# HJ-004 영화·회차·좌석 조회와 최소 운영 API

## 범위
비회원 공개 조회(영화 목록·기존 상세·한국 날짜별 회차·회차 좌석), 합성 초기 데이터(dev 프로필), 최소 운영 API(영화 생성·수정, 회차 생성·수정·삭제)를 구현했다.
범위 밖: 상영관·좌석 편집 API, 영화 삭제, 관리자 화면, 실제 운영자 생성, 확보·판매 잠금 기록(HJ-005).

## API (`/api/v1`)
| 요청 | 성공 | 주요 실패 |
|---|---|---|
| `GET /movies?page=0&size=20` | 200 `{items,page,size,hasNext}`, id 오름차순, 항목은 상세와 같은 4필드 | 400 `INVALID_PAGE`(0~1,000,000), `INVALID_SIZE`(1~100) |
| `GET /movies/{id}` | 기존 계약 그대로(4필드) | 기존 400/404 |
| `GET /screenings?movieId=&date=YYYY-MM-DD` | 200 `{items:[{id,movieId,theaterId,theaterName,startsAt,endsAt,basePrice,bookable}]}`, 한국 날짜 [00:00 KST, 다음 날 00:00 KST)에 시작, `startsAt,id` 순 | 400 `INVALID_MOVIE_ID`/`INVALID_DATE`, 404 `MOVIE_NOT_FOUND` |
| `GET /screenings/{id}/seats` | 200 `{screeningId,serverNow,seats:[{seatId,row,number,status,mine}]}`, 행 이름 길이→행→번호 순 | 400 `INVALID_SCREENING_ID`, 404 `SCREENING_NOT_FOUND` |
| `POST /ops/movies {title,runtimeMinutes,ageRating}` | 201 `{id,title,runtimeMinutes,ageRating,version}` | 400 `INVALID_TITLE`/`INVALID_RUNTIME`/`INVALID_AGE_RATING`/`UNSUPPORTED_FIELD`/`INVALID_REQUEST` |
| `PATCH /ops/movies/{id} {version, title?, runtimeMinutes?, ageRating?}` | 200 (version+1) | 404, 409 `VERSION_CONFLICT` |
| `POST /ops/screenings {movieId,theaterId,startsAt,basePrice}` | 201 `{id,movieId,theaterId,startsAt,endsAt,basePrice,salesLocked,version}` | 400 `INVALID_*`/`STARTS_AT_NOT_FUTURE`/`STARTS_AT_OUT_OF_RANGE`, 404 `MOVIE_NOT_FOUND`/`THEATER_NOT_FOUND`, 409 `SCREENING_OVERLAP` |
| `PATCH /ops/screenings/{id} {version, theaterId?, startsAt?, basePrice?}` | 200 | 409 `SALES_LOCKED`/`VERSION_CONFLICT`/`SCREENING_OVERLAP`/`SCREENING_STARTED` |
| `DELETE /ops/screenings/{id}` | 204 | 404, 409 `SALES_LOCKED` |

- 시각은 UTC ISO-8601. 입력 `startsAt`은 `Z` 표기만, 소수 초는 마이크로초(DB 정밀도)까지만 받는다. 24:00·60초는 거부한다.
- 숫자는 JSON 정수만 받는다(문자열·실수·불리언 거부). 쿼리 값은 부호·선행 0·중복 파라미터를 거부한다.
- 운영 API는 HJ-003 체인을 그대로 따른다: Origin → CSRF → 권한(미인증 401, 일반 회원 403).
- `bookable` = DB 시각 기준 시작 전이고 AVAILABLE 좌석이 1개 이상.
- 공개 응답에는 회원·확보·예매 식별자, 로그인 ID가 없다. `mine`은 확보 기능 전이라 항상 false이고, 본인 확보 `expiresAt`은 HJ-005에서 추가한다.

