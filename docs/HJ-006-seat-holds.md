# HJ-006 좌석 원자적 확보·해제·만료

## 범위
로그인 회원이 한 회차의 서로 다른 1~4석을 한 트랜잭션으로 임시 확보하고(5분 또는 상영 시작 중 이른 시각까지), 본인 확보를 조회·해제하며, 만료된 확보를 작업자와 요청 경로가 함께 정리하는 기능을 구현했다. 최초 확보 시 회차 판매 잠금(`sales_locked_at`)을 영구 기록한다.
범위 밖(승인 기획의 제외 항목): Redis, 확보 연장, 복수 활성 확보, 좌석 변경. 결제·예매(CONSUMED 전이, `reservation_id`)는 HJ-007 이후다.

## API (`/api/v1`, 로그인 필요, 변경 요청은 Origin → CSRF → 인증 → `Idempotency-Key`)
| 요청 | 성공 | 주요 실패 |
|---|---|---|
| `POST /holds {screeningId, seatIds}` | 201 `{holdId,status,seatIds,expiresAt,totalAmount,serverNow}` (seatIds 오름차순) | 400 `INVALID_SEAT_COUNT`(0석·5석 이상)/`DUPLICATE_SEAT_ID`/`INVALID_SEAT_IDS`/`INVALID_SCREENING_ID`/`UNSUPPORTED_FIELD`/`SEAT_NOT_IN_SCREENING`(다른 회차·없는 좌석), 404 `SCREENING_NOT_FOUND`, 409 `SEAT_UNAVAILABLE`(점유·예매 좌석 포함)/`ACTIVE_HOLD_EXISTS`(회원당 1건)/`SCREENING_NOT_BOOKABLE`(시작 이후) |
| `POST /holds/{id}/release` | 200 `{holdId,status}` — `RELEASED`, 만료가 지났으면 `EXPIRED`, 이미 종결이면 현재 종결 상태(변경 없음) | 400 `INVALID_HOLD_ID`, 404 `HOLD_NOT_FOUND`(없음·타인 동일), 409 `HOLD_CONSUMED` |
| `GET /holds/current` | 200 본인 유효 확보 또는 204 | 401 |
| `GET /holds/{id}` | 200 `{holdId,screeningId,status,seatIds,expiresAt,totalAmount,serverNow,paymentAttemptId,reservationId}` (결제·예매 참조는 해당 기능 전이라 null) | 400, 404 `HOLD_NOT_FOUND` |
| `GET /screenings/{id}/seats` (공개, 변경) | `mine` = 로그인 요청자의 유효 확보 좌석, 본인 유효 확보가 이 회차에 있을 때만 `expiresAt` 추가 | 기존과 같음 |

- 실패는 전체 실패다: 좌석 하나라도 문제가 있으면 확보·확보 좌석·판매 잠금·멱등 기록 어느 것도 남지 않는다.
- 조회·재전송·새로고침은 확보를 연장하지 않는다. 같은 키 재전송은 HJ-005 계약대로 원래 201 본문(원래 `expiresAt`·`serverNow`)을 그대로 돌려준다.
- 공개 좌석 조회의 HELD는 "소유 확보가 ACTIVE이고 기준 시각 < 만료 시각"일 때만이다. 작업자가 아직 반영하지 않은 만료 좌석은 AVAILABLE로 보인다. 회차 목록 `bookable`도 같은 기준이다.

## 파일 읽는 순서
1. `db/migration/V5__create_seat_holds.sql`: `holds`(상태·시간 창·종결 시각 CHECK, `(id, screening_id)` UNIQUE), `hold_items`(확보·inventory 복합 FK, 단가), `screening_seats.hold_id`(+`(hold_id, screening_id)` 복합 FK, `HELD ⇔ hold_id` CHECK). cascade 없음.
2. `hold/HoldPolicy.java`, `HoldStatus.java`, `HoldRequestPolicy.java`: 순수 정책(만료 = min(생성+5분, 시작), `now >= expiresAt` 만료, `now >= startsAt` 신규 금지, 총액, 입력 규칙).
3. `hold/HoldService.java`: 핵심. 잠금 순서·만료 재판정·조건부 해제.
4. `hold/HoldController.java`: 키 → 본문·지문 → 멱등 실행기(해제는 소유권 검사 선행).
5. `hold/HoldExpiryWorker.java`, `HoldSchedulingConfig.java`: 1초 주기 작업자.
6. `common/DatabaseTime.java`, `JdbcDatabaseTime.java`: 판정 시각(DB `UTC_TIMESTAMP(6)`).
7. `catalog/CatalogQueryService.java`, `CatalogController.java`, `CatalogResponses.java`: 좌석 조회 `mine`/`expiresAt`, 만료 반영 HELD/`bookable`.
8. 테스트: `hold/HjSeatHoldLifecycleTest`(단위 12), `hold/HjSeatHoldConcurrencyIT`(실제 MySQL + 실제 HTTP 14).

