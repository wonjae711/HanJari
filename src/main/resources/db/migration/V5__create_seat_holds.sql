-- HJ-006: 좌석 임시 확보(holds), 확보 좌석(hold_items), inventory의 확보 참조.
-- 참조는 FK로 보호하고 cascade 삭제하지 않는다. 초기 데이터는 넣지 않는다.
-- 시각은 UTC DATETIME(6). 판정 시각은 서비스가 잠금을 얻은 뒤 DB에서 읽는다.

-- 확보: ACTIVE → RELEASED / EXPIRED / CONSUMED. expires_at = min(created_at + 5분, 회차 시작)이며 연장하지 않는다.
-- 회원당 활성 확보 1건은 회원 행 잠금 아래 서비스가 보장한다(만료 지난 ACTIVE 행은 같은 트랜잭션에서 EXPIRED로 바꾼 뒤 새로 만든다).
CREATE TABLE holds (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    member_id    BIGINT      NOT NULL,
    screening_id BIGINT      NOT NULL,
    status       VARCHAR(10) NOT NULL,
    total_amount BIGINT      NOT NULL,
    expires_at   DATETIME(6) NOT NULL,
    created_at   DATETIME(6) NOT NULL,
    -- 종결(해제·만료·소비)을 반영한 시각. ACTIVE일 때만 NULL이다.
    ended_at     DATETIME(6) NULL,
    PRIMARY KEY (id),
    -- inventory·확보 좌석이 (확보, 회차) 조합을 참조해 다른 회차의 확보를 가리키지 못하게 한다.
    CONSTRAINT uk_holds_id_screening UNIQUE (id, screening_id),
    CONSTRAINT fk_holds_member FOREIGN KEY (member_id) REFERENCES members (id),
    CONSTRAINT fk_holds_screening FOREIGN KEY (screening_id) REFERENCES screenings (id),
    CONSTRAINT ck_holds_status CHECK (status IN ('ACTIVE', 'RELEASED', 'EXPIRED', 'CONSUMED')),
    -- 1~4석 × 회차 가격 상한 1,000,000원.
    CONSTRAINT ck_holds_total_amount CHECK (total_amount BETWEEN 0 AND 4000000),
    -- 확보 시간은 생성 후 최대 5분이다(회차 시작이 더 이르면 그 시각).
    CONSTRAINT ck_holds_expiry_window CHECK (expires_at > created_at
        AND expires_at <= DATE_ADD(created_at, INTERVAL 5 MINUTE)),
    CONSTRAINT ck_holds_ended CHECK ((status = 'ACTIVE' AND ended_at IS NULL)
        OR (status <> 'ACTIVE' AND ended_at IS NOT NULL)),
    INDEX ix_holds_member_status_expires (member_id, status, expires_at),
    INDEX ix_holds_status_expires (status, expires_at),
    INDEX ix_holds_screening (screening_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 확보 좌석과 확보 시점 단가. (회차, 좌석)은 그 회차 inventory에 있어야 하고, 확보도 같은 회차여야 한다.
CREATE TABLE hold_items (
    hold_id      BIGINT NOT NULL,
    screening_id BIGINT NOT NULL,
    seat_id      BIGINT NOT NULL,
    unit_price   BIGINT NOT NULL,
    PRIMARY KEY (hold_id, seat_id),
    CONSTRAINT fk_hold_items_hold FOREIGN KEY (hold_id, screening_id) REFERENCES holds (id, screening_id),
    CONSTRAINT fk_hold_items_inventory FOREIGN KEY (screening_id, seat_id)
        REFERENCES screening_seats (screening_id, seat_id),
    CONSTRAINT ck_hold_items_unit_price CHECK (unit_price BETWEEN 0 AND 1000000)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- inventory의 현재 소유 확보. 좌석 행이 하나뿐이라 한 좌석의 소유 확보는 최대 하나다.
-- HELD ⇔ hold_id 있음. 예매 참조(reservation_id)는 예매 기능에서 추가한다.
-- 해제는 "현재 hold_id가 대상과 같을 때만" 수행해 오래된 작업이 새 소유자를 지우지 못하게 한다.
-- 이 마이그레이션 이전에는 확보를 만드는 기능이 없어 소유 확보 없는 HELD 행은 유효하지 않다(fixture 잔여물뿐).
-- 아래 CHECK가 기존 행에도 적용되므로 그런 행은 먼저 AVAILABLE로 되돌린다.
UPDATE screening_seats SET state = 'AVAILABLE' WHERE state = 'HELD';

ALTER TABLE screening_seats
    ADD COLUMN hold_id BIGINT NULL,
    ADD CONSTRAINT fk_screening_seats_hold FOREIGN KEY (hold_id, screening_id) REFERENCES holds (id, screening_id),
    ADD CONSTRAINT ck_screening_seats_hold_ref CHECK ((state = 'HELD' AND hold_id IS NOT NULL)
        OR (state <> 'HELD' AND hold_id IS NULL));
