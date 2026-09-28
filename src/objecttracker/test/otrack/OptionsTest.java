package otrack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class OptionsTest {

    @Test
    void parsesKeyValuePairsAndStripsWhitespace() {
        Options o = Options.parse(" classes = a.B,+c.D ; sample=4;out=/tmp/x=y.jsonl");
        assertEquals("a.B,+c.D", o.get("classes"));
        assertEquals("4", o.get("sample"));
        assertEquals("/tmp/x=y.jsonl", o.get("out"), "only the first '=' separates key and value");
        assertFalse(o.has("cmd"));
    }

    @Test
    void bareWordIsTheCommand() {
        assertEquals("stop", Options.parse("stop").get("cmd"));
        assertEquals("start", Options.parse(null).get("cmd", "start"));
    }

    @Test
    void listDropsBlankItems() {
        assertEquals(List.of("a", "+b"), Options.parse("fields= a,, +b ,").list("fields"));
        assertTrue(Options.parse("").list("fields").isEmpty());
    }

    @Test
    void numbers() {
        Options o = Options.parse("sample=0;depth=12;interval=0.5");
        assertEquals(1, o.intAtLeast("sample", 1));
        assertEquals(12, o.intAtLeast("depth", 1));
        assertEquals(0.5, o.decimal("interval", 10));
        assertEquals(10, o.decimal("missing", 10));
        assertNull(o.get("missing"));
    }

    @Test
    void pathDefaultsToTmpWithPid() {
        Path p = Options.parse("").path("out", ".jsonl");
        assertEquals(Path.of(System.getProperty("java.io.tmpdir")), p.getParent());
        assertEquals("object-tracker-" + ProcessHandle.current().pid() + ".jsonl", p.getFileName().toString());
        assertEquals(Path.of("/x/y.jsonl"), Options.parse("out=/x/y.jsonl").path("out", ".jsonl"));
    }
}
