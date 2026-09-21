package otrack;

import java.lang.instrument.Instrumentation;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Runtime state and hot path. Lives on the bootstrap class path so that instrumented classes from
 * any class loader link to the same copy. Only JDK types are used here.
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

    static volatile boolean enabled;
    static volatile int sample = 1;
    static volatile boolean stacks;
    static volatile int stackDepth = 8;
    static volatile boolean initHooks; // classes were instrumented to report field values at constructor exit
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
                    Events.emit(head("new", r).append(",\"th\":").append(Reporter.str(Thread.currentThread().getName()))
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
        sb.append(Reporter.str(name)).append(':').append(json).append(',');
    }

    public static void onInitDone(Object o) {
        Pending p = PENDING.get();
        if (p.o != o) return;
        try {
            Ref r = p.r;
            p.o = null;
            p.r = null;
            StringBuilder b = head("new", r).append(",\"th\":").append(Reporter.str(Thread.currentThread().getName()))
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

    private static void set(Ref r, String name, String json) {
        try {
            Events.emit(head("set", r).append(",\"th\":").append(Reporter.str(Thread.currentThread().getName()))
                    .append(",\"field\":").append(Reporter.str(name)).append(",\"v\":").append(json)
                    .append(at()).append('}').toString());
        } catch (Throwable t) {
            errors.increment();
        }
    }

    public static void onInit(Object o, boolean v, String n) { if (initing(o)) init(n, String.valueOf(v)); }
    public static void onInit(Object o, char v, String n) { if (initing(o)) init(n, Reporter.str(String.valueOf(v))); }
    public static void onInit(Object o, int v, String n) { if (initing(o)) init(n, String.valueOf(v)); }
    public static void onInit(Object o, long v, String n) { if (initing(o)) init(n, String.valueOf(v)); }
    public static void onInit(Object o, float v, String n) { if (initing(o)) init(n, num(v)); }
    public static void onInit(Object o, double v, String n) { if (initing(o)) init(n, num(v)); }
    public static void onInit(Object o, Object v, String n) { if (initing(o)) init(n, val(v)); }

    public static void onSet(Object o, boolean v, String n) { Ref r = setRef(o); if (r != null) set(r, n, String.valueOf(v)); }
    public static void onSet(Object o, char v, String n) { Ref r = setRef(o); if (r != null) set(r, n, Reporter.str(String.valueOf(v))); }
    public static void onSet(Object o, int v, String n) { Ref r = setRef(o); if (r != null) set(r, n, String.valueOf(v)); }
    public static void onSet(Object o, long v, String n) { Ref r = setRef(o); if (r != null) set(r, n, String.valueOf(v)); }
    public static void onSet(Object o, float v, String n) { Ref r = setRef(o); if (r != null) set(r, n, num(v)); }
    public static void onSet(Object o, double v, String n) { Ref r = setRef(o); if (r != null) set(r, n, num(v)); }
    public static void onSet(Object o, Object v, String n) { Ref r = setRef(o); if (r != null) set(r, n, val(v)); }

    private static StringBuilder head(String ev, Ref r) {
        return new StringBuilder(160).append("{\"t\":").append((System.nanoTime() - T0) / 1000L)
                .append(",\"ev\":\"").append(ev).append("\",\"id\":").append(r.id)
                .append(",\"class\":").append(Reporter.str(r.stats.name));
    }

    private static String num(double d) {
        return Double.isFinite(d) ? Double.toString(d) : Reporter.str(Double.toString(d));
    }

    private static String num(float f) {
        return Float.isFinite(f) ? Float.toString(f) : Reporter.str(Float.toString(f));
    }

    /** JSON for an object-typed value. Never calls toString()/hashCode() of application objects. */
    private static String val(Object v) {
        if (v == null) return "null";
        if (v instanceof String s) return Reporter.str(s.length() > MAX_STR ? s.substring(0, MAX_STR) + "..." : s);
        if (v instanceof Boolean || v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte)
            return v.toString();
        if (v instanceof Double d) return num(d.doubleValue());
        if (v instanceof Float f) return num(f.floatValue());
        if (v instanceof Character c) return Reporter.str(c.toString());
        if (v instanceof Enum<?> e) return Reporter.str(e.name());
        if (v instanceof Class<?> c) return Reporter.str(c.getName());
        Ref t = find(v);
        return "{\"class\":" + Reporter.str(v.getClass().getName()) + (t != null ? ",\"id\":" + t.id : "") + "}";
    }

    /** ,"at":[...] with the caller's frames when stacks=true, else "". */
    private static String at() {
        if (!stacks) return "";
        final int depth = stackDepth;
        List<String> fr = StackWalker.getInstance().walk(st -> st
                .filter(f -> !f.getClassName().startsWith("otrack."))
                .limit(depth)
                .map(f -> f.getClassName() + "." + f.getMethodName() + ":" + f.getLineNumber())
                .toList());
        StringBuilder b = new StringBuilder(",\"at\":[");
        for (int i = 0; i < fr.size(); i++) {
            if (i > 0) b.append(',');
            b.append(Reporter.str(fr.get(i)));
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