## 파일 읽는 순서
1. `db/migration/V3__create_catalog_screenings.sql`: `theaters`, `seats`(UNIQUE 위치, 행 `^[A-Z]{1,2}$`, 번호 1~999), `screenings`(시작<종료, 가격 0~1,000,000, `sales_locked_at`, `version`), `screening_seats`(PK (회차,좌석), 상태 CHECK). inventory는 `(screening_id, theater_id)`와 `(seat_id, theater_id)` 두 복합 FK로 회차 상영관 밖의 좌석을 DB 수준에서 막는다. cascade는 없다. `movies`에는 `version`만 추가했다.
2. `db/devdata/R__hj004_synthetic_catalog.sql`: dev 프로필 전용 반복 마이그레이션. 합성 영화 2편, 상영관 3개, 좌석 100석. 이미 있는 행은 LEFT JOIN으로 건너뛰므로 다시 적용해도 결과가 같다. 계정·세션·회차는 만들지 않는다.
3. `screening/ScreeningSchedule.java`: 종료 = 시작 + 상영시간. 점유 구간 [시작, 종료+10분)은 반열린 구간이다. 한국 날짜→UTC 구간. DB 변환은 UTC LocalDateTime이며 드라이버 시간대 변환을 거치지 않는다.
4. `common/RequestValues.java`, `operations/OpsRequestPolicy.java`: 입력 정책.
5. `operations/OpsService.java`: 운영 변경. READ COMMITTED에서 잠금을 얻은 뒤 판정한다. 잠금 순서는 상영관(ID 오름차순) → 회차이며 회원 잠금은 얻지 않는다. 시각 판정은 `UTC_TIMESTAMP(6)`. 순서: 판매 잠금 → version → (시작 변경 시 영화 행 `FOR SHARE`) → DB 시각 → 저장 범위 → 중첩. DB 시각은 필요한 잠금(상영관·회차·영화)을 모두 얻은 뒤에 읽는다(리뷰 반영: 영화 잠금 대기 중 시작 시각이 지나면 거부).
6. `catalog/CatalogController.java`, `catalog/CatalogQueryService.java`: 공개 조회. 읽기 전용 트랜잭션.
7. `auth/SecurityConfig.java`(GET 공개 경로 추가), `common/ApiExceptionHandler.java`(catalog·operations 처리기의 일시적 DB 장애 → 503, 그 밖 → 500, 고정 이벤트 로그만).
8. 테스트: `catalog/HjCatalogScreeningPolicyTest`(단위 10), `catalog/HjCatalogOperationsIT`(실제 MySQL + 실제 HTTP 12).

## 결정과 근거
- **중첩 방지**: 같은 상영관의 회차 생성·이동·시간 변경은 모두 상영관 행 `FOR UPDATE`로 직렬화한다. 그 뒤 READ COMMITTED 읽기로 최신 커밋을 보고 중첩을 검사한다. DB 제약으로 표현할 수 없는 구간 겹침을 잠금으로 보장한다.
- **종료 시각 저장·영화 수정**: 영화 수정은 회차를 건드리지 않는다. 판매 잠금 여부와 무관하게 기존 회차의 저장된 시간은 그대로다. 잠금 전 회차의 `startsAt`을 명시적으로 바꿀 때만 그 시점의 상영시간으로 종료를 다시 계산한다.
- **상영관 이동**: 잠금 전 회차는 확보·예매가 없으므로 inventory를 지우고 새 상영관 좌석으로 다시 만든다(같은 트랜잭션, cascade 아님).
- **판매 잠금**: `sales_locked_at`이 있으면 시간·가격·상영관 변경과 삭제를 거부한다. 기록 자체는 최초 확보 시 HJ-005가 한다. 이번 테스트는 DB fixture로 설정했다.
- **설계에 없던 추가 규칙(검토 요청)**: `startsAt` 없이 수정할 때 이미 시작한 회차는 409 `SCREENING_STARTED`로 거부한다. 과거·현재 시작은 400 `STARTS_AT_NOT_FUTURE`다. 본문의 없는 영화·상영관 참조는 404다. 종료 + 준비 10분이 DATETIME(6) 상한(9999-12-31T23:59:59.999999Z)을 넘는 시작은 400 `STARTS_AT_OUT_OF_RANGE`다(리뷰 반영: 이전에는 DB 예외로 500).
- **movies.version 열 추가**: 설계의 movies 열 목록에 없던 열이다. 운영 수정의 version 검증에 필요해서 추가했다. 상세 API 계약(4필드)은 바뀌지 않는다. `HjMysqlFoundationIT`의 열 목록 기대값을 갱신했다.

