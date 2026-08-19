import java.util.*;

/**
 * A small, dependency-free JSON reader/writer.
 * Objects  -> LinkedHashMap<String,Object>
 * Arrays   -> ArrayList<Object>
 * Strings  -> String
 * Numbers  -> Double (or Long if it has no fractional/exponent part)
 * true/false -> Boolean
 * null     -> null
 */
public class Json {

    /* ---------------- parsing ---------------- */

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWhitespace();
        Object value = p.parseValue();
        p.skipWhitespace();
        return value;
    }

    private static class Parser {
        private final String s;
        private int i = 0;

        Parser(String s) { this.s = s; }

        void skipWhitespace() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        char peek() {
            if (i >= s.length()) throw new RuntimeException("Unexpected end of JSON input");
            return s.charAt(i);
        }

        Object parseValue() {
            skipWhitespace();
            char c = peek();
            switch (c) {
                case '{': return parseObject();
                case '[': return parseArray();
                case '"': return parseString();
                case 't': expect("true"); return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null"); return null;
                default: return parseNumber();
            }
        }

        void expect(String literal) {
            if (i + literal.length() > s.length() || !s.startsWith(literal, i)) {
                throw new RuntimeException("Expected '" + literal + "' at position " + i);
            }
            i += literal.length();
        }

        Map<String, Object> parseObject() {
            Map<String, Object> obj = new LinkedHashMap<>();
            i++; // {
            skipWhitespace();
            if (peek() == '}') { i++; return obj; }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                if (peek() != ':') throw new RuntimeException("Expected ':' at position " + i);
                i++;
                Object value = parseValue();
                obj.put(key, value);
                skipWhitespace();
                char c = peek();
                if (c == ',') { i++; continue; }
                if (c == '}') { i++; break; }
                throw new RuntimeException("Expected ',' or '}' at position " + i);
            }
            return obj;
        }

        List<Object> parseArray() {
            List<Object> arr = new ArrayList<>();
            i++; // [
            skipWhitespace();
            if (peek() == ']') { i++; return arr; }
            while (true) {
                Object value = parseValue();
                arr.add(value);
                skipWhitespace();
                char c = peek();
                if (c == ',') { i++; continue; }
                if (c == ']') { i++; break; }
                throw new RuntimeException("Expected ',' or ']' at position " + i);
            }
            return arr;
        }

        String parseString() {
            if (peek() != '"') throw new RuntimeException("Expected string at position " + i);
            i++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') break;
                if (c == '\\') {
                    char esc = s.charAt(i++);
                    switch (esc) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            String hex = s.substring(i, i + 4);
                            sb.append((char) Integer.parseInt(hex, 16));
                            i += 4;
                            break;
                        default: throw new RuntimeException("Invalid escape at position " + i);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        Object parseNumber() {
            int start = i;
            if (peek() == '-') i++;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            boolean isDouble = false;
            if (i < s.length() && s.charAt(i) == '.') {
                isDouble = true;
                i++;
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                isDouble = true;
                i++;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            }
            String num = s.substring(start, i);
            if (num.isEmpty() || num.equals("-")) throw new RuntimeException("Invalid number at position " + start);
            return isDouble ? (Object) Double.parseDouble(num) : (Object) Long.parseLong(num);
        }
    }

    /* ---------------- writing ---------------- */

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(value, sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(Object value, StringBuilder sb) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            writeString((String) value, sb);
        } else if (value instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) value).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(e.getKey(), sb);
                sb.append(':');
                writeValue(e.getValue(), sb);
            }
            sb.append('}');
        } else if (value instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object item : (List<Object>) value) {
                if (!first) sb.append(',');
                first = false;
                writeValue(item, sb);
            }
            sb.append(']');
        } else if (value instanceof Boolean) {
            sb.append(value.toString());
        } else if (value instanceof Double) {
            double d = (Double) value;
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                sb.append((long) d);
            } else {
                sb.append(d);
            }
        } else if (value instanceof Number) {
            sb.append(value.toString());
        } else {
            writeString(value.toString(), sb);
        }
    }

    private static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int j = 0; j < s.length(); j++) {
            char c = s.charAt(j);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    /* ---------------- small read helpers ---------------- */

    public static String getString(Map<String, Object> obj, String key, String def) {
        Object v = obj.get(key);
        return v == null ? def : v.toString();
    }

    public static double getDouble(Map<String, Object> obj, String key, double def) {
        Object v = obj.get(key);
        if (v instanceof Number) return ((Number) v).doubleValue();
        return def;
    }

    public static boolean getBoolean(Map<String, Object> obj, String key, boolean def) {
        Object v = obj.get(key);
        if (v instanceof Boolean) return (Boolean) v;
        return def;
    }
}
