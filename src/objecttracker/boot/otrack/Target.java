package otrack;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * A class to track, as given by the user: a dotted name ({@code a.B}, or package-less {@code B}) and whether
 * its subclasses / implementors count too ({@code +} prefix).
 */
record Target(String name, boolean withSubtypes) {

    /** Reads the targets from {@code classesJson=}, {@code classes=} and {@code classesFile=}, in that order. */
    static List<Target> readAll(Options o) throws IOException {
        List<String> specs = new ArrayList<>();
        if (o.has("classesJson")) specs.addAll(yesClasses(Path.of(o.get("classesJson"))));
        specs.addAll(o.list("classes"));
        if (o.has("classesFile")) {
            for (String line : Files.readAllLines(Path.of(o.get("classesFile")))) {
                line = line.strip();
                if (!line.isEmpty() && !line.startsWith("#")) specs.add(line);
            }
        }
        List<Target> out = new ArrayList<>();
        for (String spec : specs) {
            Target t = parse(spec);
            if (t != null) out.add(t);
        }
        return out;
    }

    /** Parses {@code a.B} or {@code +a.B}; null for a blank name or a JDK/agent class, which is never tracked. */
    static Target parse(String spec) {
        String s = spec.strip();
        boolean sub = s.startsWith("+");
        String n = sub ? s.substring(1).strip() : s;
        if (n.isEmpty()) return null;
        if (TargetMatcher.isJdk(n.replace('.', '/')) || n.startsWith("otrack.")) {
            Log.log("refusing JDK/agent class " + n);
            return null;
        }
        return new Target(n, sub);
    }

    /**
     * Reads a JSON object of {@code "class": "YES"|"NO"} (or {@code true}/{@code false}) and returns the YES
     * keys.
     */
    private static List<String> yesClasses(Path file) throws IOException {
        JsonObject m;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            m = JsonParser.parseReader(r).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new IOException("invalid classesJson " + file + ": " + e.getMessage(), e);
        }
        List<String> yes = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : m.entrySet()) {
            String v = e.getValue().isJsonPrimitive() ? e.getValue().getAsString().strip() : e.getValue().toString();
            if (v.equalsIgnoreCase("YES") || v.equals("true")) yes.add(e.getKey());
            else if (!v.equalsIgnoreCase("NO") && !v.equals("false"))
                Log.log("classesJson: ignoring '" + e.getKey() + "', value must be YES or NO, got '" + v + "'");
        }
        Log.log("classesJson: " + yes.size() + " YES of " + m.size() + " entries in " + file);
        return yes;
    }
}
