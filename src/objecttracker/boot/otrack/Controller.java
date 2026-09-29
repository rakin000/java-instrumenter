package otrack;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Entry point invoked reflectively by the agent. Args are {@code key=value} pairs joined by ';':
 * <pre>
 *   cmd=start|add|stop|report   (default start; add == start)
 *   classes=a.B,+c.D            comma list; '+' also tracks subclasses / implementors
 *   classesFile=path            one class per line, '#' comments
 *   classesJson=path            JSON object {"a.B": "YES", "+c.D": "NO", ...}; only YES entries are instrumented
 *                               ('+' prefix as in classes=). Combined with classes=/classesFile=
 *   (names without a package, e.g. "Foo", match Foo in any non-JDK package, and Outer$Foo as "Foo")
 *   sample=N                    keep 1 in N objects for liveness tracking (default 1 = all)
 *   stacks=true|false           record allocation stacks of sampled objects (default false)
 *   depth=N                     stack frames to keep, for stacks= and for set events (default 8)
 *   interval=SECONDS            report period (default 10)
 *   out=path                    JSONL statistics output (default $TMP/object-tracker-PID.jsonl)
 *   fields=*|a,b                track instance-field values: emit per-object new/set/free events; set events
 *                               always carry the writer's call stack ("at")
 *                               (only honoured on the first start; classes must be loaded after or
 *                               retransformed by it)
 *   dumpOnSet=true|false        each set event also carries "all": the current instance-field values (own and
 *                               inherited, read reflectively) of every live tracked object of every marked
 *                               class (default false; costs O(live objects) per write -- combine with sample=)
 *   events=path                JSONL event trace (default $TMP/object-tracker-PID-events.jsonl)
 *   logFile=path                append the agent's own [otrack] messages to this file instead of stderr
 * </pre>
 */
public final class Controller {
    private static Transformer transformer;
    private static Reporter reporter;
    private static Instrumentation inst;

    private Controller() {}

    public static synchronized void command(String rawArgs, Instrumentation i) {
        Options o = Options.parse(rawArgs);
        if (o.has("logFile")) Log.openFile(Path.of(o.get("logFile")));
        String cmd = o.get("cmd", "start");
        try {
            switch (cmd) {
                case "start", "add" -> start(o, i);
                case "stop" -> stop();
                case "report" -> {
                    if (reporter == null) Log.log("not running");
                    else reporter.write("on-demand");
                }
                default -> Log.log("unknown cmd '" + cmd + "'");
            }
        } catch (Throwable t) {
            Log.log("command '" + cmd + "' failed: " + t);
            t.printStackTrace();
        }
    }

    private static void start(Options o, Instrumentation i) throws IOException {
        List<Target> targets = Target.readAll(o);
        if (targets.isEmpty() && transformer == null) {
            Log.log("no valid classes given (classes=..., classesFile=... or classesJson=...)");
            return;
        }
        inst = i;
        Tracker.inst = i;
        if (o.has("sample")) Tracker.sample = o.intAtLeast("sample", 1);
        if (o.has("stacks")) Tracker.stacks = Boolean.parseBoolean(o.get("stacks"));
        if (o.has("depth")) Tracker.stackDepth = o.intAtLeast("depth", 1);
        if (o.has("dumpOnSet")) Tracker.dumpOnSet = Boolean.parseBoolean(o.get("dumpOnSet"));

        if (transformer == null) firstStart(o, i);
        else if (o.has("fields") || o.has("events")) Log.log("fields/events ignored: only applied on the first start");
        for (Target t : targets) transformer.addTarget(t);
        Tracker.enabled = true;
        Log.log("instrumented " + retransformMissed() + " loaded classes; later loads are instrumented on the fly");
    }

    /** Sets up what lives for the whole run: the transformer, the event trace and the reporter. */
    private static void firstStart(Options o, Instrumentation i) throws IOException {
        Path out = o.path("out", ".jsonl");
        long intervalMs = (long) (o.decimal("interval", 10) * 1000);
        boolean fieldsOn = o.has("fields");
        Set<String> fieldNames = !fieldsOn || o.get("fields").equals("*") ? null : Set.copyOf(o.list("fields"));

        transformer = new Transformer(fieldsOn, fieldNames);
        Tracker.initHooks = fieldsOn;
        if (fieldsOn || o.has("events")) {
            Path ev = o.path("events", "-events.jsonl");
            Events.start(ev, fieldsOn ? o.get("fields") : "");
            Log.log("writing events to " + ev);
        }
        i.addTransformer(transformer, true);
        reporter = new Reporter(out, intervalMs);
        reporter.start();
        Log.log("writing " + out + " every " + intervalMs + " ms");
    }

    private static void stop() {
        if (transformer == null) {
            Log.log("not running");
            return;
        }
        Tracker.enabled = false;
        Transformer t = transformer;
        Set<String> toRevert = t.instrumentedNames();
        t.deactivate();
        int reverted = 0;
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (toRevert.contains(c.getName().replace('.', '/')) && inst.isModifiableClass(c)) {
                try {
                    inst.retransformClasses(c);
                    reverted++;
                } catch (Throwable e) {
                    Log.log("cannot revert " + c.getName() + ": " + e);
                }
            }
        }
        inst.removeTransformer(t);
        reporter.stop();
        Events.stop();
        Tracker.initHooks = false;
        Tracker.reset();
        transformer = null;
        reporter = null;
        Log.log("stopped, reverted " + reverted + " classes");
    }

    /**
     * Re-scans all currently loaded classes and retransforms any target that {@link Transformer#transform}
     * never got a chance to instrument on its own class-load callback (e.g. classes defined into a plugin's
     * own {@code ModuleLayer}/classloader after start-up, which for some plugin loading paths do not appear
     * to reliably trigger the JVM's live class-file-load hook the way ordinary classloaders do). Called
     * periodically by {@link Reporter} so such classes are caught within one report interval of loading,
     * instead of only once at agent start (when nothing is loaded yet). Safe to call after {@link #stop}.
     */
    static synchronized int sweepMissed() {
        return transformer == null ? 0 : retransformMissed();
    }

    /** Retransforms loaded classes that match but are not yet instrumented. */
    private static int retransformMissed() {
        int n = 0;
        Module trackerModule = Tracker.class.getModule();
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (c.isInterface() || c.isArray() || c.isPrimitive() || !inst.isModifiableClass(c)) continue;
            if (!transformer.matches(c) || transformer.isInstrumented(c)) continue;
            try {
                Module m = c.getModule();
                if (m.isNamed() && !m.canRead(trackerModule)) {
                    inst.redefineModule(m, Set.of(trackerModule), Map.of(), Map.of(), Set.of(), Map.of());
                }
                inst.retransformClasses(c);
                n++;
            } catch (Throwable t) {
                Log.log("cannot retransform " + c.getName() + ": " + t);
            }
        }
        return n;
    }
}
