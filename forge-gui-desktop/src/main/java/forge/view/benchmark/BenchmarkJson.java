/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2026  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package forge.view.benchmark;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class BenchmarkJson {
    private BenchmarkJson() {
    }

    static String toJson(final Object value) {
        final StringBuilder out = new StringBuilder();
        append(out, value);
        return out.toString();
    }

    private static void append(final StringBuilder out, final Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String || value instanceof Enum<?>) {
            appendString(out, value.toString());
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                appendString(out, String.valueOf(entry.getKey()));
                out.append(':');
                append(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof Iterable<?> iterable) {
            out.append('[');
            boolean first = true;
            for (Object element : iterable) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                append(out, element);
            }
            out.append(']');
        } else {
            appendString(out, value.toString());
        }
    }

    private static void appendString(final StringBuilder out, final String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    static Map<String, String> parseFlatObject(final String json) {
        final Map<String, String> values = new LinkedHashMap<>();
        int index = skipWhitespace(json, 0);
        if (index >= json.length() || json.charAt(index++) != '{') {
            throw new IllegalArgumentException("Expected a JSON object");
        }
        while (true) {
            index = skipWhitespace(json, index);
            if (index < json.length() && json.charAt(index) == '}') {
                return values;
            }
            final ParsedString key = readString(json, index);
            index = skipWhitespace(json, key.nextIndex);
            if (index >= json.length() || json.charAt(index++) != ':') {
                throw new IllegalArgumentException("Expected ':' after JSON key");
            }
            index = skipWhitespace(json, index);
            final String value;
            if (index < json.length() && json.charAt(index) == '"') {
                final ParsedString parsed = readString(json, index);
                value = parsed.value;
                index = parsed.nextIndex;
            } else {
                final int start = index;
                while (index < json.length() && json.charAt(index) != ',' && json.charAt(index) != '}') {
                    index++;
                }
                value = json.substring(start, index).trim();
            }
            values.put(key.value, "null".equals(value) ? null : value);
            index = skipWhitespace(json, index);
            if (index < json.length() && json.charAt(index) == ',') {
                index++;
            } else if (index < json.length() && json.charAt(index) == '}') {
                return values;
            } else {
                throw new IllegalArgumentException("Expected ',' or '}' in JSON object");
            }
        }
    }

    private static int skipWhitespace(final String value, int index) {
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
            index++;
        }
        return index;
    }

    private static ParsedString readString(final String json, int index) {
        if (index >= json.length() || json.charAt(index++) != '"') {
            throw new IllegalArgumentException("Expected JSON string");
        }
        final StringBuilder out = new StringBuilder();
        while (index < json.length()) {
            final char c = json.charAt(index++);
            if (c == '"') {
                return new ParsedString(out.toString(), index);
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (index >= json.length()) {
                throw new IllegalArgumentException("Incomplete JSON escape");
            }
            final char escaped = json.charAt(index++);
            switch (escaped) {
                case '"', '\\', '/' -> out.append(escaped);
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    if (index + 4 > json.length()) {
                        throw new IllegalArgumentException("Incomplete JSON unicode escape");
                    }
                    out.append((char) Integer.parseInt(json.substring(index, index + 4), 16));
                    index += 4;
                }
                default -> throw new IllegalArgumentException("Unsupported JSON escape: " + escaped);
            }
        }
        throw new IllegalArgumentException("Unterminated JSON string");
    }

    static List<Map<String, Object>> mapList(final Iterable<? extends JsonMappable> values) {
        final List<Map<String, Object>> result = new ArrayList<>();
        for (JsonMappable value : values) {
            result.add(value.toJsonMap());
        }
        return result;
    }

    interface JsonMappable {
        Map<String, Object> toJsonMap();
    }

    private record ParsedString(String value, int nextIndex) {
    }
}
