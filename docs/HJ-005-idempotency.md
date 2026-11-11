# HJ-005 변경 요청 멱등성

## 범위
확보 생성·해제·결제 접수·전체 취소가 공통으로 쓸 멱등 기반을 구현했다: `Idempotency-Key` 형식 계약, operation별 요청 지문(정규화·SHA-256), `idempotency_records` 테이블, 도메인 변경과 같은 트랜잭션에서 선점·완료·재전송·충돌을 처리하는 실행기.
확보·결제·취소 API 자체는 후속 업무(HJ-006~)다. 이번 업무에는 실제 엔드포인트가 없으므로, 통합 테스트는 테스트 전용 probe 컨트롤러로 실제 실행기·지문 계약·보안 체인과 실제 도메인 테이블(`screening_seats.state`, `screenings.sales_locked_at`)을 사용해 검증했다.
범위 밖: 멱등 기록 자동 삭제, 가입·로그인 본문 기록, 외부 캐시.

## 계약
| 항목 | 규칙 |
|---|---|
| 헤더 | `Idempotency-Key`. UUID 정규 표기 8-4-4-4-12 16진수만. 없음 → 400 `IDEMPOTENCY_KEY_REQUIRED`; 형식 위반·헤더 2개 이상 → 400 `INVALID_IDEMPOTENCY_KEY`. 16진수 대소문자는 같은 키(소문자로 저장). trim 등 다른 정규화는 없다. |
| 범위 | 회원 + operation(`HOLD_CREATE`, `HOLD_RELEASE`, `PAYMENT_ATTEMPT_CREATE`, `RESERVATION_CANCEL`) + 키. DB `UNIQUE(member_id, operation, idempotency_key)`. |
| 지문 | `{"body":{..이름 순..},"method":"POST","operation":"..","path":"/api/v1/.."}`의 SHA-256. 경로는 해석한 ID로 다시 만든 정규 경로(원시 URI·인코딩·matrix 미사용), 본문은 선언한 필드만. 헤더·쿠키·세션은 들어가지 않는다. |
| 좌석 | `holdCreate(screeningId, seatIds)`: 좌석 ID를 오름차순으로 정렬해 순서 차이를 무시. 중복 좌석 ID는 정규화하지 않고 400 `DUPLICATE_SEAT_ID`(기존 키로 보내도 재전송이 아니라 400). |
| 같은 키·같은 지문 | 저장된 원래 HTTP 상태·본문 원문을 그대로 반환. 작업은 다시 실행하지 않는다. 재전송 표시 헤더 `Idempotency-Replayed: true`만 추가된다. |
| 같은 키·다른 지문(경로·본문) | 409 `IDEMPOTENCY_CONFLICT` `{code,message}`. 원래 응답은 담지 않는다. |
| 저장 대상 | 종결 2xx만(201·200·202). 업무 오류(4xx)·일시 장애(5xx)는 도메인 변경과 함께 롤백되어 같은 키로 재실행된다. DB CHECK도 2xx만 허용한다. |
| 일시 장애 | 데드락·잠금 대기 초과·연결 실패 → 전체 롤백 후 503 `SERVICE_UNAVAILABLE`(같은 요청 재시도 안내). 원인·SQL은 응답·로그에 없다. |
| 비동기 | 202 `{attemptId|cancellationId, statusUrl}` 같은 접수 응답을 저장해 재전송한다. 최신 상태는 조회 API로 확인한다(재전송 본문은 접수 시점 그대로). |

| 소유권 우선 | 경로의 기존 소유 리소스를 대상으로 하는 operation(`HOLD_RELEASE`, `PAYMENT_ATTEMPT_CREATE`, `RESERVATION_CANCEL`; `IdempotencyOperation.targetsOwnedResource()`)은 실행기가 **기록 선점·조회·재전송·충돌 판정보다 먼저** 소유권 검사(`IdempotencyAccessCheck`)를 실행한다. 없는 리소스·타인 리소스 → 404이며, 자기 범위에 이미 기록이 있는 키를 써도 409·재전송이 아니다. 이 operation에 `NO_TARGET_RESOURCE`·null을 넘기면 실행기가 거부한다. |

컨트롤러의 처리 순서: (보안 체인: Origin → CSRF → 인증) → 키 해석 → 본문 검증·지문 생성 → `IdempotencyExecutor.execute(memberId, key, request, accessCheck, action)` → `IdempotentResponse.toResponseEntity()`. 실행기 안의 순서: 접근 검사 → 선점 → (재전송/409 | 작업 실행·완료) → 커밋.

