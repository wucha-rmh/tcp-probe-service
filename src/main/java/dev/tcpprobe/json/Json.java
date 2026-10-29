package dev.tcpprobe.json;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal strict JSON codec (RFC 8259) used for both request parsing and response rendering.
 *
 * <p>Design notes:</p>
 * <ul>
 *   <li>The parser is a recursive-descent scanner with an explicit depth limit and precise
 *       offsets in error messages, which makes 400 responses actionable for API clients.</li>
 *   <li>Rendering accepts plain Java objects ({@code Map}, {@code Iterable}, {@code String},
 *       {@code Number}, {@code Boolean}, {@code null}) so handlers can build payloads directly
 *       without a mapping library.</li>
 * </ul>
 */
public final class Json {

    private static final int MAX_DEPTH = 64;

    private Json() {
    }

    // ------------------------------------------------------------------ parsing

    public static JsonValue parse(String text) {
        if (text == null) {
            throw new JsonException("body is empty");
        }
        Parser p = new Parser(text);
        p.skipWhitespace();
        if (p.eof()) {
            throw new JsonException("body is empty");
        }
        JsonValue v = p.value(0);
        p.skipWhitespace();
        if (!p.eof()) {
            throw new JsonException("unexpected trailing content at offset " + p.pos);
        }
        return v;
    }

    private static final class Parser {
        private final String src;
        private final int len;
        private int pos;

        Parser(String src) {
            this.src = src;
            this.len = src.length();
        }

        boolean eof() {
            return pos >= len;
        }

        void skipWhitespace() {
            while (pos < len) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        JsonValue value(int depth) {
            if (depth > MAX_DEPTH) {
                throw new JsonException("document nested deeper than " + MAX_DEPTH + " levels");
            }
            if (eof()) {
                throw new JsonException("unexpected end of input");
            }
            char c = src.charAt(pos);
            return switch (c) {
                case '{' -> object(depth);
                case '[' -> array(depth);
                case '"' -> new JsonValue.Str(string());
                case 't' -> literal("true", JsonValue.Bool.TRUE);
                case 'f' -> literal("false", JsonValue.Bool.FALSE);
                case 'n' -> literal("null", JsonValue.Null.INSTANCE);
                default -> number();
            };
        }

        private JsonValue literal(String word, JsonValue v) {
            if (!src.startsWith(word, pos)) {
                throw new JsonException("unexpected token at offset " + pos);
            }
            pos += word.length();
            return v;
        }

        private JsonValue object(int depth) {
            expect('{');
            Map<String, JsonValue> values = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return new JsonValue.Obj(values);
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw new JsonException("expected object key at offset " + pos);
                }
                String key = string();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                JsonValue v = value(depth + 1);
                if (values.putIfAbsent(key, v) != null) {
                    throw new JsonException("duplicate object key \"" + key + "\"");
                }
                skipWhitespace();
                char c = next();
                if (c == '}') {
                    return new JsonValue.Obj(values);
                }
                if (c != ',') {
                    throw new JsonException("expected ',' or '}' at offset " + (pos - 1));
                }
            }
        }