## 결정과 근거
- **잠금 순서**: 모든 변경 경로가 회원 → 회차(ID 오름차순) → 확보(ID 오름차순) → 좌석 순서다. 회차 행 배타 잠금이 그 회차의 모든 좌석 변경(확보·해제·만료)을 직렬화하므로 같은 좌석의 유효 소유자는 하나다(좌석 행이 하나뿐이라 DB 구조상으로도 소유 확보는 최대 하나). 회원 행 잠금이 회원당 활성 확보 1건을 보장한다. 운영 작업(상영관 → 회차)은 회원 잠금을 얻지 않아 순환 대기가 없다. 멱등 기록 선점은 HJ-005대로 이 잠금들보다 앞선다.
- **회원 잠금은 멱등 선점보다 먼저**: 선점 INSERT가 `idempotency_records → members` FK 검사로 회원 행에 공유 잠금을 건다. 서비스 안에서 나중에 회원 행을 배타 잠그면 같은 회원의 동시 요청들이 공유 잠금을 함께 쥔 채 배타 잠금으로 올리려다 데드락이 난다(첫 verify 재실행의 같은 회원 경합 테스트가 이 대기 위치 차이로 드러냈다). 그래서 확보 생성·해제는 실행기의 접근 검사 단계(선점 전)에서 회원 행을 먼저 `FOR UPDATE`로 잠근다. 해제는 소유권 확인(404) 뒤에 잠근다. 다른 회원끼리는 서로 막지 않는다.
- **판정 시각**: 필요한 잠금을 모두 얻은 뒤 `DatabaseTime`(DB `UTC_TIMESTAMP(6)`)으로 읽는다. 테스트는 이 빈을 고정 가능한 구현으로 바꿔 마이크로초 경계를 재현한다.
- **작업자 비의존 만료**: 확보 생성은 대상 좌석을 점유한 확보(다른 회원 포함)와 본인의 기존 ACTIVE 확보가 만료됐으면 같은 트랜잭션에서 EXPIRED로 종결하고 좌석을 비운 뒤 확보한다. 해제·조회·좌석 조회도 만료를 다시 판정한다. 그래서 작업자가 멈춰도 만료 좌석은 다시 확보된다. 조회는 DB를 바꾸지 않는다.
- **오래된 작업 무해성**: 종결 전이는 `WHERE status = 'ACTIVE'`, 좌석 해제는 `WHERE hold_id = 대상` 조건부다. 작업자는 잠금 없이 읽은 후보를 처리하되, 확보마다 불변 소유자·회차를 읽고 회원 → 회차 → 확보 순서로 잠근 뒤 "여전히 ACTIVE이고 만료 시각이 지났는지"를 재검증한다. 늦게·반복 실행돼도 새 소유자의 좌석·확보를 바꾸지 못한다.
- **판매 잠금**: 최초 확보 트랜잭션에서 `sales_locked_at IS NULL`일 때만 기록한다. 해제·만료는 지우지 않으므로 확보가 모두 끝나도 HJ-004 운영 API의 시간·가격·상영관 변경과 삭제가 409 `SALES_LOCKED`다.
- **반복 해제**: 이미 RELEASED/EXPIRED인 확보를 새 키로 해제해도 200과 현재 종결 상태를 돌려주고 아무것도 바꾸지 않는다(같은 키는 원래 응답 재전송).
- **설계에 없던 추가(검토 요청)**: `holds.ended_at`(종결 반영 시각), `hold_items.screening_id`(확보·inventory 복합 FK로 다른 회차 좌석을 DB 수준에서 차단), `holds.total_amount`, 오류 코드 이름(`SEAT_NOT_IN_SCREENING`은 400, `SEAT_UNAVAILABLE`·`ACTIVE_HOLD_EXISTS`·`SCREENING_NOT_BOOKABLE`·`HOLD_CONSUMED`는 409), `GET /holds/{id}`의 null 결제·예매 참조 필드.
- **기존 HELD 정리**: V5는 CHECK 추가 전에 소유 확보 없는 HELD 행을 AVAILABLE로 되돌린다. 이전 버전에는 확보를 만드는 기능이 없어 그런 행은 테스트 fixture 잔여물뿐이다.
- **작업자 설정**: `hanjari.holds.expiry-worker.enabled`(기본 true), `interval-ms`(기본 1000). 테스트 클래스패스의 `config/application.properties`가 기본으로 끈다. 캐시된 다른 테스트 컨텍스트의 작업자가 실행 중인 테스트 데이터를 바꾸지 않게 하기 위해서다.