## 파일 읽는 순서
1. `db/migration/V4__create_idempotency_records.sql`: 열은 범위·요청 해시·상태·응답 원문·시각뿐. 응답은 바이트 보존을 위해 JSON 타입이 아니라 `TEXT utf8mb4_bin` + `JSON_VALID` CHECK. operation·키 형식·해시 형식·2xx·완료 조합 CHECK, 회원 FK(cascade 없음).
2. `idempotency/IdempotencyKey.java`, `IdempotencyOperation.java`, `IdempotencyAccessCheck.java`: 키 형식, operation 목록(인증 요청 없음)·소유 리소스 대상 여부, 기록 접근 전 소유권 검사 계약.
3. `idempotency/IdempotentRequest.java`, `CanonicalJson.java`: operation별 지문 계약과 결정적 직렬화.
4. `idempotency/IdempotencyExecutor.java`: 핵심. 트랜잭션(READ COMMITTED) 안에서 소유권 검사 → 선점 INSERT → (실패 시) 커밋된 기록 조회 후 재전송/409 → (성공 시) 작업 실행 → 응답 직렬화·완료 → 커밋. 이미 열린 트랜잭션 안에서 호출하면 거부한다(경계 소유).
5. `idempotency/JdbcIdempotencyRecordStore.java`, `IdempotencyRecordStore.java`, `IdempotentResult.java`, `IdempotentResponse.java`.
6. 테스트: `idempotency/HjIdempotencyContractTest`(단위 8), `idempotency/HjIdempotencyPersistenceIT`(실제 MySQL + 실제 HTTP 7, probe 컨트롤러 포함).

## 결정과 근거
- **선점 우선(INSERT first)**: 작업 전에 범위 행을 INSERT한다. InnoDB는 같은 고유 키를 선점한 미커밋 트랜잭션이 있으면 뒤 요청을 그 트랜잭션 종료까지 대기시킨다. 앞 요청이 커밋하면 뒤 요청은 중복 키 오류 → 커밋된 기록을 읽어 재전송한다. 롤백하면 뒤 요청이 선점해 실행한다. 별도 잠금 테이블·애플리케이션 락 없이 DB 고유 제약 하나로 단일 결과를 만든다. 서로 다른 키는 서로 막지 않는다.
- **커밋된 기록은 항상 완료**: 선점과 완료가 같은 트랜잭션이라 미완료 행이 커밋되지 않는다. 그래서 "처리 중" 상태를 응답하는 경로가 없다. 선점 실패 후 기록이 보이지 않거나 미완료면(정상 경로에서는 생기지 않음) 503으로 재시도시킨다.
- **잠금 순서와의 관계**: 기록 선점은 기획의 회원 → 회차 → … 잠금보다 앞선다. 같은 키 경쟁자는 선점에서 기다리며 다른 잠금을 쥐지 않으므로 순환 대기를 만들지 않는다. 단, 첫 선점자가 롤백되면 대기자들이 같은 키의 공유 잠금을 함께 쥔 상태에서 배타 잠금을 요구해 MySQL이 데드락으로 일부를 중단시킬 수 있다(InnoDB의 알려진 중복 키 동작). 중단된 요청은 503이고 같은 키 재시도로 원래 응답에 수렴한다. IT가 이 경로를 실제로 검증한다.
- **4xx 비저장(검토 요청)**: 기획은 "원래 응답"과 "일시적 5xx 비저장"만 명시한다. 이번 구현은 4xx도 저장하지 않는다. 예를 들어 좌석 점유로 409를 받은 키를 상태가 바뀐 뒤 재시도하면 새로 판정한다. 대신 같은 키·같은 내용이 다른 시점에 다른 4xx를 받을 수 있다. 업무 오류를 영구 저장하려면 도메인 롤백과 기록 커밋을 분리해야 하므로 보류했다.
- **재전송 표시 헤더**: 상태·본문은 같고 `Idempotency-Replayed: true` 헤더만 추가된다. 클라이언트·지표용이다(설계에 없던 추가, 검토 요청).
- **키 대소문자**: UUID 16진수는 대소문자를 구분하지 않으므로 같은 키로 본다(설계에 명시 없음, 검토 요청).
- **비유출**: 미인증은 보안 체인이 컨트롤러 전에 401로 끝내므로 기록을 조회하지 않는다. 범위에 회원 ID가 들어가 다른 회원의 기록은 조회·재전송 대상이 아니다. 타인 자원 요청은 실행기가 기록에 접근하기 전에 소유권 검사(404)로 끝나므로, 요청자가 자기 리소스에 이미 쓴 키를 재사용해도 409 충돌·재전송으로 기존 기록의 존재가 드러나지 않고 선점 행도 남지 않는다(리뷰 지적 반영). 소유권 검사는 불변 소유자 정보만 읽고 잠그지 않으며, 작업 안에서 승인된 잠금 순서로 다시 검증한다. 409 충돌 응답은 원래 응답을 담지 않는다.
- **비밀 비기록**: operation에 가입·로그인·로그아웃이 없다. 지문은 선언한 본문 필드와 정규 경로만 쓰고 원문 대신 해시만 저장한다. 키·요청·응답 값은 로그에 남기지 않는다(로그는 `event=IDEMPOTENCY_REPLAYED|IDEMPOTENCY_CONFLICT|IDEMPOTENT_REQUEST_ROLLED_BACK operation=… status=…`).
- **열 이름**: 설계의 `key`는 MySQL 예약어라 `idempotency_key`, 응답은 `response_body`로 했다. 완료 시각 `completed_at`을 추가했다.

