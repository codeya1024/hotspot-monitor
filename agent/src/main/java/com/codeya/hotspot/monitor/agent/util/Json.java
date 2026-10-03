package com.codeya.hotspot.monitor.agent.util;

import java.util.Iterator;
import java.util.Map;

/**
 * 极简 JSON 序列化器（零依赖，agent 内部使用）。
 * 支持 null / Boolean / Number / String / Map / Iterable / Object[]。
 */
public final class Json {

    private Json() {
    }

    public static String write(Object o) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, o);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object o) {
        if (o == null) {
            sb.append("null");
        } else if (o instanceof Map) {
            writeMap(sb, (Map<?, ?>) o);
        } else if (o instanceof Iterable) {
            writeIterable(sb, (Iterable<?>) o);
        } else if (o instanceof Object[]) {
            writeArray(sb, (Object[]) o);
        } else if (o instanceof Number || o instanceof Boolean) {
            sb.append(o);
        } else {
            sb.append('"').append(escape(o.toString())).append('"');
        }
    }

    private static void writeMap(StringBuilder sb, Map<?, ?> map) {
        sb.append('{');
        Iterator<? extends Map.Entry<?, ?>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<?, ?> e = it.next();
            sb.append('"').append(escape(String.valueOf(e.getKey()))).append("\":");
            writeValue(sb, e.getValue());
            if (it.hasNext()) {
                sb.append(',');
            }
        }
        sb.append('}');
    }

    private static void writeIterable(StringBuilder sb, Iterable<?> it) {
        sb.append('[');
        Iterator<?> i = it.iterator();
        while (i.hasNext()) {
            writeValue(sb, i.next());
            if (i.hasNext()) {
                sb.append(',');
            }
        }
        sb.append(']');
    }

    private static void writeArray(StringBuilder sb, Object[] arr) {
        sb.append('[');
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            writeValue(sb, arr[i]);
        }
        sb.append(']');
    }

    static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
