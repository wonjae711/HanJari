-- HJ-005: 변경 요청 멱등 기록. 범위 = 회원 + operation + 키.
-- 도메인 변경과 같은 트랜잭션에서 먼저 행을 선점(INSERT)하고, 성공 응답을 저장한 뒤 함께 커밋한다.
-- 실패하면 선점 행도 함께 롤백되므로 커밋된 행은 항상 완료 상태다(상태·응답이 모두 있음).
-- 저장 대상은 정규화한 요청 해시와 2xx 응답(상태·본문 원문)뿐이다. 인증 헤더·쿠키·세션·비밀번호·원시 요청 본문을 담는 열은 없다.
-- 자동 삭제는 후속 범위다. 참조는 FK로 보호하고 cascade 삭제하지 않는다.
CREATE TABLE idempotency_records (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    member_id       BIGINT      NOT NULL,
    operation       VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key CHAR(36)    CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash    CHAR(64)    CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    http_status     SMALLINT    NULL,
    -- 응답 원문을 바이트 그대로 재전송하기 위해 JSON 타입(키 재정렬·공백 정규화)이 아니라 TEXT로 저장한다.
    response_body   TEXT        CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    created_at      DATETIME(6) NOT NULL,
    completed_at    DATETIME(6) NULL,
    PRIMARY KEY (id),
    -- 동시 동일 키 요청은 이 고유 제약으로 하나만 선점한다.
    CONSTRAINT uk_idempotency_scope UNIQUE (member_id, operation, idempotency_key),
    CONSTRAINT fk_idempotency_member FOREIGN KEY (member_id) REFERENCES members (id),
    CONSTRAINT ck_idempotency_operation CHECK (operation IN
        ('HOLD_CREATE', 'HOLD_RELEASE', 'PAYMENT_ATTEMPT_CREATE', 'RESERVATION_CANCEL')),
    -- 키는 소문자 정규형 UUID(8-4-4-4-12)만 저장한다.
    CONSTRAINT ck_idempotency_key_format CHECK (idempotency_key REGEXP
        '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
    CONSTRAINT ck_idempotency_request_hash CHECK (request_hash REGEXP '^[0-9a-f]{64}$'),
    -- 종결 응답은 2xx만 저장한다. 오류(4xx)·일시 장애(5xx)는 롤백되어 같은 키로 재실행된다.
    CONSTRAINT ck_idempotency_http_status CHECK (http_status IS NULL OR http_status BETWEEN 200 AND 299),
    CONSTRAINT ck_idempotency_completion CHECK (
        (http_status IS NULL AND response_body IS NULL AND completed_at IS NULL)
        OR (http_status IS NOT NULL AND response_body IS NOT NULL AND completed_at IS NOT NULL)),
    CONSTRAINT ck_idempotency_response_json CHECK (response_body IS NULL OR JSON_VALID(response_body))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
