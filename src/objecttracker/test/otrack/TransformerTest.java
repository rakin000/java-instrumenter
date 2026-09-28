package otrack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Instruments the fixture classes with {@link Transformer}, runs them, and checks what {@link Tracker} recorded.
 * No agent is involved: {@link InstrumentingLoader} passes the bytes through the transformer itself.
 */
class TransformerTest {
    @TempDir
    Path tmp;

    @BeforeEach
    void resetTracker() {
        Events.stop();
        Tracker.reset();
        Tracker.enabled = true;
        Tracker.sample = 1;
        Tracker.stacks = false;
        Tracker.initHooks = false;
    }

    @AfterEach
    void stopTracking() {
        Events.stop();
        Tracker.enabled = false;
        Tracker.initHooks = false;
    }

    @Test
    void countsEachObjectOnceUnderItsOwnClass() throws Exception {
        Transformer t = transformer(false, "+fixture.Account");
        ClassLoader l = new InstrumentingLoader(t);
        Object a = account(l, "alice", 10);
        Object b = l.loadClass("fixture.Account").getConstructor(String.class).newInstance("bob"); // this(...)
        Object s = l.loadClass("fixture.Savings").getConstructor(String.class).newInstance("carol"); // super(...)

        assertEquals(2, stats("fixture.Account").allocated.sum());
        assertEquals(2, stats("fixture.Account").live.sum());
        assertEquals(1, stats("fixture.Savings").allocated.sum());
        assertEquals(Set.of("fixture/Account", "fixture/Savings"), t.instrumentedNames());
        assertNotNull(List.of(a, b, s)); // keep them reachable until here
    }

    @Test
    void disabledTrackerCountsNothing() throws Exception {
        ClassLoader l = new InstrumentingLoader(transformer(false, "fixture.Account"));
        Tracker.enabled = false;
        account(l, "alice", 10);
        assertEquals(0, stats("fixture.Account").allocated.sum());
    }

    @Test
    void leavesNonTargetsAndDeactivatedTransformerAlone() throws IOException {
        Transformer t = transformer(false, "fixture.Savings");
        ClassLoader l = new InstrumentingLoader(t);
        assertNull(t.transform(l, "fixture/Account", null, null, bytes("fixture/Account")));
        assertNotNull(t.transform(l, "fixture/Savings", null, null, bytes("fixture/Savings")));

        t.deactivate();
        assertNull(t.transform(l, "fixture/Savings", null, null, bytes("fixture/Savings")),
                "returning null restores the original class on retransform");
    }

    @Test
    void fieldEventsCarryValuesAndSetEventsCarryTheWritersStack() throws Exception {
        Path events = tmp.resolve("events.jsonl");
        Events.start(events, "*");
        Tracker.initHooks = true;
        ClassLoader l = new InstrumentingLoader(transformer(true, "fixture.Account"));

        Object a = account(l, "alice", 10);
        a.getClass().getMethod("deposit", long.class).invoke(a, 5L);
        Events.stop();

        List<JsonObject> ev = readJsonl(events);
        assertEquals("start", ev.get(0).get("ev").getAsString());
        assertEquals("end", ev.get(ev.size() - 1).get("ev").getAsString());

        JsonObject created = only(ev, "new");
        assertEquals("fixture.Account", created.get("class").getAsString());
        assertEquals(10, created.getAsJsonObject("f").get("balance").getAsLong());
        assertEquals("alice", created.getAsJsonObject("f").get("owner").getAsString());
        assertFalse(created.has("at"), "new events have a stack only with stacks=true");

        JsonObject set = only(ev, "set");
        assertEquals(created.get("id"), set.get("id"));
        assertEquals("balance", set.get("field").getAsString());
        assertEquals(15, set.get("v").getAsLong());
        JsonArray at = set.getAsJsonArray("at");
        assertTrue(at.get(0).getAsString().startsWith("fixture.Account.deposit:"), at.toString());
        for (JsonElement frame : at) assertFalse(frame.getAsString().startsWith("otrack."), at.toString());
    }

    @Test
    void namedFieldsOnly() throws Exception {
        Path events = tmp.resolve("events.jsonl");
        Events.start(events, "balance");
        Tracker.initHooks = true;
        ClassLoader l = new InstrumentingLoader(transformer(true, Set.of("balance"), "fixture.Account"));

        account(l, "alice", 10);
        Events.stop();

        JsonObject f = only(readJsonl(events), "new").getAsJsonObject("f");
        assertEquals(Set.of("balance"), f.keySet());
    }

    @Test
    void snapshotIsValidJsonWithPerClassStats() throws Exception {
        ClassLoader l = new InstrumentingLoader(transformer(false, "fixture.Account"));
        Object a = account(l, "alice", 10);

        JsonObject snap = JsonParser.parseString(Reporter.snapshot("test")).getAsJsonObject();
        assertEquals("test", snap.get("reason").getAsString());
        JsonObject account = null;
        for (JsonElement c : snap.getAsJsonArray("classes"))
            if (c.getAsJsonObject().get("class").getAsString().equals("fixture.Account")) account = c.getAsJsonObject();
        assertNotNull(account, snap.toString());
        assertEquals(1, account.get("allocated").getAsLong());
        assertEquals(1, account.get("live").getAsLong());
        assertNotNull(a);
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static Transformer transformer(boolean fieldsOn, String... targets) {
        return transformer(fieldsOn, null, targets);
    }

    private static Transformer transformer(boolean fieldsOn, Set<String> fieldNames, String... targets) {
        Transformer t = new Transformer(fieldsOn, fieldNames);
        for (String s : targets) t.addTarget(Target.parse(s));
        return t;
    }

    private static Object account(ClassLoader l, String owner, long balance) throws Exception {
        return l.loadClass("fixture.Account").getConstructor(String.class, long.class).newInstance(owner, balance);
    }

    private static Tracker.ClassStats stats(String name) {
        return Tracker.snapshotStats().stream().filter(s -> s.name.equals(name)).findFirst().orElseThrow();
    }

    private static byte[] bytes(String internal) throws IOException {
        try (InputStream in = TransformerTest.class.getClassLoader().getResourceAsStream(internal + ".class")) {
            return in.readAllBytes();
        }
    }

    static List<JsonObject> readJsonl(Path p) throws IOException {
        return Files.readAllLines(p).stream().map(line -> JsonParser.parseString(line).getAsJsonObject()).toList();
    }

    private static JsonObject only(List<JsonObject> events, String ev) {
        List<JsonObject> hits = events.stream().filter(e -> e.get("ev").getAsString().equals(ev)).toList();
        assertEquals(1, hits.size(), events.toString());
        return hits.get(0);
    }

    /** Defines the fixture classes itself, from bytes passed through the transformer; delegates the rest. */
    private static final class InstrumentingLoader extends ClassLoader {
        private final Transformer transformer;

        InstrumentingLoader(Transformer transformer) {
            super(TransformerTest.class.getClassLoader());
            this.transformer = transformer;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith("fixture.")) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c != null) return c;
                String internal = name.replace('.', '/');
                try {
                    byte[] original = bytes(internal);
                    byte[] out = transformer.transform(this, internal, null, null, original);
                    byte[] b = out != null ? out : original;
                    return defineClass(name, b, 0, b.length);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
    }
}
