package otrack;

import java.lang.instrument.Instrumentation;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import com.google.gson.JsonPrimitive;

/**
 * Runtime state and hot path. Lives on the bootstrap class path so that instrumented classes from
 * any class loader link to the same copy. Only JDK types and the relocated Gson from the same jar are used here.
 *
 * Per tracked object cost: one getClass() compare + one LongAdder increment; sampled objects
 * additionally cost one WeakReference and (optionally) one stack walk.
 */
public final class Tracker {
    static final int MAX_CLASSES = 4096;
    static final int MAX_SITES = 1000;
    static final long T0 = System.nanoTime();

    static final ClassStats[] STATS = new ClassStats[MAX_CLASSES];
    private static final Map<String, Integer> BY_NAME = new HashMap<>();
    private static int nextId;

    static final ReferenceQueue<Object> QUEUE = new ReferenceQueue<>();
    /** Tracked objects, looked up by identity of the referent (see {@link Ref#equals}). */
    static final ConcurrentHashMap<Ref, Ref> LIVE = new ConcurrentHashMap<>();
    private static final AtomicLong IDS = new AtomicLong();
    private static final int MAX_STR = 256;
    private static final int MAX_ELEMS = 100; // elements shown per collection/map in the dumpOnSet dump

    static volatile boolean enabled;
    static volatile int sample = 1;
    static volatile boolean stacks;
    static volatile int stackDepth = 8;
    static volatile boolean initHooks; // classes were instrumented to report field values at constructor exit
    static volatile boolean dumpOnSet; // set events also carry the field values of every live tracked object
    static volatile Instrumentation inst;
    static final LongAdder errors = new LongAdder();

    private Tracker() {}

    static final class Site {
        final String[] frames; // null for the "no stack captured" site
        final LongAdder live = new LongAdder();

        Site(String[] frames) {
            this.frames = frames;
        }
    }

    static final class ClassStats {
        final String name;
        final int id;
        final LongAdder allocated = new LongAdder();
        final LongAdder freed = new LongAdder();
        final LongAdder live = new LongAdder();
        final LongAdder birthSumMs = new LongAdder(); // sum of weight * birth time (ms since T0) of live objects
        final Site anonymous = new Site(null);
        final ConcurrentHashMap<Long, Site> sites = new ConcurrentHashMap<>();
        volatile long shallowSize;

        ClassStats(String name, int id) {
            this.name = name;
            this.id = id;
        }
    }

    static final class Ref extends WeakReference<Object> {
        final ClassStats stats;
        final Site site;
        final int weight;
        final long bornMs;
        final long id;
        private final int hash;

        Ref(Object o, ClassStats stats, Site site, int weight, long bornMs) {
            super(o, QUEUE);
            this.stats = stats;
            this.site = site;
            this.weight = weight;
            this.bornMs = bornMs;
            this.id = IDS.incrementAndGet();
            this.hash = System.identityHashCode(o);
        }

        /** Lookup key: not queued, equal to the tracked Ref of the same object. */
        private Ref(Object probe) {
            super(probe);
            this.stats = null;
            this.site = null;
            this.weight = 0;
            this.bornMs = 0;
            this.id = 0;
            this.hash = System.identityHashCode(probe);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object x) {
            if (x == this) return true;
            if (!(x instanceof Ref r)) return false;
            Object a = get();
            return a != null && a == r.get();
        }
    }

    static Ref find(Object o) {
        return LIVE.get(new Ref(o));
    }

    /** Returns the id baked into instrumented bytecode for this class name, or -1 if the table is full. */
    static synchronized int register(String dottedName) {
        Integer id = BY_NAME.get(dottedName);
        if (id != null) return id;
        if (nextId >= MAX_CLASSES) return -1;
        int n = nextId++;
        BY_NAME.put(dottedName, n);
        STATS[n] = new ClassStats(dottedName, n);
        return n;
    }

    /** Zeroes all statistics. Ids stay valid so stragglers running old code never see null. */
    static synchronized void reset() {
        LIVE.clear();
        for (int i = 0; i < nextId; i++) STATS[i] = new ClassStats(STATS[i].name, i);
    }

    static synchronized List<ClassStats> snapshotStats() {
        List<ClassStats> out = new ArrayList<>(nextId);
        for (int i = 0; i < nextId; i++) out.add(STATS[i]);
        return out;
    }

