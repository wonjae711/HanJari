package com.hanjari.idempotency;

import com.hanjari.common.RequestValues;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * operation과 정규화한 요청 지문(경로·본문)의 묶음.
 *
 * <p>지문 원문(canonical)은 {@code {"body":{...},"method":"POST","operation":"...","path":"/api/v1/..."}} 형태의
 * {@link CanonicalJson}이며 SHA-256(소문자 16진수)으로 저장한다. 원시 URI·원시 본문·헤더는 쓰지 않는다:
 * <ul>
 *   <li>경로는 해석한 ID로 다시 만든 정규 경로다(퍼센트 인코딩·matrix 변수 등 표기 차이가 지문에 들어가지 않는다).</li>
 *   <li>본문은 선언한 필드만 이름 순서로 담는다. 집합 필드는 오름차순으로 정렬하되, 중복 값은 정규화로 숨기지 않고
 *       입력 오류(400)로 거부한다.</li>
 * </ul>
 * 아래 정적 메서드가 operation별 지문 계약이다. 각 업무(확보·결제·취소)의 컨트롤러는 입력을 검증한 뒤 이 메서드로 지문을 만든다.
 */
public final class IdempotentRequest {

    public static final String DUPLICATE_SEAT_CODE = "DUPLICATE_SEAT_ID";

    private static final Pattern PATH = Pattern.compile("/api/v1(/[a-z0-9-]+)+");
    private static final Pattern FIELD = Pattern.compile("[a-z][A-Za-z0-9]{0,39}");
    private static final Set<String> METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final IdempotencyOperation operation;
    private final String canonical;
    private final String hash;

    private IdempotentRequest(IdempotencyOperation operation, String canonical) {
        this.operation = operation;
        this.canonical = canonical;
        this.hash = sha256(canonical);
    }

    // operation별 지문 계약 -------------------------------------------------------------------------------

    /** POST /api/v1/holds {screeningId, seatIds}. 좌석 순서는 무시하고, 중복 좌석 ID는 400 DUPLICATE_SEAT_ID. */
    public static IdempotentRequest holdCreate(long screeningId, Collection<Long> seatIds) {
        return builder(IdempotencyOperation.HOLD_CREATE, "POST", "/api/v1/holds")
                .number("screeningId", screeningId)
                .distinctIdSet("seatIds", seatIds, DUPLICATE_SEAT_CODE, "같은 좌석을 두 번 선택할 수 없습니다.")
                .build();
    }

    /** POST /api/v1/holds/{holdId}/release (본문 없음). */
    public static IdempotentRequest holdRelease(long holdId) {
        return builder(IdempotencyOperation.HOLD_RELEASE, "POST", "/api/v1/holds/" + positive(holdId) + "/release")
                .build();
    }

    /** POST /api/v1/holds/{holdId}/payment-attempts (본문 없음, 금액은 받지 않는다). */
    public static IdempotentRequest paymentAttemptCreate(long holdId) {
        return builder(IdempotencyOperation.PAYMENT_ATTEMPT_CREATE, "POST",
                "/api/v1/holds/" + positive(holdId) + "/payment-attempts").build();
    }

    /** POST /api/v1/reservations/{reservationId}/cancellations (본문 없음). */
    public static IdempotentRequest reservationCancel(long reservationId) {
        return builder(IdempotencyOperation.RESERVATION_CANCEL, "POST",
                "/api/v1/reservations/" + positive(reservationId) + "/cancellations").build();
    }

    public static Builder builder(IdempotencyOperation operation, String method, String canonicalPath) {
        return new Builder(operation, method, canonicalPath);
    }

    public IdempotencyOperation operation() {
        return operation;
    }

    /** 지문 원문. 테스트·검토용이며 저장하지 않는다(저장은 {@link #hash()}만). */
    public String canonical() {
        return canonical;
    }

    public String hash() {
        return hash;
    }

    @Override
    public String toString() {
        return "IdempotentRequest[operation=" + operation + "]";
    }

    public static final class Builder {
        private final IdempotencyOperation operation;
        private final String method;
        private final String path;
        private final TreeMap<String, Object> body = new TreeMap<>();

        private Builder(IdempotencyOperation operation, String method, String path) {
            this.operation = Objects.requireNonNull(operation, "operation");
            if (method == null || !METHODS.contains(method)) {
                throw new IllegalArgumentException("unsupported method");
            }
            if (path == null || !PATH.matcher(path).matches()) {
                throw new IllegalArgumentException("path must be a canonical /api/v1 path");
            }
            this.method = method;
            this.path = path;
        }

        public Builder number(String field, long value) {
            put(field, value);
            return this;
        }

        /** 정렬한 ID 집합. 중복이 있으면 지정한 코드의 400을 던진다. null 원소는 호출자 오류다. */
        public Builder distinctIdSet(String field, Collection<Long> ids, String duplicateCode, String duplicateMessage) {
            Objects.requireNonNull(ids, "ids");
            TreeSet<Long> sorted = new TreeSet<>();
            for (Long id : ids) {
                if (!sorted.add(Objects.requireNonNull(id, "id"))) {
                    throw RequestValues.badRequest(duplicateCode, duplicateMessage);
                }
            }
            put(field, new ArrayList<>(sorted));
            return this;
        }

        private void put(String field, Object value) {
            if (field == null || !FIELD.matcher(field).matches()) {
                throw new IllegalArgumentException("invalid field name");
            }
            if (body.putIfAbsent(field, value) != null) {
                throw new IllegalArgumentException("field declared twice");
            }
        }

        public IdempotentRequest build() {
            Map<String, Object> root = new TreeMap<>();
            root.put("body", new TreeMap<>(body));
            root.put("method", method);
            root.put("operation", operation.name());
            root.put("path", path);
            return new IdempotentRequest(operation, CanonicalJson.write(root));
        }
    }

    private static long positive(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("id must be positive");
        }
        return id;
    }

    static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