## 기존 테스트 변경
- `HjMysqlFoundationIT`: V3 테이블 목록, movies의 `version` 열을 반영했고, 최초 적용 후 상영관·좌석·회차가 0행인지 확인을 추가했다(테스트 수 5 유지).
- `HjSessionAuthenticationIT`: 운영자 fixture의 `POST /ops/movies {}` 기대값을 404(라우트 없음)에서 400 `INVALID_REQUEST` + movies 0행으로 바꿨다. 이제 라우트가 있기 때문이다(테스트 수 12 유지).
- `TestDataCleaner.TABLES_CHILD_FIRST`에 `screening_seats → screenings → seats → theaters`를 앞에 추가했다. `HjCatalogOperationsIT`는 테스트 뒤에도 정리한다. `MovieApiIT`의 movies 일괄 삭제가 회차 FK에 막히지 않게 하기 위해서다.

## 실행
```bash
sh ./mvnw -B -ntp -V verify   # 실행 전 HANJARI_TEST_DB_URL / _USERNAME / _PASSWORD 설정 필요
```

## 확인 결과 (2026-11-11, Linux 컨테이너)
- `sh ./mvnw -B -ntp -V verify`: **BUILD SUCCESS**, Total time 03:40 min, Finished at 2026-11-11T06:09:10Z. Apache Maven 3.9.11, Java 17.0.17.
- surefire 60개 통과(실패·오류·skip 0): MovieControllerTest 13, MovieIdParserTest 16, MovieServiceTest 2, HjFoundationPolicyTest 4, HjMemberAuthenticationPolicyTest 10, HjAuthFailurePolicyTest 5, HjCatalogScreeningPolicyTest 10.
- failsafe 34개 통과(실패·오류·skip 0, `failsafe-summary.xml` completed=34): MovieApiIT 4, HjMysqlFoundationIT 5, HjSessionAuthenticationIT 12, HjAuthEncodedPathFailureIT 1, HjCatalogOperationsIT 12. 실제 MySQL 8.0.40에 연결했다.
- 빈 스키마 최초 적용: executed=3, targetSchemaVersion=3. 재실행: 0/0.
- 동시 중첩 등록(상영관 2개 × 4건): statuses=[409,409,409,409,409,201,201,409]. 상영관마다 회차가 DB에 정확히 1개다.
- 같은 version 동시 수정 4건: [409,409,200,409]. DB 가격은 승자의 값이고 version=2다.

### 리뷰 반영 후 재실행 (2026-11-11)
- `sh ./mvnw -B -ntp -V verify`: **BUILD SUCCESS**, Total time 03:26 min, Finished at 2026-11-11T06:29:26Z. surefire 60, failsafe 34(실패·오류·skip 0). suite별 개수는 위와 같다(HjCatalogScreeningPolicyTest 10, HjCatalogOperationsIT 12 유지; 새 검증은 기존 메서드 안에 추가).
- 영화 잠금 대기 중 시작 경과 → 400 `STARTS_AT_NOT_FUTURE`, DB 무변경. 저장 상한 초과 생성·수정 → 400 `STARTS_AT_OUT_OF_RANGE`, 점유 종료가 정확히 상한인 시작은 201.

## 한계
- 합성 초기 데이터의 반복 적용은 스크립트를 테스트 스키마에 직접 세 번 실행해 검증했다(누락 좌석 보충도 확인). Flyway의 반복 마이그레이션 재적용(체크섬 변경 시)이나 dev 프로필 실제 기동은 실행하지 않았다. 공유 테스트 스키마의 이력 테이블에 devdata 이력이 섞이는 것을 피하기 위해서다.
- 판매 잠금은 fixture로만 설정했다. 확보와 판매 잠금 사이의 경합은 HJ-005에서 검증해야 한다.
- 503 매핑은 코드 경로만 추가했고, 이번 업무에서 장애 주입 테스트는 하지 않았다.
- 설계의 MySQL 8.4 LTS가 아니라 기존 8.0.40 서버로 검증했다.
