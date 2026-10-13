package io.vepr.contracts.signing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight JSON parser and pretty-printer for vector generation and testing,
 * eliminating the need for third-party JSON libraries.
 */
final class Json {

    private Json() {
    }

    public static Object parse(String json) {
        if (json == null) {
            return null;
        }
        return new Parser(json.trim()).parseValue();
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> parseList(String json) {
        Object val = parse(json);
        if (val instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        throw new IllegalArgumentException("Expected JSON array, got: " + (val == null ? "null" : val.getClass()));
    }

    public static String toPrettyJson(Object obj) {
        StringBuilder sb = new StringBuilder();
        formatValue(obj, sb, 0);
        sb.append("\n");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void formatValue(Object obj, StringBuilder sb, int indent) {
        if (obj == null) {
            sb.append("null");
        } else if (obj instanceof String s) {
            sb.append('"').append(escapeString(s)).append('"');
        } else if (obj instanceof Boolean b) {
            sb.append(b);
        } else if (obj instanceof Number n) {
            sb.append(n);
        } else if (obj instanceof Map<?, ?> map) {
            Map<String, Object> m = (Map<String, Object>) map;
            if (m.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append("{\n");
            int i = 0;
            int size = m.size();
            for (Map.Entry<String, Object> entry : m.entrySet()) {
                indent(sb, indent + 2);
                sb.append('"').append(escapeString(entry.getKey())).append("\": ");
                formatValue(entry.getValue(), sb, indent + 2);
                if (++i < size) {
                    sb.append(",");
                }
                sb.append("\n");
            }
            indent(sb, indent);
            sb.append("}");
        } else if (obj instanceof List<?> list) {
            if (list.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append("[\n");
            for (int i = 0; i < list.size(); i++) {
                indent(sb, indent + 2);
                formatValue(list.get(i), sb, indent + 2);
                if (i + 1 < list.size()) {
                    sb.append(",");
                }
                sb.append("\n");
            }
            indent(sb, indent);
            sb.append("]");
        } else {
            throw new IllegalArgumentException("Unsupported JSON type: " + obj.getClass());
        }
    }

    private static void indent(StringBuilder sb, int spaces) {
        sb.append(" ".repeat(Math.max(0, spaces)));
    }

    private static String escapeString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
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
        return sb.toString();
    }

    private static final class Parser {
        private final String src;
        private int pos = 0;

        Parser(String src) {
            this.src = src;
        }

        Object parseValue() {
            skipWhitespace();
            if (pos >= src.length()) {
                throw new IllegalArgumentException("Unexpected EOF");
            }
            char c = src.charAt(pos);
            if (c == '{') {
                return parseObject();
            } else if (c == '[') {
                return parseArray();
            } else if (c == '"') {
                return parseString();
            } else if (c == 't' || c == 'f') {
                return parseBoolean();
            } else if (c == 'n') {
                return parseNull();
            } else if (c == '-' || Character.isDigit(c)) {
                return parseNumber();
            }
            throw new IllegalArgumentException("Unexpected character '" + c + "' at pos " + pos);
        }

        private Map<String, Object> parseObject() {
            match('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (pos < src.length() && src.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                match(':');
                Object val = parseValue();
                map.put(key, val);
                skipWhitespace();
                if (pos < src.length() && src.charAt(pos) == ',') {
                    pos++;
                } else if (pos < src.length() && src.charAt(pos) == '}') {
                    pos++;
                    break;
                } else {
                    throw new IllegalArgumentException("Expected ',' or '}' at pos " + pos);
                }
            }
            return map;
        }

        private List<Object> parseArray() {
            match('[');
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (pos < src.length() && src.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                if (pos < src.length() && src.charAt(pos) == ',') {
                    pos++;
                } else if (pos < src.length() && src.charAt(pos) == ']') {
                    pos++;
                    break;
                } else {
                    throw new IllegalArgumentException("Expected ',' or ']' at pos " + pos);
                }
            }
            return list;
        }

        private String parseString() {
            match('"');
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                } else if (c == '\\') {
                    if (pos >= src.length()) {
                        throw new IllegalArgumentException("Unterminated escape");
                    }
                    char esc = src.charAt(pos++);
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > src.length()) {
                                throw new IllegalArgumentException("Unterminated \\u escape");
                            }
                            String hex = src.substring(pos, pos + 4);
                            pos += 4;
                            sb.append((char) Integer.parseInt(hex, 16));
                        }
                        default -> throw new IllegalArgumentException("Unknown escape \\" + esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("Unterminated string");
        }

        private Boolean parseBoolean() {
            if (src.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            } else if (src.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("Expected boolean at pos " + pos);
        }

        private Object parseNull() {
            if (src.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new IllegalArgumentException("Expected null at pos " + pos);
        }

        private Number parseNumber() {
            int start = pos;
            if (src.charAt(pos) == '-') {
                pos++;
            }
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
                pos++;
            }
            boolean isFloating = false;
            if (pos < src.length() && src.charAt(pos) == '.') {
                isFloating = true;
                pos++;
                while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
                    pos++;
                }
            }
            if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
                isFloating = true;
                pos++;
                if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) {
                    pos++;
                }
                while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
                    pos++;
                }
            }
            String numStr = src.substring(start, pos);
            if (isFloating) {
                return Double.parseDouble(numStr);
            }
            return Long.parseLong(numStr);
        }

        private void skipWhitespace() {
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        private void match(char expected) {
            skipWhitespace();
            if (pos >= src.length() || src.charAt(pos) != expected) {
                throw new IllegalArgumentException("Expected '" + expected + "' at pos " + pos);
            }
            pos++;
        }
    }
}