    /** Called from the tail of every instrumented (non-delegating) constructor. */
    public static void onNew(Object o, Class<?> declared, int id) {
        if (o.getClass() != declared || !enabled) return; // only the most-derived constructor records
        try {
            ClassStats s = STATS[id];
            s.allocated.increment();
            int n = sample;
            if (n > 1 && ThreadLocalRandom.current().nextInt(n) != 0) return;
            Ref r = track(o, s, n);
            if (Events.on) {
                if (initHooks) {
                    Pending p = PENDING.get(); // completed by onInit.../onInitDone, which follow in the same constructor
                    p.o = o;
                    p.r = r;
                    p.sb.setLength(0);
                } else {
                    Events.emit(head("new", r).append(",\"th\":").append(quote(Thread.currentThread().getName()))
                            .append(at()).append('}').toString());
                }
            }
        } catch (Throwable t) {
            errors.increment();
        }
    }

    private static Ref track(Object o, ClassStats s, int weight) {
        Site site = stacks ? capture(s) : s.anonymous;
        long bornMs = (System.nanoTime() - T0) / 1_000_000L;
        Ref r = new Ref(o, s, site, weight, bornMs);
        LIVE.put(r, r);
        s.live.add(weight);
        s.birthSumMs.add(weight * bornMs);
        site.live.add(weight);
        if (s.shallowSize == 0) {
            Instrumentation i = inst;
            if (i != null) s.shallowSize = i.getObjectSize(o);
        }
        return r;
    }

    /** Called by the reporter thread for every reference the GC has cleared. */
    static void release(Ref r) {
        if (LIVE.remove(r) == null) return; // wiped by reset()
        ClassStats s = r.stats;
        s.live.add(-r.weight);
        s.freed.add(r.weight);
        s.birthSumMs.add(-r.weight * r.bornMs);
        r.site.live.add(-r.weight);
        if (Events.on) Events.emit(head("free", r).append('}').toString());
    }

    // ---- field value events -------------------------------------------------------------------
    // Called from injected bytecode. onInit* run at the tail of the most-derived constructor (one per
    // tracked field, then onInitDone) and are merged into one "new" event. onSet* run before every
    // PUTFIELD of a tracked field. Objects that were not sampled by onNew are ignored.

    private static final class Pending {
        Object o;
        Ref r;
        final StringBuilder sb = new StringBuilder();
    }

    private static final ThreadLocal<Pending> PENDING = ThreadLocal.withInitial(Pending::new);

    private static boolean initing(Object o) {
        return PENDING.get().o == o;
    }

    private static void init(String name, String json) {
        StringBuilder sb = PENDING.get().sb;
        sb.append(quote(name)).append(':').append(json).append(',');
    }

    public static void onInitDone(Object o) {
        Pending p = PENDING.get();
        if (p.o != o) return;
        try {
            Ref r = p.r;
            p.o = null;
            p.r = null;
            StringBuilder b = head("new", r).append(",\"th\":").append(quote(Thread.currentThread().getName()))
                    .append(at()).append(",\"f\":{");
            if (p.sb.length() > 0) b.append(p.sb, 0, p.sb.length() - 1);
            Events.emit(b.append("}}").toString());
        } catch (Throwable t) {
            errors.increment();
        }
    }

    private static Ref setRef(Object o) {
        return Events.on && enabled ? find(o) : null;
    }

    /** {@code v} is the new value of an object-typed field (so the dump can open collections), else null. */
    private static void set(Object o, Ref r, String name, String json, Object v) {
        try {
            StringBuilder b = head("set", r).append(",\"th\":").append(quote(Thread.currentThread().getName()))
                    .append(",\"field\":").append(quote(name)).append(",\"v\":").append(json).append(stack());
            if (dumpOnSet) dumpAll(b, o, name, v != null ? expand(v) : json);
            Events.emit(b.append('}').toString());
        } catch (Throwable t) {
            errors.increment();
        }
    }

    // ---- dumpOnSet: field values of every live tracked object ----------------------------------

    /** An instance field of a tracked class or one of its superclasses, and its key in the dump. */
    private record Slot(Field field, String key) {}

