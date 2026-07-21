package geoplanph;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Just enough JSON for the dashboard API: string escaping for output, and a
 * parser for a flat object {@code {"k":"v","n":123,"b":true}} into string values.
 * Nested structures are not needed here, so they aren't supported.
 */
public final class Json {

    private Json() { }

    public static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    /** Parse a flat JSON object; every value is returned as its string form. */
    public static Map<String, String> parseFlatObject(String json) {
        Map<String, String> out = new LinkedHashMap<>();
        if (json == null) return out;
        int i = 0, n = json.length();
        i = skipWs(json, i);
        if (i >= n || json.charAt(i) != '{') return out;
        i++;
        while (true) {
            i = skipWs(json, i);
            if (i >= n) break;
            if (json.charAt(i) == '}') break;
            if (json.charAt(i) != '"') break;
            StringBuilder key = new StringBuilder();
            i = readString(json, i, key);
            i = skipWs(json, i);
            if (i >= n || json.charAt(i) != ':') break;
            i++;
            i = skipWs(json, i);
            StringBuilder val = new StringBuilder();
            if (i < n && json.charAt(i) == '"') {
                i = readString(json, i, val);
            } else {
                while (i < n && json.charAt(i) != ',' && json.charAt(i) != '}') {
                    val.append(json.charAt(i));
                    i++;
                }
            }
            out.put(key.toString(), val.toString().trim());
            i = skipWs(json, i);
            if (i < n && json.charAt(i) == ',') { i++; continue; }
            break;
        }
        return out;
    }

    private static int skipWs(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    /** Reads a JSON string starting at the opening quote; appends content to sb. */
    private static int readString(String s, int i, StringBuilder sb) {
        i++; // opening quote
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') break;
            if (c == '\\' && i < s.length()) {
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'u' -> {
                        if (i + 4 <= s.length()) {
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                    }
                    default -> sb.append(e);
                }
            } else {
                sb.append(c);
            }
        }
        return i;
    }
}
