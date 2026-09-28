package otrack;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent arguments: {@code key=value} pairs joined by ';'. A bare word is taken as the command, so
 * {@code "stop"} means {@code "cmd=stop"}. See {@link Controller} for the keys.
 */
final class Options {
    private final Map<String, String> values = new HashMap<>();

    private Options() {}

    static Options parse(String raw) {
        Options o = new Options();
        if (raw == null) return o;
        for (String kv : raw.split(";")) {
            int eq = kv.indexOf('=');
            if (eq > 0) o.values.put(kv.substring(0, eq).strip(), kv.substring(eq + 1).strip());
            else if (!kv.isBlank()) o.values.put("cmd", kv.strip());
        }
        return o;
    }

    boolean has(String key) {
        return values.containsKey(key);
    }

    /** The value, or null if the key is absent. */
    String get(String key) {
        return values.get(key);
    }

    String get(String key, String def) {
        return values.getOrDefault(key, def);
    }

    /** The value as an int, raised to {@code min} if smaller. The key must be present. */
    int intAtLeast(String key, int min) {
        return Math.max(min, Integer.parseInt(values.get(key)));
    }

    double decimal(String key, double def) {
        String v = values.get(key);
        return v == null ? def : Double.parseDouble(v);
    }

    /** The comma-separated items of the value, stripped, blanks dropped; empty if the key is absent. */
    List<String> list(String key) {
        List<String> out = new ArrayList<>();
        String v = values.get(key);
        if (v == null) return out;
        for (String item : v.split(",")) if (!item.isBlank()) out.add(item.strip());
        return out;
    }

    /** The value as a path, or {@code $TMP/object-tracker-PID<suffix>} if the key is absent. */
    Path path(String key, String defaultSuffix) {
        return Path.of(get(key, System.getProperty("java.io.tmpdir") + "/object-tracker-" + ProcessHandle.current().pid()
                + defaultSuffix));
    }
}
