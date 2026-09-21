package otrack;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Minimal parser for a flat JSON object whose values are strings (or bare true/false, read as YES/NO). */
final class Json {
    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    static Map<String, String> flatStringMap(String text) throws IOException {
        return new Json(text).object();
    }

    private Map<String, String> object() throws IOException {
        Map<String, String> m = new LinkedHashMap<>();
        ws();
        expect('{');
        ws();
        if (peek() == '}') return m;
        while (true) {
            ws();
            String k = string();
            ws();
            expect(':');
            ws();
            m.put(k, peek() == '"' ? string() : bare());
            ws();
            if (peek() == ',') {
                i++;
            } else {
                expect('}');
                return m;
            }
        }
    }

    private String string() throws IOException {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') {
                b.append(c);
                continue;
            }
            if (i >= s.length()) break;
            char e = s.charAt(i++);
            switch (e) {
                case 'n' -> b.append('\n');
                case 't' -> b.append('\t');
                case 'r' -> b.append('\r');
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'u' -> {
                    if (i + 4 > s.length()) throw err("bad \\u escape");
                    b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                }
                default -> b.append(e); // \" \\ \/
            }
        }
        throw err("unterminated string");
    }

    private String bare() throws IOException {
        int st = i;
        while (i < s.length() && ",}".indexOf(s.charAt(i)) < 0 && !Character.isWhitespace(s.charAt(i))) i++;
        String t = s.substring(st, i);
        return switch (t) {
            case "true" -> "YES";
            case "false" -> "NO";
            default -> throw err("value must be a string, got " + t);
        };
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private char peek() {
        return i < s.length() ? s.charAt(i) : '\0';
    }

    private void expect(char c) throws IOException {
        if (peek() != c) throw err("expected '" + c + "'");
        i++;
    }

    private IOException err(String m) {
        return new IOException("invalid JSON at offset " + i + ": " + m);
    }
}
