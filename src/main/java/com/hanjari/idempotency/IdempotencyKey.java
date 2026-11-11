package com.hanjari.idempotency;

import com.hanjari.common.RequestValues;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * {@code Idempotency-Key} 헤더 값. UUID 정규 표기(8-4-4-4-12 16진수)만 받는다.
 *
 * <ul>
 *   <li>없음 → 400 IDEMPOTENCY_KEY_REQUIRED.</li>
 *   <li>형식 위반(중괄호·urn 접두·공백·하이픈 없는 표기·길이 오류 등)이나 헤더가 둘 이상 → 400 INVALID_IDEMPOTENCY_KEY.</li>
 *   <li>UUID 16진수는 대소문자를 구분하지 않으므로(RFC 9562) 소문자로 정규화해 저장·비교한다. 그 밖의 정규화(trim 등)는 하지 않는다.</li>
 * </ul>
 * 오류 메시지는 고정 문구이며 입력 값을 담지 않는다.
 */
public record IdempotencyKey(String value) {

    public static final String HEADER = "Idempotency-Key";
    public static final String REQUIRED_CODE = "IDEMPOTENCY_KEY_REQUIRED";
    public static final String INVALID_CODE = "INVALID_IDEMPOTENCY_KEY";

    private static final Pattern UUID_TEXT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern CANONICAL = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    public IdempotencyKey {
        if (value == null || !CANONICAL.matcher(value).matches()) {
            throw new IllegalArgumentException("idempotency key must be a lowercase canonical UUID");
        }
    }

    public static IdempotencyKey from(HttpServletRequest request) {
        return fromHeaderValues(Collections.list(request.getHeaders(HEADER)));
    }

    public static IdempotencyKey fromHeaderValues(List<String> values) {
        if (values == null || values.isEmpty()) {
            throw RequestValues.badRequest(REQUIRED_CODE, "Idempotency-Key 헤더가 필요합니다.");
        }
        if (values.size() != 1 || values.get(0) == null || !UUID_TEXT.matcher(values.get(0)).matches()) {
            throw RequestValues.badRequest(INVALID_CODE, "Idempotency-Key는 UUID 형식이어야 합니다.");
        }
        return new IdempotencyKey(values.get(0).toLowerCase(Locale.ROOT));
    }

    @Override
    public String toString() {
        return "IdempotencyKey[redacted]";
    }
}