## 기존 코드·테스트 변경
- `CatalogQueryService`·`CatalogController`·`CatalogResponses`: 좌석 조회 `mine`/`expiresAt`, 만료 반영 HELD·`bookable`. 판정 시각을 `DatabaseTime`으로 읽는다.
- `ApiExceptionHandler`: `com.hanjari.hold` 처리기의 일시적 DB 장애 → 503, 그 밖 → 500(고정 이벤트 로그).
- `application.yml`: 작업자 설정 추가.
- `TestDataCleaner`: `hold_items` → `screening_seats` → `holds` → `screenings` 순서로 등록.
- `HjMysqlFoundationIT`: 최초 적용 테이블 목록에 `holds`, `hold_items` 추가와 0행 확인(테스트 수 5 유지).
- `HjCatalogOperationsIT`: HELD 좌석 fixture가 다른 회원의 유효 확보를 참조하도록 바꿨다(DB CHECK). 응답에 `expiresAt`·holder 정보가 없음을 추가 확인(테스트 수 12 유지).
- `HjIdempotencyPersistenceIT`: probe가 좌석을 HELD로 표시할 때 fixture 확보 행을 함께 만들고, 해제·접수 시 `hold_id`를 비운다(테스트 수 7 유지, 판정 로직 불변).

## 실행
```bash
sh ./mvnw -B -ntp -V verify   # 실행 전 HANJARI_TEST_DB_URL / _USERNAME / _PASSWORD 설정 필요
```

## 확인 결과 (2026-11-11, Linux 컨테이너)
- 최종 `sh ./mvnw -B -ntp -V verify`: **BUILD SUCCESS**, 종료 코드 0, Total time 03:36 min, Finished at 2026-11-11T11:42:55Z. Maven 3.9.11, Java 17.0.17, Spring Boot 4.1.1, Flyway 12.4.0, MySQL 서버 8.0.40.
- surefire 80개 통과(실패·오류·skip 0): MovieControllerTest 13, MovieIdParserTest 16, MovieServiceTest 2, HjFoundationPolicyTest 4, HjMemberAuthenticationPolicyTest 10, HjAuthFailurePolicyTest 5, HjCatalogScreeningPolicyTest 10, HjIdempotencyContractTest 8, HjSeatHoldLifecycleTest 12.
- failsafe 55개 통과(`failsafe-summary.xml` completed=55, errors/failures/skipped/flakes 0): MovieApiIT 4, HjMysqlFoundationIT 5, HjSessionAuthenticationIT 12, HjAuthEncodedPathFailureIT 1, HjCatalogOperationsIT 12, HjIdempotencyPersistenceIT 7, HjSeatHoldConcurrencyIT 14.
- 같은 좌석 6명 경합: statuses=[409, 409, 201, 409, 409, 409], DB 유효 확보 1건. 같은 회원 4회차 경합: [201, 409, 409, 409], 활성 확보 1건. 오래된 만료 작업 vs 재확보 경합: workerExpired=1, reacquire=201, 새 소유자 유지.
- 이전 실행: (1) 종료 1 — HjCatalogScreeningPolicyTest가 SeatMap 필드 목록을 3개로 고정 → 승인 계약의 `expiresAt` 반영. (2) 종료 1 — 같은 회원 경합에서 요청들이 회원 `FOR UPDATE`가 아닌 멱등 선점 INSERT(회원 FK 공유 잠금)에서 대기 → 회원 잠금을 선점 전으로 옮김(결정과 근거 참고).

## 한계
- 데드락·잠금 대기 초과의 503 경로는 HJ-005 실행기 경로를 그대로 쓰며, 이번 업무에서 확보 API에 별도 장애 주입은 하지 않았다.
- 재시작 검증은 같은 JVM에서 운영 구성만 올린 두 번째 애플리케이션 인스턴스(같은 DB·세션 저장소)로 했다. 별도 프로세스 재기동은 아니다.
- 설계의 MySQL 8.4 LTS가 아니라 기존 테스트 서버로 검증했다.
