package otrack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Runs {@code fixture.Main} in a separate JVM under the real agent jars (built by the otrackTest task's
 * dependencies), once with -javaagent and once attached to the running JVM.
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class AgentEndToEndTest {
    private static final String AGENT_JAR = System.getProperty("otrack.agentJar");

    @TempDir
    Path tmp;

    @Test
    void premainTracksAllocationsAndFieldWrites() throws Exception {
        Path events = tmp.resolve("events.jsonl"), stats = tmp.resolve("stats.jsonl"), log = tmp.resolve("agent.log");
        Path console = tmp.resolve("console.txt");
        Process p = java("-javaagent:" + AGENT_JAR + "=classes=+fixture.Account;fields=balance;interval=0.2"
                + ";events=" + events + ";out=" + stats + ";logFile=" + log, "fixture.Main", "700")
                .redirectOutput(console.toFile()).start();
        assertEquals(0, p.waitFor(), () -> read(console));

        String agentLog = read(log);
        assertFalse(agentLog.contains("cannot"), agentLog);

        List<JsonObject> ev = TransformerTest.readJsonl(events);
        assertEquals("balance", ev.get(0).get("fields").getAsString());
        assertEquals("end", ev.get(ev.size() - 1).get("ev").getAsString());
        assertTrue(ev.stream().anyMatch(e -> isEvent(e, "new", "fixture.Savings")), "subclass tracked via '+'");
        JsonObject set = ev.stream().filter(e -> isEvent(e, "set", "fixture.Account")).findFirst().orElseThrow();
        assertEquals(5, set.get("v").getAsLong());
        JsonArray at = set.getAsJsonArray("at");
        assertTrue(at.get(0).getAsString().startsWith("fixture.Account.deposit:"), at.toString());
        assertTrue(at.get(1).getAsString().startsWith("fixture.Main.main:"), at.toString());

        List<JsonObject> lines = TransformerTest.readJsonl(stats);
        assertFalse(lines.isEmpty(), "at least one interval report in 700 ms at interval=0.2");
        assertTrue(allocated(lines.get(lines.size() - 1), "fixture.Account") > 0, lines.toString());
    }

    @Test
    void attachStartReportStop() throws Exception {
        Path stats = tmp.resolve("stats.jsonl"), log = tmp.resolve("agent.log");
        Process target = java("-XX:+EnableDynamicAgentLoading", "fixture.Main", "60000").redirectErrorStream(true).start();
        try {
            awaitLine(target, "ready"); // fixture classes are loaded, so starting must retransform them
            String pid = Long.toString(target.pid());

            attach(pid, "classes=+fixture.Account;interval=60;out=" + stats + ";logFile=" + log);
            Thread.sleep(300);
            attach(pid, "report");
            attach(pid, "stop");

            String agentLog = read(log);
            assertTrue(agentLog.contains("instrumented 2 loaded classes"), agentLog);
            assertTrue(agentLog.contains("stopped, reverted 2 classes"), agentLog);

            List<JsonObject> lines = TransformerTest.readJsonl(stats);
            assertEquals(List.of("on-demand", "final"), lines.stream().map(l -> l.get("reason").getAsString()).toList());
            assertTrue(allocated(lines.get(0), "fixture.Account") > 0, lines.toString());
        } finally {
            target.destroyForcibly();
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** A JVM like this one, with the fixture classes (and nothing of otrack's) on its class path. */
    private static ProcessBuilder java(String... args) throws Exception {
        Path fixtures = Path.of(fixture.Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> cmd = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xshare:off", "-cp", fixtures.toString()));
        cmd.addAll(List.of(args));
        return new ProcessBuilder(cmd);
    }

    private static void attach(String pid, String args) throws Exception {
        Process p = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", AGENT_JAR, "otrack.agent.Attach", pid, args).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), out);
    }

    private static void awaitLine(Process p, String expected) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder seen = new StringBuilder();
        for (String line; (line = r.readLine()) != null; ) {
            if (line.equals(expected)) return;
            seen.append(line).append('\n');
        }
        throw new AssertionError("process ended before printing '" + expected + "':\n" + seen);
    }

    private static boolean isEvent(JsonObject e, String ev, String cls) {
        return e.has("class") && e.get("ev").getAsString().equals(ev) && e.get("class").getAsString().equals(cls);
    }

    private static long allocated(JsonObject statsLine, String cls) {
        for (JsonElement c : statsLine.getAsJsonArray("classes")) {
            JsonObject o = c.getAsJsonObject();
            if (o.get("class").getAsString().equals(cls)) return o.get("allocated").getAsLong();
        }
        return 0;
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            return "(cannot read " + p + ": " + e + ")";
        }
    }
}
