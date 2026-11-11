package com.hanjari.idempotency;

import java.util.List;
import java.util.Map;
import java.util.SortedMap;

/**
 * 결정적 JSON 직렬화. 멱등 요청 지문과 저장 응답 본문에 쓴다.
 *
 * <p>허용 값: null, String, Boolean, Integer·Long, List, Map(String 키). 객체 키 순서는 호출자가 준 Map의 반복 순서를
 * 그대로 따른다(지문은 {@link SortedMap}을 넘겨 이름 순서로 고정한다). 공백을 넣지 않는다. 실수·그 밖의 타입은
 * 표현이 흔들릴 수 있어 거부한다.
 */
public final class CanonicalJson {

    private CanonicalJson() {
    }

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        append(out, value);
        return out.toString();
    }

    private static void append(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String text) {
            appendString(out, text);
        } else if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
            out.append(value);
        } else if (value instanceof List<?> list) {
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                append(out, list.get(i));
            }
            out.append(']');
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("JSON object keys must be strings");
                }
                if (!first) {
                    out.append(',');
                }
                first = false;
                appendString(out, key);
                out.append(':');
                append(out, entry.getValue());
            }
            out.append('}');
        } else {
            throw new IllegalArgumentException("unsupported JSON value type: " + value.getClass().getName());
        }
    }

    private static void appendString(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
