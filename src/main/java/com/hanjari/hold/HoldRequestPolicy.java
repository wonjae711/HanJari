package com.hanjari.hold;

import com.hanjari.common.RequestValues;
import com.hanjari.idempotency.IdempotentRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code POST /api/v1/holds} 본문 {screeningId, seatIds} 해석. 정규화 없이 엄격하게 받는다.
 *
 * <ul>
 *   <li>본문 없음·객체 아님 → 400 INVALID_REQUEST, 선언하지 않은 필드 → 400 UNSUPPORTED_FIELD.</li>
 *   <li>screeningId: JSON 양의 정수 → 아니면 400 INVALID_SCREENING_ID.</li>
 *   <li>seatIds: JSON 배열, 원소는 JSON 양의 정수 → 아니면 400 INVALID_SEAT_IDS.</li>
 *   <li>좌석 수 1~4 → 아니면 400 INVALID_SEAT_COUNT(0석·5석 이상).</li>
 *   <li>중복 좌석 ID → 400 DUPLICATE_SEAT_ID(멱등 지문 계약 {@link IdempotentRequest#holdCreate}이 판정).</li>
 * </ul>
 * 다른 회차·없는 좌석·점유 좌석은 DB 상태가 필요하므로 서비스가 잠금 아래에서 판정한다.
 */
public final class HoldRequestPolicy {

    public static final String INVALID_SEAT_IDS = "INVALID_SEAT_IDS";
    public static final String INVALID_SEAT_COUNT = "INVALID_SEAT_COUNT";

    private static final Set<String> FIELDS = Set.of("screeningId", "seatIds");

    private HoldRequestPolicy() {
    }

    /** 검증한 확보 요청. seatIds는 요청 순서 그대로이며 중복은 지문 단계에서 거부된다. */
    public record HoldCreate(long screeningId, List<Long> seatIds) {
        public HoldCreate {
            seatIds = List.copyOf(seatIds);
        }

        /** 멱등 지문(좌석 순서 무시, 중복 → 400 DUPLICATE_SEAT_ID). */
        public IdempotentRequest fingerprint() {
            return IdempotentRequest.holdCreate(screeningId, seatIds);
        }

        public List<Long> sortedSeatIds() {
            return seatIds.stream().sorted().toList();
        }
    }

    public static HoldCreate holdCreate(Map<String, Object> body) {
        RequestValues.requireObject(body, FIELDS);
        long screeningId = RequestValues.jsonInteger(body.get("screeningId"), 1, Long.MAX_VALUE,
                "INVALID_SCREENING_ID", "유효하지 않은 회차 ID입니다.");
        if (!(body.get("seatIds") instanceof List<?> raw)) {
            throw RequestValues.badRequest(INVALID_SEAT_IDS, "좌석 ID 목록이 필요합니다.");
        }
        List<Long> seatIds = new ArrayList<>(raw.size());
        for (Object value : raw) {
            seatIds.add(RequestValues.jsonInteger(value, 1, Long.MAX_VALUE, INVALID_SEAT_IDS,
                    "좌석 ID가 올바르지 않습니다."));
        }
        if (!HoldPolicy.validSeatCount(seatIds.size())) {
            throw RequestValues.badRequest(INVALID_SEAT_COUNT, "좌석은 한 번에 1~4석까지 확보할 수 있습니다.");
        }
        HoldCreate request = new HoldCreate(screeningId, seatIds);
        request.fingerprint(); // 중복 좌석 ID를 여기서 거부한다.
        return request;
    }
}
