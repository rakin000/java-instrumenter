package otrack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TargetTest {
    @TempDir
    Path tmp;

    @Test
    void plusPrefixMeansWithSubtypes() {
        assertEquals(new Target("a.B", false), Target.parse("a.B"));
        assertEquals(new Target("a.B", true), Target.parse(" + a.B "));
        assertEquals(new Target("B", true), Target.parse("+B"));
    }

    @Test
    void refusesBlankJdkAndAgentClasses() {
        assertNull(Target.parse("+"));
        assertNull(Target.parse("java.lang.String"));
        assertNull(Target.parse("+javax.swing.JPanel"));
        assertNull(Target.parse("com.sun.net.httpserver.HttpServer"));
        assertNull(Target.parse("otrack.Tracker"));
        assertEquals(new Target("javafoo.Bar", false), Target.parse("javafoo.Bar"), "only whole package prefixes");
    }

    @Test
    void readsJsonThenClassesThenFile() throws IOException {
        Path json = Files.writeString(tmp.resolve("classes.json"), """
                {"a.A": "YES", "b.B": "no", "c.C": true, "d.D": false, "e.E": 1, "+f.F": " yes "}
                """);
        Path file = Files.writeString(tmp.resolve("classes.txt"), "# comment\n\n  g.G  \n+h.H\n");
        Options o = Options.parse("classesJson=" + json + ";classes=x.X, +y.Y;classesFile=" + file);

        assertEquals(List.of(
                new Target("a.A", false), new Target("c.C", false), new Target("f.F", true),
                new Target("x.X", false), new Target("y.Y", true),
                new Target("g.G", false), new Target("h.H", true)), Target.readAll(o));
    }

    @Test
    void invalidJsonNamesTheFile() throws IOException {
        Path json = Files.writeString(tmp.resolve("broken.json"), "{\"a.A\": ");
        IOException e = assertThrows(IOException.class, () -> Target.readAll(Options.parse("classesJson=" + json)));
        assertTrue(e.getMessage().contains("invalid classesJson " + json), e.getMessage());
    }

    @Test
    void jsonMustBeAnObject() throws IOException {
        Path json = Files.writeString(tmp.resolve("array.json"), "[\"a.A\"]");
        assertThrows(IOException.class, () -> Target.readAll(Options.parse("classesJson=" + json)));
    }
}
