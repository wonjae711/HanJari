-- HJ-004: 상영관·좌석·회차·회차별 좌석 inventory와 운영 수정 충돌 검출용 version.
-- 참조는 FK로 보호하고 cascade 삭제하지 않는다. 초기 데이터(계정 포함)는 넣지 않는다.

-- 기존 movies 열(id,title,runtime_minutes,age_rating)과 상세 API 계약은 그대로 두고, 운영 수정의 낙관적 충돌 검출용 version만 추가한다.
ALTER TABLE movies ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE theaters (
    id   BIGINT      NOT NULL AUTO_INCREMENT,
    name VARCHAR(50) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_theaters_name UNIQUE (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE seats (
    id          BIGINT     NOT NULL AUTO_INCREMENT,
    theater_id  BIGINT     NOT NULL,
    row_label   VARCHAR(2) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    seat_number INT        NOT NULL,
    PRIMARY KEY (id),
    -- 같은 상영관 안에서 좌석 위치는 하나뿐이다.
    CONSTRAINT uk_seats_position UNIQUE (theater_id, row_label, seat_number),
    -- inventory가 (좌석, 상영관) 조합을 참조해 회차 상영관 밖의 좌석을 DB 수준에서 막는다.
    CONSTRAINT uk_seats_id_theater UNIQUE (id, theater_id),
    CONSTRAINT fk_seats_theater FOREIGN KEY (theater_id) REFERENCES theaters (id),
    -- utf8mb4_bin이라 대소문자를 구분한다(소문자 행 이름 거부).
    CONSTRAINT ck_seats_row_label CHECK (row_label REGEXP '^[A-Z]{1,2}$'),
    CONSTRAINT ck_seats_number CHECK (seat_number BETWEEN 1 AND 999)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 시각은 UTC DATETIME(6). 종료 시각은 생성·시작 변경 시점의 영화 상영시간으로 계산해 저장하며, 영화 수정으로 재계산하지 않는다.
-- 같은 상영관의 [시작, 종료+10분) 중첩은 상영관 행 잠금 아래 서비스가 검사한다.
CREATE TABLE screenings (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    movie_id        BIGINT      NOT NULL,
    theater_id      BIGINT      NOT NULL,
    starts_at       DATETIME(6) NOT NULL,
    ends_at         DATETIME(6) NOT NULL,
    base_price      BIGINT      NOT NULL,
    sales_locked_at DATETIME(6) NULL,
    version         BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_screenings_id_theater UNIQUE (id, theater_id),
    CONSTRAINT fk_screenings_movie FOREIGN KEY (movie_id) REFERENCES movies (id),
    CONSTRAINT fk_screenings_theater FOREIGN KEY (theater_id) REFERENCES theaters (id),
    CONSTRAINT ck_screenings_time_order CHECK (starts_at < ends_at),
    CONSTRAINT ck_screenings_base_price CHECK (base_price BETWEEN 0 AND 1000000),
    INDEX ix_screenings_theater_starts (theater_id, starts_at),
    INDEX ix_screenings_movie_starts (movie_id, starts_at, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 회차별 좌석 inventory. 회차와 좌석이 같은 상영관이어야 두 복합 FK를 모두 만족한다.
-- 확보·예매 참조 열과 상태·참조 조합 CHECK는 확보 기능(HJ-005)에서 추가한다.
CREATE TABLE screening_seats (
    screening_id BIGINT      NOT NULL,
    theater_id   BIGINT      NOT NULL,
    seat_id      BIGINT      NOT NULL,
    state        VARCHAR(10) NOT NULL DEFAULT 'AVAILABLE',
    PRIMARY KEY (screening_id, seat_id),
    CONSTRAINT fk_screening_seats_screening FOREIGN KEY (screening_id, theater_id)
        REFERENCES screenings (id, theater_id),
    CONSTRAINT fk_screening_seats_seat FOREIGN KEY (seat_id, theater_id) REFERENCES seats (id, theater_id),
    CONSTRAINT ck_screening_seats_state CHECK (state IN ('AVAILABLE', 'HELD', 'BOOKED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
