package otrack;

import java.io.IOException;
import java.io.Writer;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
 *   depth=N                     stack frames to keep (default 8)
 *   interval=SECONDS            report period (default 10)
 *   out=path                    JSONL statistics output (default $TMP/object-tracker-PID.jsonl)
 *   fields=*|a,b                track instance-field values: emit per-object new/set/free events
 *                               (only honoured on the first start; classes must be loaded after or
 *                               retransformed by it)
 *   events=path                 JSONL event trace (default $TMP/object-tracker-PID-events.jsonl)
 *   logFile=path                append the agent's own [otrack] messages to this file instead of stderr
 * </pre>
 */
public final class Controller {
    private static Transformer transformer;
    private static Reporter reporter;
    private static Instrumentation inst;
    private static volatile Writer logWriter;

    private Controller() {}

    public static synchronized void command(String rawArgs, Instrumentation i) {
        Map<String, String> a = parse(rawArgs);
        // Opened once, up front, and kept open for the run: like Events.java, this dodges hosts (e.g.
        // Elasticsearch's entitlements) that grant file access at open time to whatever thread/context is
        // live during agent start-up but deny a fresh permission check made later from a background thread.
        if (a.containsKey("logFile") && logWriter == null) {
            try {
                logWriter = Files.newBufferedWriter(Path.of(a.get("logFile")), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Throwable e) {
                System.err.println("[otrack] cannot open log file " + a.get("logFile") + ": " + e);
            }
        }
        String cmd = a.getOrDefault("cmd", "start");
        try {
            switch (cmd) {
                case "start", "add" -> start(a, i);
                case "stop" -> stop();
                case "report" -> {
                    if (reporter == null) log("not running");
                    else reporter.write("on-demand");
                }
                default -> log("unknown cmd '" + cmd + "'");
            }
        } catch (Throwable t) {
            log("command '" + cmd + "' failed: " + t);
            t.printStackTrace();
        }
    }

    private static void start(Map<String, String> a, Instrumentation i) throws IOException {
        List<String[]> targets = targets(a);
        if (targets.isEmpty() && transformer == null) {
            log("no valid classes given (classes=..., classesFile=... or classesJson=...)");
            return;
        }
        inst = i;
        Tracker.inst = i;
        if (a.containsKey("sample")) Tracker.sample = Math.max(1, Integer.parseInt(a.get("sample")));
        if (a.containsKey("stacks")) Tracker.stacks = Boolean.parseBoolean(a.get("stacks"));
        if (a.containsKey("depth")) Tracker.stackDepth = Math.max(1, Integer.parseInt(a.get("depth")));

        if (transformer != null && (a.containsKey("fields") || a.containsKey("events"))) log("fields/events ignored: only applied on the first start");
        if (transformer == null) {
            Path out = Path.of(a.getOrDefault("out",
                    System.getProperty("java.io.tmpdir") + "/object-tracker-" + ProcessHandle.current().pid() + ".jsonl"));
            long intervalMs = (long) (Double.parseDouble(a.getOrDefault("interval", "10")) * 1000);
            boolean fieldsOn = a.containsKey("fields");
            Set<String> names = null;
            if (fieldsOn && !a.get("fields").equals("*")) {
                names = new HashSet<>();
                for (String f : a.get("fields").split(",")) if (!f.isBlank()) names.add(f.strip());
            }
            transformer = new Transformer(fieldsOn, names);
            Tracker.initHooks = fieldsOn;
            if (fieldsOn || a.containsKey("events")) {
                Path ev = Path.of(a.getOrDefault("events",
                        System.getProperty("java.io.tmpdir") + "/object-tracker-" + ProcessHandle.current().pid() + "-events.jsonl"));
                Events.start(ev, fieldsOn ? a.get("fields") : "");
                log("writing events to " + ev);
            }
            i.addTransformer(transformer, true);
            reporter = new Reporter(out, intervalMs);
            reporter.start();
            log("writing " + out + " every " + intervalMs + " ms");
        }
        for (String[] t : targets) transformer.addTarget(t[0], t[1] != null);
        Tracker.enabled = true;
        log("instrumented " + retransform(true) + " loaded classes; later loads are instrumented on the fly");
    }

    private static void stop() {
        if (transformer == null) {
            log("not running");
            return;
        }
        Tracker.enabled = false;
        Transformer t = transformer;
        Set<String> toRevert = Set.copyOf(t.instrumented);
        t.deactivate(); // from now on transform() returns null => originals restored on retransform
        int reverted = 0;
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (toRevert.contains(c.getName().replace('.', '/')) && inst.isModifiableClass(c)) {
                try {
                    inst.retransformClasses(c);
                    reverted++;
                } catch (Throwable e) {
                    log("cannot revert " + c.getName() + ": " + e);
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
        log("stopped, reverted " + reverted + " classes");
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
        return transformer == null ? 0 : retransform(true);
    }

    /** Retransforms loaded classes that match but are not yet instrumented. */
    private static int retransform(boolean onlyNew) {
        int n = 0;
        Module trackerModule = Tracker.class.getModule();
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (c.isInterface() || c.isArray() || c.isPrimitive() || !inst.isModifiableClass(c)) continue;
            if (!transformer.matches(c)) continue;
            if (onlyNew && transformer.instrumented.contains(c.getName().replace('.', '/'))) continue;
            try {
                Module m = c.getModule();
                if (m.isNamed() && !m.canRead(trackerModule)) {
                    inst.redefineModule(m, Set.of(trackerModule), Map.of(), Map.of(), Set.of(), Map.of());
                }
                inst.retransformClasses(c);
                n++;
            } catch (Throwable t) {
                log("cannot retransform " + c.getName() + ": " + t);
            }
        }
        return n;
    }

    /** Each entry: {dottedName, "+" or null}. */
    private static List<String[]> targets(Map<String, String> a) throws IOException {
        List<String> raw = new ArrayList<>();
        if (a.containsKey("classesJson")) raw.addAll(yesClasses(Path.of(a.get("classesJson"))));
        if (a.containsKey("classes")) raw.addAll(List.of(a.get("classes").split(",")));
        if (a.containsKey("classesFile")) {
            for (String line : Files.readAllLines(Path.of(a.get("classesFile")))) {
                line = line.strip();
                if (!line.isEmpty() && !line.startsWith("#")) raw.add(line);
            }
        }
        List<String[]> out = new ArrayList<>();
        for (String r : raw) {
            r = r.strip();
            boolean sub = r.startsWith("+");
            String n = sub ? r.substring(1).strip() : r;
            if (n.isEmpty()) continue;
            if (n.matches("(java|javax|jdk|sun|com\\.sun|otrack)\\..*")) {
                log("refusing JDK/agent class " + n);
                continue;
            }
            out.add(new String[] {n, sub ? "+" : null});
        }
        return out;
    }

    /** Reads a flat JSON object of {@code "class": "YES"|"NO"} and returns the YES keys. */
    private static List<String> yesClasses(Path file) throws IOException {
        Map<String, String> m = Json.flatStringMap(Files.readString(file, StandardCharsets.UTF_8));
        List<String> yes = new ArrayList<>();
        for (Map.Entry<String, String> e : m.entrySet()) {
            String v = e.getValue().strip();
            if (v.equalsIgnoreCase("YES")) yes.add(e.getKey());
            else if (!v.equalsIgnoreCase("NO")) log("classesJson: ignoring '" + e.getKey() + "', value must be YES or NO, got '" + v + "'");
        }
        log("classesJson: " + yes.size() + " YES of " + m.size() + " entries in " + file);
        return yes;
    } 

    private static Map<String, String> parse(String s) {
        Map<String, String> m = new HashMap<>();
        if (s == null) return m;
        for (String kv : s.split(";")) {
            int eq = kv.indexOf('=');
            if (eq > 0) m.put(kv.substring(0, eq).strip(), kv.substring(eq + 1).strip());
            else if (!kv.isBlank()) m.put("cmd", kv.strip());
        }
        return m;
    }

    static void log(String msg) {
        Writer w = logWriter;
        if (w != null) {
            try {
                synchronized (Controller.class) {
                    w.write(Instant.now() + " [otrack] " + msg + System.lineSeparator());
                    w.flush();
                }
                return;
            } catch (Throwable e) {
                // Catches more than IOException on purpose: under a SecurityManager (e.g. Elasticsearch's
                // bootstrap entitlements), a write from a thread not covered by the host's own grants throws
                // AccessControlException/SecurityException, not IOException. This method must never propagate
                // an exception, since callers include the otrack-reporter daemon thread, whose sole job is the
                // periodic instrumentation sweep in Controller.sweepMissed() -- letting a logging failure kill
                // that thread silently disables all further catch-up retransformation for the rest of the run.
                System.err.println("[otrack] cannot write log file: " + e);
                logWriter = null; // stop retrying every call for the rest of the run; fall back to stderr
            }
        }
        System.err.println("[otrack] " + msg);
    }
}