## 기존 코드 변경
- `TestDataCleaner.TABLES_CHILD_FIRST`에 `idempotency_records`를 `members` 앞에 추가했다.
- `HjMysqlFoundationIT`: 빈 스키마 최초 적용의 기대 테이블 목록에 `idempotency_records`를 추가하고 0행 확인을 넣었다(테스트 수 5 유지).
- 운영 코드의 기존 파일은 바꾸지 않았다.

## 실행
```bash
sh ./mvnw -B -ntp -V verify   # 실행 전 HANJARI_TEST_DB_URL / _USERNAME / _PASSWORD 설정 필요
```

## 확인 결과 (2026-11-11, Linux 컨테이너)
- 리뷰 반영 후(소유권 검사 선행) 재실행 `sh ./mvnw -B -ntp -V verify`: **BUILD SUCCESS**, Total time 23:40 min, Finished at 2026-11-11T10:21:08Z. surefire 68·failsafe 41 통과(실패·오류·skip 0), 아래 suite 수 동일. 이번 실행에서 HjSessionAuthenticationIT가 1191 s 걸렸다(통과, 이번 변경과 무관한 suite이며 원인은 확인하지 않았다). 롤백 경합: statuses=[503, 503, 503, 201, 500, 503], 재시도 후 단일 201 수렴.
- 최초 구현 실행 `sh ./mvnw -B -ntp -V verify`: **BUILD SUCCESS**, 종료 코드 0, Total time 03:39 min, Finished at 2026-11-11T09:52:08Z. Apache Maven 3.9.11, Java 17.0.17, MySQL 서버 8.0.40, Flyway 12.4.0.
- surefire 68개 통과(실패·오류·skip 0): MovieControllerTest 13, MovieIdParserTest 16, MovieServiceTest 2, HjFoundationPolicyTest 4, HjMemberAuthenticationPolicyTest 10, HjAuthFailurePolicyTest 5, HjCatalogScreeningPolicyTest 10, HjIdempotencyContractTest 8.
- failsafe 41개 통과(`failsafe-summary.xml` completed=41, errors/failures/skipped 0): MovieApiIT 4, HjMysqlFoundationIT 5, HjSessionAuthenticationIT 12, HjAuthEncodedPathFailureIT 1, HjCatalogOperationsIT 12, HjIdempotencyPersistenceIT 7.
- 빈 스키마 최초 적용: executed=4, targetSchemaVersion=4(`idempotency_records` 포함). 재실행: 0/0.
- 동시 동일 키 8건: statuses 전부 201(새 실행 1 + 재전송 7), 선점 대기 관찰 7건, 도메인 실행 1회, 기록 1행.
- 첫 선점자 롤백 경합 6건: statuses=[503, 500, 503, 503, 201, 503]. 대기자 사이에 InnoDB 데드락이 생겨 503이 나왔다. 같은 키로 재시도하자 모두 같은 201로 수렴했고 도메인 실행 2회(롤백 1, 커밋 1), 기록 1행이었다.

## 한계
- 실제 확보·결제·취소 엔드포인트는 후속 업무다. 이번 검증은 테스트 전용 probe(실제 실행기·지문·보안 체인·DB 테이블 사용)로 했고, probe 자원의 소유자 매핑은 메모리 fixture다. 후속 업무가 실제 엔드포인트에서 같은 계약을 다시 검증해야 한다.
- HJ-004 문서·주석(`V3__create_catalog_screenings.sql`, `CatalogResponses`, `HjCatalogOperationsIT`)은 확보 기능을 "HJ-005"로 적었지만, 승인된 HJ-005는 멱등성이다. 적용된 마이그레이션의 체크섬을 바꾸지 않기 위해 그 주석은 고치지 않았다.
- 멱등 기록 자동 삭제는 후속이며 기록은 계속 쌓인다.
- 설계의 MySQL 8.4 LTS가 아니라 기존 테스트 서버로 검증했다.