    /**
     * Every readable instance field of a class, most-derived first. A field hidden by a subclass field of the
     * same name is keyed {@code Declarer.name}. Fields that cannot be made accessible are left out.
     */
    private static final ClassValue<Slot[]> SLOTS = new ClassValue<>() {
        @Override
        protected Slot[] computeValue(Class<?> c) {
            List<Slot> out = new ArrayList<>();
            Set<String> keys = new HashSet<>();
            try {
                for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                    boolean open = open(k);
                    for (Field f : k.getDeclaredFields()) {
                        if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) continue;
                        if (!open || !f.trySetAccessible()) continue;
                        String key = keys.add(f.getName()) ? f.getName() : k.getSimpleName() + "." + f.getName();
                        out.add(new Slot(f, key));
                    }
                }
            } catch (Throwable t) {
                errors.increment(); // e.g. denied by a security manager: keep what was collected, don't retry
            }
            return out.toArray(new Slot[0]);
        }
    };

    /** Whether k's package is open to the tracker, opening it via Instrumentation if needed and possible. */
    private static boolean open(Class<?> k) {
        Module m = k.getModule(), me = Tracker.class.getModule();
        String pkg = k.getPackageName();
        if (m.isOpen(pkg, me)) return true;
        Instrumentation i = inst;
        if (i == null || !i.isModifiableModule(m)) return false;
        i.redefineModule(m, Set.of(), Map.of(), Map.of(pkg, Set.of(me)), Set.of(), Map.of());
        return m.isOpen(pkg, me);
    }

    /**
     * Appends ,"all":[{"id":..,"class":..,"f":{..}},..] with every live tracked object. Runs before the
     * PUTFIELD, so the field being written on {@code written} shows the new value passed to the hook.
     */
    private static void dumpAll(StringBuilder b, Object written, String name, String json) throws IllegalAccessException {
        b.append(",\"all\":[");
        boolean first = true;
        for (Ref r : LIVE.keySet()) {
            Object o = r.get();
            if (o == null) continue; // collected, not yet released
            if (!first) b.append(',');
            first = false;
            b.append("{\"id\":").append(r.id).append(",\"class\":").append(quote(r.stats.name)).append(",\"f\":{");
            Slot[] slots = SLOTS.get(o.getClass());
            for (int i = 0; i < slots.length; i++) {
                Slot s = slots[i];
                if (i > 0) b.append(',');
                b.append(quote(s.key)).append(':')
                        .append(o == written && s.key.equals(name) ? json : fieldVal(s.field, o));
            }
            b.append("}}");
        }
        b.append(']');
    }

    private static String fieldVal(Field f, Object o) throws IllegalAccessException {
        Class<?> t = f.getType();
        if (t == float.class) return num(f.getFloat(o));
        if (t == double.class) return num(f.getDouble(o));
        if (t == char.class) return quote(String.valueOf(f.getChar(o)));
        return expand(f.get(o)); // other primitives come back boxed, which val() prints as plain JSON
    }

    /**
     * Like {@link #val}, but a JDK Collection or Map is opened one level: {@code "size"} plus up to
     * {@link #MAX_ELEMS} elements ({@code "items"}) or key/value pairs ({@code "entries":[[k,v],..]}), each
     * printed by val(). Only bootstrap-loaded classes are opened, so no application collection code runs;
     * if iterating fails (e.g. a concurrent modification) the plain val() form is used.
     */
    private static String expand(Object v) {
        if (v == null || v.getClass().getClassLoader() != null || !(v instanceof Collection<?> || v instanceof Map<?, ?>))
            return val(v);
        try {
            StringBuilder b = objHead(v);
            int n = 0;
            if (v instanceof Map<?, ?> m) {
                b.append(",\"size\":").append(m.size()).append(",\"entries\":[");
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (n++ == MAX_ELEMS) break;
                    if (n > 1) b.append(',');
                    b.append('[').append(val(e.getKey())).append(',').append(val(e.getValue())).append(']');
                }
            } else {
                Collection<?> c = (Collection<?>) v;
                b.append(",\"size\":").append(c.size()).append(",\"items\":[");
                for (Object x : c) {
                    if (n++ == MAX_ELEMS) break;
                    if (n > 1) b.append(',');
                    b.append(val(x));
                }
            }
            return b.append("]}").toString();
        } catch (Throwable t) {
            return val(v);
        }
    }

    public static void onInit(Object o, boolean v, String n) { if (initing(o)) init(n, String.valueOf(v)); }
    public static void onInit(Object o, char v, String n) { if (initing(o)) init(n, quote(String.valueOf(v))); }
    public static void onInit(Object o, int v, String n) { if (initing(o)) init(n, String.valueOf(v)); }
    public static void onInit(Object o, long v, String n) { if (initing(o)) init(n, String.valueOf(v)); }
    public static void onInit(Object o, float v, String n) { if (initing(o)) init(n, num(v)); }
    public static void onInit(Object o, double v, String n) { if (initing(o)) init(n, num(v)); }
    public static void onInit(Object o, Object v, String n) { if (initing(o)) init(n, val(v)); }

    public static void onSet(Object o, boolean v, String n) { Ref r = setRef(o); if (r != null) set(o, r, n, String.valueOf(v), null); }
    public static void onSet(Object o, char v, String n) { Ref r = setRef(o); if (r != null) set(o, r, n, quote(String.valueOf(v)), null); }
    public static void onSet(Object o, int v, String n) { Ref r = setRef(o); if (r != null) set(o, r, n, String.valueOf(v), null); }
    public static void onSet(Object o, long v, String n) { Ref r = setRef(o); if (r != null) set(o, r, n, String.valueOf(v), null); }
    public static void onSet(Object o, float v, String n) { Ref r = setRef(o); if (r != null) set(o, r, n, num(v), null); }
    public static void onSet(Object o, double v, String n) { Ref r = setRef(o); if (r != null) set(o, r, n, num(v), null); }
    public static void onSet(Object o, Object v, String n) { Ref r = setRef(o); if (r != null) set(o, r, n, val(v), v); }

    private static StringBuilder head(String ev, Ref r) {
        return new StringBuilder(160).append("{\"t\":").append((System.nanoTime() - T0) / 1000L)
                .append(",\"ev\":\"").append(ev).append("\",\"id\":").append(r.id)
                .append(",\"class\":").append(quote(r.stats.name));
    }

    private static String quote(String s) {
        return new JsonPrimitive(s).toString();
    }

    private static String num(double d) {
        return Double.isFinite(d) ? Double.toString(d) : quote(Double.toString(d));
    }

    private static String num(float f) {
        return Float.isFinite(f) ? Float.toString(f) : quote(Float.toString(f));
    }

    /** JSON for an object-typed value. Never calls toString()/hashCode() of application objects. */
    private static String val(Object v) {
        if (v == null) return "null";
        if (v instanceof String s) return quote(s.length() > MAX_STR ? s.substring(0, MAX_STR) + "..." : s);
        if (v instanceof Boolean || v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte)
            return v.toString();
        if (v instanceof Double d) return num(d.doubleValue());
        if (v instanceof Float f) return num(f.floatValue());
        if (v instanceof Character c) return quote(c.toString());
        if (v instanceof Enum<?> e) return quote(e.name());
        if (v instanceof Class<?> c) return quote(c.getName());
        return objHead(v).append('}').toString();
    }

    /** {"class":..[,"id":..] without the closing brace; the id only if v is itself tracked. */
    private static StringBuilder objHead(Object v) {
        Ref t = find(v);
        StringBuilder b = new StringBuilder("{\"class\":").append(quote(v.getClass().getName()));
        return t != null ? b.append(",\"id\":").append(t.id) : b;
    }

    /** ,"at":[...] with the caller's frames when stacks=true, else "". */
    private static String at() {
        return stacks ? stack() : "";
    }

    /** ,"at":[...] with up to depth= frames of the caller, innermost first, skipping the tracker's own. */
    private static String stack() {
        final int depth = stackDepth;
        List<String> fr = StackWalker.getInstance().walk(st -> st
                .filter(f -> !f.getClassName().startsWith("otrack."))
                .limit(depth)
                .map(f -> f.getClassName() + "." + f.getMethodName() + ":" + f.getLineNumber())
                .toList());
        StringBuilder b = new StringBuilder(",\"at\":[");
        for (int i = 0; i < fr.size(); i++) {
            if (i > 0) b.append(',');
            b.append(quote(fr.get(i)));
        }
        return b.append(']').toString();
    }

    private static Site capture(ClassStats s) {
        final int depth = stackDepth;
        return StackWalker.getInstance().walk(st -> {
            // frames: capture, track, onNew, <init> -> report the constructor's caller first
            Object[] fr = st.skip(4).limit(depth).toArray();
            long h = 1125899906842597L;
            for (Object f : fr) {
                StackWalker.StackFrame sf = (StackWalker.StackFrame) f;
                h = 31 * h + sf.getClassName().hashCode();
                h = 31 * h + sf.getMethodName().hashCode();
                h = 31 * h + sf.getLineNumber();
            }
            Site site = s.sites.get(h);
            if (site == null) {
                if (s.sites.size() >= MAX_SITES) return s.anonymous;
                String[] frames = new String[fr.length];
                for (int i = 0; i < fr.length; i++) {
                    StackWalker.StackFrame sf = (StackWalker.StackFrame) fr[i];
                    frames[i] = sf.getClassName() + "." + sf.getMethodName() + ":" + sf.getLineNumber();
                }
                site = s.sites.computeIfAbsent(h, k -> new Site(frames));
            }
            return site;
        });
    }
}