        private JsonValue array(int depth) {
            expect('[');
            List<JsonValue> values = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return new JsonValue.Arr(values);
            }
            while (true) {
                skipWhitespace();
                values.add(value(depth + 1));
                skipWhitespace();
                char c = next();
                if (c == ']') {
                    return new JsonValue.Arr(values);
                }
                if (c != ',') {
                    throw new JsonException("expected ',' or ']' at offset " + (pos - 1));
                }
            }
        }

        private String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (eof()) {
                    throw new JsonException("unterminated string starting at offset " + pos);
                }
                char c = src.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    if (c < 0x20) {
                        throw new JsonException("unescaped control character 0x"
                                + Integer.toHexString(c) + " in string");
                    }
                    sb.append(c);
                    continue;
                }
                if (eof()) {
                    throw new JsonException("unterminated escape sequence");
                }
                char e = src.charAt(pos++);
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 > len) {
                            throw new JsonException("truncated \\u escape at offset " + pos);
                        }
                        String hex = src.substring(pos, pos + 4);
                        try {
                            sb.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException ex) {
                            throw new JsonException("invalid \\u escape \"" + hex + "\" at offset " + pos);
                        }
                        pos += 4;
                    }
                    default -> throw new JsonException("invalid escape '\\" + e + "' at offset " + (pos - 1));
                }
            }
        }

        private JsonValue number() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            if (eof()) {
                throw new JsonException("truncated number at offset " + start);
            }
            if (peek() == '0') {
                pos++;
            } else if (isDigit(peek())) {
                while (!eof() && isDigit(peek())) {
                    pos++;
                }
            } else {
                throw new JsonException("unexpected token '" + src.charAt(start) + "' at offset " + start);
            }
            if (!eof() && peek() == '.') {
                pos++;
                if (eof() || !isDigit(peek())) {
                    throw new JsonException("digit expected after decimal point at offset " + pos);
                }
                while (!eof() && isDigit(peek())) {
                    pos++;
                }
            }
            if (!eof() && (peek() == 'e' || peek() == 'E')) {
                pos++;
                if (!eof() && (peek() == '+' || peek() == '-')) {
                    pos++;
                }
                if (eof() || !isDigit(peek())) {
                    throw new JsonException("digit expected in exponent at offset " + pos);
                }
                while (!eof() && isDigit(peek())) {
                    pos++;
                }
            }
            return new JsonValue.Num(src.substring(start, pos));
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }

        private char peek() {
            return pos < len ? src.charAt(pos) : '\0';
        }

        private char next() {
            if (eof()) {
                throw new JsonException("unexpected end of input");
            }
            return src.charAt(pos++);
        }

        private void expect(char c) {
            if (eof() || src.charAt(pos) != c) {
                throw new JsonException("expected '" + c + "' at offset " + pos);
            }
            pos++;
        }
    }

    // ------------------------------------------------------------------ rendering

    /** Renders any supported Java value as a JSON string. */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder(256);
        writeValue(sb, value);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object v) {
        if (v == null || v instanceof JsonValue.Null) {
            sb.append("null");
        } else if (v instanceof JsonValue jv) {
            writeJsonValue(sb, jv);
        } else if (v instanceof String s) {
            writeString(sb, s);
        } else if (v instanceof Character c) {
            writeString(sb, String.valueOf(c));
        } else if (v instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (v instanceof Number n) {
            writeNumber(sb, n);
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeValue(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object o : it) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeValue(sb, o);
            }
            sb.append(']');
        } else if (v instanceof Object[] arr) {
            sb.append('[');
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                writeValue(sb, arr[i]);
            }
            sb.append(']');
        } else {
            writeString(sb, v.toString());
        }
    }

    private static void writeJsonValue(StringBuilder sb, JsonValue v) {
        switch (v) {
            case JsonValue.Obj o -> {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<String, JsonValue> e : o.values().entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    writeString(sb, e.getKey());
                    sb.append(':');
                    writeJsonValue(sb, e.getValue());
                }
                sb.append('}');
            }
            case JsonValue.Arr a -> {
                sb.append('[');
                for (int i = 0; i < a.values().size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    writeJsonValue(sb, a.values().get(i));
                }
                sb.append(']');
            }
            case JsonValue.Str s -> writeString(sb, s.value());
            case JsonValue.Num n -> sb.append(n.raw());
            case JsonValue.Bool b -> sb.append(b.value() ? "true" : "false");
            case JsonValue.Null ignored -> sb.append("null");
        }
    }

    private static void writeNumber(StringBuilder sb, Number n) {
        if (n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte) {
            sb.append(n.longValue());
            return;
        }
        if (n instanceof BigDecimal bd) {
            sb.append(bd.toPlainString());
            return;
        }
        double d = n.doubleValue();
        if (!Double.isFinite(d)) {
            // JSON has no NaN/Infinity representation; null keeps the document valid.
            sb.append("null");
            return;
        }
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            sb.append((long) d);
            return;
        }
        sb.append(Double.toString(d));
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------------ helpers

    /** Builds an ordered map from alternating key/value pairs; handy for response payloads. */
    public static Map<String, Object> map(Object... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("map() requires an even number of arguments");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    public static String describe(JsonValue v) {
        return switch (v) {
            case JsonValue.Obj ignored -> "object";
            case JsonValue.Arr ignored -> "array";
            case JsonValue.Str ignored -> "string";
            case JsonValue.Num n -> "number(" + n.raw() + ")";
            case JsonValue.Bool b -> "boolean(" + b.value() + ")";
            case JsonValue.Null ignored -> "null";
        };
    }
}
