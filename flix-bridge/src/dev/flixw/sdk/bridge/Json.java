package dev.flixw.sdk.bridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A strict reader for the one JSON file the bridge ships, {@code versions.json}.
 *
 * <p>The bridge has no third-party dependency, because it shares a class path with a compiler. The
 * reader accepts objects, arrays, strings, integers, booleans and null; it rejects duplicate keys,
 * trailing text, comments and every number that is not a plain integer.
 */
final class Json {
    private final String text;
    private int position;

    private Json(String text) {
        this.text = text;
    }

    /** Parses a document into maps, lists, strings, longs, booleans and null. */
    static Object parse(String text) {
        Json reader = new Json(text);
        Object value = reader.value();
        reader.skip();
        if (reader.position != text.length()) throw reader.error("trailing text");
        return value;
    }

    private Object value() {
        skip();
        if (position >= text.length()) throw error("unexpected end");
        char c = text.charAt(position);
        if (c == '{') return object();
        if (c == '[') return array();
        if (c == '"') return string();
        if (text.startsWith("true", position)) { position += 4; return Boolean.TRUE; }
        if (text.startsWith("false", position)) { position += 5; return Boolean.FALSE; }
        if (text.startsWith("null", position)) { position += 4; return null; }
        if (c == '-' || (c >= '0' && c <= '9')) return number();
        throw error("unexpected character '" + c + "'");
    }

    private Map<String, Object> object() {
        Map<String, Object> result = new LinkedHashMap<>();
        position++;
        skip();
        if (peek('}')) { position++; return result; }
        while (true) {
            skip();
            if (!peek('"')) throw error("object key expected");
            String key = string();
            skip();
            expect(':');
            Object value = value();
            if (result.containsKey(key)) throw error("duplicate key " + key);
            result.put(key, value);
            skip();
            if (peek(',')) { position++; continue; }
            expect('}');
            return result;
        }
    }

    private List<Object> array() {
        List<Object> result = new ArrayList<>();
        position++;
        skip();
        if (peek(']')) { position++; return result; }
        while (true) {
            result.add(value());
            skip();
            if (peek(',')) { position++; continue; }
            expect(']');
            return result;
        }
    }

    private String string() {
        StringBuilder out = new StringBuilder();
        position++;
        while (true) {
            if (position >= text.length()) throw error("unterminated string");
            char c = text.charAt(position++);
            if (c == '"') return out.toString();
            if (c < 0x20) throw error("control character in string");
            if (c != '\\') { out.append(c); continue; }
            if (position >= text.length()) throw error("unterminated escape");
            char e = text.charAt(position++);
            switch (e) {
                case '"', '\\', '/' -> out.append(e);
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    if (position + 4 > text.length()) throw error("short unicode escape");
                    try {
                        out.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw error("bad unicode escape");
                    }
                    position += 4;
                }
                default -> throw error("bad escape \\" + e);
            }
        }
    }

    private Long number() {
        int start = position;
        if (peek('-')) position++;
        int digits = position;
        while (position < text.length() && Character.isDigit(text.charAt(position))) position++;
        if (position == digits) throw error("digits expected");
        if (text.charAt(digits) == '0' && position - digits > 1) throw error("leading zero");
        if (position < text.length() && ".eE".indexOf(text.charAt(position)) >= 0)
            throw error("only integers are supported");
        return Long.parseLong(text.substring(start, position));
    }

    private boolean peek(char c) {
        return position < text.length() && text.charAt(position) == c;
    }

    private void expect(char c) {
        if (!peek(c)) throw error("'" + c + "' expected");
        position++;
    }

    private void skip() {
        while (position < text.length() && " \t\r\n".indexOf(text.charAt(position)) >= 0) position++;
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException("versions.json: " + message + " at offset " + position);
    }
}
