# HJ-003 회원과 세션 인증·권한 기반

## 범위
가입·로그인·로그아웃·현재 회원·CSRF 토큰 API와 서버 세션 인증, 운영 API 권한 경계를 구현했다. 범위 밖 항목은 다음과 같다: 실계정 운영 권한 부여, 비밀번호 복구, 계정 삭제, 소셜 로그인, 추가 개인정보 수집. 계정·권한 변경 API는 없다.

## API (`/api/v1`)
| 요청 | 성공 | 주요 실패 |
|---|---|---|
| `GET /auth/csrf` | 200 `{headerName, token}`. 서버 세션 쿠키 발급 | – |
| `POST /auth/signup {loginId,password}` | 201 `{memberId, loginId}` | 400 `INVALID_LOGIN_ID`/`INVALID_PASSWORD`/`INVALID_REQUEST`/`ROLE_NOT_ALLOWED`/`UNSUPPORTED_FIELD`, 409 `LOGIN_ID_TAKEN` |
| `POST /auth/login {loginId,password}` | 200 `{memberId, loginId}`. 세션 ID·CSRF 토큰 교체 | 401 `LOGIN_FAILED`(사유 구별 없음), 429 `LOGIN_RATE_LIMITED` + `Retry-After` |
| `POST /auth/logout` | 204. 서버 세션 삭제, `Max-Age=0` 쿠키. 이미 로그아웃된 상태여도 CSRF를 충족하면 204 | 403 |
| `GET /me` | 200 `{memberId, loginId}` | 401 `UNAUTHENTICATED` |

모든 상태 변경 요청은 두 검사를 차례로 통과해야 한다. 먼저 Origin을 검사한다(실패 시 403 `ORIGIN_NOT_ALLOWED`). 그다음 CSRF 헤더(`X-CSRF-TOKEN`)를 검사한다(실패 시 403 `CSRF_INVALID`). 미인증은 401 `UNAUTHENTICATED`, 일반 회원의 `/api/v1/ops/**` 접근은 403 `FORBIDDEN`이다. 오류 본문은 `{code,message}`이며 고정 문구만 쓴다.

## 파일 읽는 순서
1. `src/main/resources/db/migration/V2__create_members_and_sessions.sql`: `members`에는 로그인 ID·해시·역할·생성 시각만 둔다. 다음 DB 제약을 건다.
   - 로그인 ID: `UNIQUE`, 형식 `CHECK`, `utf8mb4_bin`.
   - 해시: `$argon2id$` 접두 `CHECK`.
   - 역할: `CHECK`.
   - Spring Session JDBC 표준 테이블도 만든다. 초기 데이터 INSERT는 없다.
2. `src/main/java/com/hanjari/auth/SecurityConfig.java`: 필터 체인과 권한 규칙을 정의한다. httpBasic·formLogin·요청 캐시는 끈다. `HanjariApplication`이 import하므로 WebMvc 슬라이스 테스트에도 같은 체인이 적용된다.
3. `auth/CredentialPolicy.java`: 입력을 정규화하지 않는다. 허용 필드는 `loginId`, `password`뿐이다. 로그인 ID는 `[a-z0-9_]{4,30}`이고, 비밀번호는 코드 포인트 기준 12~64자이며 trim하지 않는다.
4. `member/MemberService.java`, `member/MemberRepository.java`: 가입 시 역할은 항상 MEMBER로 고정한다. 동시 중복 가입은 DB UNIQUE가 최종 판정하고 409로 응답한다.
5. `auth/MemberAuthenticationProvider.java`: 없는 ID나 형식 위반이어도 더미 해시로 Argon2 검증을 한 번 수행해 실패 경로를 같게 맞춘다. 이 빈이 있어 Spring Boot 기본 메모리 사용자가 생성되지 않는다.
6. `auth/AuthController.java`: 로그인 흐름은 요청 제한 → 인증 → 세션 ID 교체(`ChangeSessionIdAuthenticationStrategy`) → CSRF 토큰 교체 → 인증 정보 저장 → 로그인 시각 기록 순서다.
7. `auth/SessionLifetimeFilter.java`: 절대 만료를 처리한다. `now >= 로그인 시각 + 12h`이면 세션을 폐기한다. 유휴 30분은 Spring Session의 `spring.session.timeout`으로 처리하며, DB의 `MAX_INACTIVE_INTERVAL`은 1800이다.
8. `auth/LocalOriginFilter.java`, `auth/LoginRateLimiter.java`, `auth/SessionCookieConfig.java`.
9. 테스트: `HjMemberAuthenticationPolicyTest`(단위 10개, DB 없음), `HjSessionAuthenticationIT`(실제 MySQL + 임의 포트 실제 HTTP 서버 12개).

## 결정과 근거
- **세션**: Spring Security와 Spring Session JDBC를 쓴다. 쿠키는 `HANJARI_SESSION`이고 HttpOnly, SameSite=Lax, Path=/를 적용하며 Domain은 두지 않는다(host-only). 로컬 HTTP 전제이므로 Secure는 붙이지 않았고, 외부 공개 전 재검토가 필요하다.
  - Spring Session 쿠키 값은 세션 ID를 Base64로 인코딩한 값이다.
  - `PRINCIPAL_NAME`에는 로그인 ID가 아니라 회원 ID를 기록한다.
- **세션 테이블 FK cascade 예외**: `SPRING_SESSION_ATTRIBUTES`의 `ON DELETE CASCADE`는 Spring Session이 세션을 삭제할 때 전제로 하는 표준 스키마다. 도메인 테이블의 cascade 금지 규칙과는 별개다.
- **Argon2id 파라미터**: m=16384KiB, t=2, p=1, salt 16B, hash 32B(Spring Security 5.8 기본값).
  - 개발 환경 측정값(Linux 컨테이너, Java 17.0.17, 2026-11-11 단위 테스트 출력): encode 480ms, match 39ms.
  - encode 값은 첫 호출이라 JIT 워밍업이 포함됐다. 단일 측정이므로 성능 목표로 쓰지 않는다.
- **요청 제한**: 인스턴스 메모리에서 UTC 분 단위 고정 창으로 센다. 로그인 ID별 5회, 전체 100회다.
  - 허용된 시도는 성공·실패와 무관하게 센다.
  - 형식이 틀린 ID는 공용 키 하나로 센다.
  - IP·User-Agent는 저장하지 않는다.
  - 고정 창이라 창 경계에서는 짧은 시간에 최대 2배까지 허용될 수 있다.
  - 재시작하면 카운트가 초기화되며, 다중 인스턴스 간에는 공유하지 않는다.
- **Origin**: `hanjari.security.allowed-origins` 값과 정확히 일치해야 한다. 기본값은 `http://localhost:8080`이다. Origin이 없거나 `null`이면 거부한다. CORS는 열지 않았으며 F-01에서 결정한다.
- **로그**: 인증 실패·접근 거부·원시 입력을 기록하지 않는다. 해석 불가 본문도 `{code,message}`로만 응답한다. `org.springframework.security` 로그 레벨은 WARN이다.
  - 이번 verify 출력을 확인한 결과 테스트 로그인 ID·비밀번호·쿠키 이름·해시 문자열은 나타나지 않았다.
  - 단, 14일 보관 정책 구현은 이 업무 범위가 아니다.
- **인증 경로 장애(리뷰 반영)**: `/api/v1/auth/**`, `/api/v1/me`에서 발생한 장애는 `common/AuthFailurePolicy`를 따른다.
  - 일시적 DB 장애(연결 획득 실패·잠금 대기 초과·데드락·쿼리 시간 초과, 원인 사슬 포함)는 503 `SERVICE_UNAVAILABLE`으로 응답한다. 그 밖의 장애는 500 `INTERNAL_ERROR`다. 판별은 `common/TransientDataAccessFailures`가 하며, 중복 키 등 제약 위반은 일시 장애가 아니다(가입 중복은 기존대로 409).
  - 로그는 `event=AUTH_REQUEST_FAILED stage=HANDLER|FILTER category=DB_TRANSIENT|UNEXPECTED status=503|500`만 남긴다. 예외 객체·메시지·SQL·스택·요청 값은 넘기지 않는다.
  - MVC 처리기 밖(세션 테이블 조회·커밋 등)의 장애는 Spring Session 필터 바깥(order `MIN_VALUE+40`)의 `auth/AuthFailureGuardFilter`가 같은 정책으로 바꾼다. 예외를 다시 던지지 않아 컨테이너의 예외 원문 로그도 막는다. 이미 커밋된 응답은 고정 이벤트만 기록한다.
  - 기존 영화 API(`/api/v1/movies/**`)의 500 계약과 로그는 바꾸지 않았다.
  - **경로 판별(2차 리뷰 반영)**: 원시 `getRequestURI()` 문자열 비교를 버렸다. Spring MVC와 같은 방식(`RequestPath.parse` → 세그먼트별 퍼센트 디코딩 값, matrix 변수 제외)으로 판별한다. 그래서 `/api/v1/%6de`, `/api/v1/me;x=1`처럼 MVC가 `/api/v1/me`로 라우팅하는 요청도 인증 경로다. 빈 세그먼트·`.`은 건너뛰고 `..`은 상위로 올려, 판별이 넓어지는 쪽으로만 정규화한다. 해석할 수 없는 경로는 보수적으로 인증 경로 정책을 적용한다.
  - 처리기 단계에서는 이중 확인을 한다. `ApiExceptionHandler`가 MVC가 실제로 선택한 처리기(`BEST_MATCHING_HANDLER_ATTRIBUTE`)가 `com.hanjari.auth` 패키지 컨트롤러이면, 경로 표기와 무관하게 정책을 적용한다.

## 테스트가 확인하는 것
- AC1: 가입 정상 경로에서 DB 행·열 목록·UNIQUE 제약·Argon2id 해시 형식을 확인한다. 같은 비밀번호도 해시가 다른지, 평문을 넣으면 DB CHECK가 거부하는지 확인한다.
  - 입력 오류 12종에 400을 반환하고 DB 행이 0개인지 확인한다. 중복 가입은 409다.
  - 동시 중복 가입: 독립 HTTP 요청 8개를 barrier로 동시에 출발시키고 90초 유한 timeout을 둔다. 사후 DB 행이 정확히 1개이고 응답이 201 1건, 409 7건인지 확인한다. 저장된 해시가 승자의 비밀번호와 일치하는지도 확인한다.
- AC2: 다음 실패 5종의 응답 본문이 바이트 단위로 같은지 확인한다: 없는 ID, 틀린 비밀번호, trim된 비밀번호, 대문자 ID, 형식 위반.
  - 로그인 시 다음을 확인한다: DB의 세션 ID 변경, 이전 ID 행 삭제, 고정 쿠키 재사용 시 401, 로그인 전 CSRF 토큰 사용 시 403, 쿠키 속성.
- AC3: 로그아웃 시 204, DB 행 삭제, `Max-Age=0`을 확인하고 쿠키 재사용 시 401인지 확인한다.
  - 유휴 만료: DB의 `LAST_ACCESS_TIME`을 29분 전으로 옮기면 200(갱신됨), 30분 1초 전으로 옮기면 401이고 행이 삭제된다.
  - 절대 만료: 제어 Clock으로 시각을 옮긴다. 6시간과 12시간−1ms에는 200, 정확히 12시간에는 401이고 행이 삭제된다.
- AC4: 가입·로그인·로그아웃에서 CSRF가 없거나, 틀리거나, 다른 세션의 토큰이면 403을 확인한다. 이때 DB에 쓰기가 없고 세션은 유지된다. Origin 6종도 거부되는지 확인한다.
  - 요청 제한: ID별 6번째 시도는 비밀번호가 맞아도 429이고 `Retry-After`가 붙는다. 없는 ID의 429 응답은 있는 ID의 것과 같다. 다른 ID에는 영향이 없고, 다음 창에서 허용되며, 전체 101번째 시도는 429다.
- AC5: role 4종과 추가 필드 4종(email·name·phone·birthDate)이 거부되고 DB 행이 0개인지 확인한다. 가입한 계정의 역할은 MEMBER다.
  - 운영 경로는 미인증 401, 회원 403이다. 대조로 격리 DB에서 OPERATOR로 바꾼 fixture는 권한 검사를 통과한다.
  - `UserDetailsService` 빈이 없는지 확인한다.
  - `classpath*:db/**/*.sql`에 members·세션 INSERT가 없는지 확인한다.
- 장애 주입(`HjAuthFailurePolicyTest`, 단위 4개): 가입·로그인·`/me`에서 일시 DB 예외를 던지면 503 `{code,message}`인지 확인하고, 비일시 예외는 500인지 확인한다. 보호 필터가 필터 단계 장애를 503·500으로 바꾸고, 커밋된 응답에는 개입하지 않으며, 영화 경로는 통과시키는지도 확인한다.
  - 주입한 예외 메시지·원인에는 비밀번호·로그인 ID·JDBC URL·SQL·argon2 해시 문자열을 넣는다. 응답 본문과 로그 이벤트(메시지·인자)에 이 문자열이 없고 throwable이 첨부되지 않는지 검사한다.
  - 2차 리뷰 반영으로 단위 1개를 추가했다(총 5개). 인코딩·matrix 변수·이중 슬래시·`.`/`..`·context path 경로를 인증 경로로 판별하는지 확인한다. 반대로 `/api/v1/%6dovies/1`, `/api/v1/meeting`, `/api/v1%2Fme`, `/api/v2/me`는 아니라고 판별하는지 확인한다. 인코딩 경로에서 보호 필터가 503을 내는지도 확인한다.
- 인코딩 경로 장애 주입(`HjAuthEncodedPathFailureIT`, 통합 1개, 실제 MySQL·실제 HTTP): 실제 가입·로그인 후 먼저 `/api/v1/%6de`가 장애 없이 200과 본인 loginId를 돌려주는지 확인한다. MVC 라우팅 일치를 보는 대조다.
  - 이어서 `MemberRepository.findById`(spy)에 민감 SQL을 담은 `QueryTimeoutException`을 주입한다. `/api/v1/%6de`, `/api/v1/%6D%65`, `/api/v1/m%65`, `/api/v1/me` 모두 503 `{code,message}`인지 확인한다.
  - 루트 로거 전체 이벤트를 수집해 다음을 검사한다: 정확히 4개의 고정 이벤트 `event=AUTH_REQUEST_FAILED stage=HANDLER category=DB_TRANSIENT status=503`만 있는지, 어떤 로그 메시지·throwable에도 민감 문자열·예외 클래스명이 없는지.
- 기존 `HjMysqlFoundationIT`의 빈 스키마 최초 적용 테스트는 V2 테이블을 포함하도록 기대 테이블 목록을 갱신했다. 대소문자 구분 없이 비교하며, 최초 적용 후 members가 0행인지 확인을 추가했다.
  - `TestDataCleaner.TABLES_CHILD_FIRST`에 `SPRING_SESSION_ATTRIBUTES → SPRING_SESSION → members`를 등록했다. 테스트 서버의 `lower_case_table_names` 설정 때문에 information_schema가 소문자 이름을 돌려줘서, 등록 비교도 대소문자를 구분하지 않게 바꿨다.

## 실행
```bash
sh ./mvnw -B -ntp -V verify   # 실행 전 HANJARI_TEST_DB_URL / _USERNAME / _PASSWORD 설정 필요
```

## 확인 결과 (2026-11-11, Linux 컨테이너)
- `sh ./mvnw -B -ntp -V verify`: **BUILD SUCCESS**, Total time 02:59 min, Finished at 2026-11-11T01:21:49Z.
- surefire 45개 통과(실패·오류·skip 0): MovieControllerTest 13, MovieIdParserTest 16, MovieServiceTest 2, HjFoundationPolicyTest 4, HjMemberAuthenticationPolicyTest 10.
- failsafe 21개 통과(실패·오류·skip 0): MovieApiIT 4, HjMysqlFoundationIT 5, HjSessionAuthenticationIT 12.
- 실행 환경: Apache Maven 3.9.11(wrapper), Java 17.0.17, Spring Boot 4.1.1, Hibernate 7.4.5.Final, Flyway 12.4.0, Connector/J 9.7.0, MySQL 서버 8.0.40.
  - 빈 스키마 최초 적용 결과: executed=2, targetSchemaVersion=2. 재실행: 0/0.

## 확인 결과 (리뷰 반영 후 재실행, 2026-11-11)
- `sh ./mvnw -B -ntp -V verify`: **BUILD SUCCESS**, Total time 01:42 min, Finished at 2026-11-11T01:31:28Z.
- surefire 49개 통과(실패·오류·skip 0): 위 5개 suite(13/16/2/4/10)에 신규 HjAuthFailurePolicyTest 4개가 더해졌다.
- failsafe 21개 통과(실패·오류·skip 0): MovieApiIT 4, HjMysqlFoundationIT 5, HjSessionAuthenticationIT 12.
- 출력에 남은 인증 경로 장애 로그는 고정 형식의 `event=AUTH_REQUEST_FAILED ...` 줄뿐이다. 주입한 민감 문자열은 이 줄에 나타나지 않았다.

## 확인 결과 (2차 리뷰 반영 후, 2026-11-11) — 미완료
- surefire 50개 통과(실패·오류·skip 0): 13/16/2/4/10 + HjAuthFailurePolicyTest 5.
- `sh ./mvnw -B -ntp -V verify`를 3회 실행했고 모두 BUILD FAILURE였다. 원인은 테스트 MySQL 접속 불가다(`NoRouteToHostException`, SQLState 08S01).
  - 1회차: HjAuthEncodedPathFailureIT 1, HjSessionAuthenticationIT 12는 통과했다. 이후 HjMysqlFoundationIT·MovieApiIT 실행 중 연결이 끊겨 실패했다.
  - 2·3회차: 4개 IT 모두 컨텍스트 로드 단계의 DB 연결 실패다. 변경하지 않은 baseline MovieApiIT도 같다.
- 재시도(2026-11-11 03:00Z 이후) 2회도 BUILD FAILURE였다.
  - 4회차: 위 1회차와 같은 양상이다. 인증 IT 12+1은 통과했다. HjMysqlFoundationIT 첫 테스트 중 서버 연결이 끊겼고 오류가 EOF → Connection refused → NoRouteToHost 순서로 바뀌었다. DB 서버/호스트가 사라졌다는 뜻이다.
  - 이후 조치: HjAuthEncodedPathFailureIT는 spy bean 전용 컨텍스트를 쓰므로 `@DirtiesContext(AFTER_CLASS)`로 클래스 종료 후 연결 풀을 닫게 했다. 뒤따르는 IT 실행 중 유지되는 연결 풀은 1차 통과 때와 같은 3개가 된다.
  - 5회차: 4개 IT 22개 모두 컨텍스트 로드 단계의 DB 연결 실패다(NoRouteToHost, SQLState 08S01).
- DB 접속이 복구된 뒤 verify를 다시 실행해 failsafe 22개(4/5/12/1) 통과를 확인해야 한다. → 아래 6회차에서 확인했다.

## 확인 결과 (2차 리뷰 반영 후 재검증, 2026-11-11) — 완료
- `sh ./mvnw -B -ntp -V verify`(6회차): **BUILD SUCCESS**, 종료 코드 0, Total time 03:27 min, Finished at 2026-11-11T03:34:10Z. Apache Maven 3.9.11, Java 17.0.17(Eclipse Adoptium).
- surefire 50개 통과(실패·오류·skip 0): MovieControllerTest 13, MovieIdParserTest 16, MovieServiceTest 2, HjFoundationPolicyTest 4, HjMemberAuthenticationPolicyTest 10, HjAuthFailurePolicyTest 5.
- failsafe 22개 통과(실패·오류·skip 0): MovieApiIT 4, HjMysqlFoundationIT 5, HjSessionAuthenticationIT 12, HjAuthEncodedPathFailureIT 1. 실제 MySQL에 연결해 통과했다.
- 코드 변경 없이 재실행만 했다. 5회차까지의 실패 원인은 테스트 DB 접속 불가였다.

## 한계
- 기존 영화 API의 장애 로그(`ApiExceptionHandler`의 `Unhandled exception`)는 HJ-002 baseline 동작이라 그대로 두었다. 이 로그는 예외 원문과 스택을 출력하며, `MovieControllerTest`가 주입한 가짜 JDBC 문자열이 verify 출력에 나타난다. 로그 허용 목록을 전 경로로 확대하는 일은 후속 결정이 필요하다.
- 요청 제한은 단일 인스턴스 메모리 기준이다(위 결정 참조).
- 유휴 만료 판정은 Spring Session이 실제 시스템 시각으로 한다. 그래서 테스트는 DB의 마지막 접근 시각을 옮겨 검증했다. 정확히 30분 경계를 ms 단위로 맞춘 검증은 아니다.
- 절대 만료는 다음 요청 시점에 적용된다. 요청이 없을 때는 유휴 만료(30분)와 Spring Session 정리 작업(매분)이 행을 삭제한다.
- 설계의 MySQL 8.4 LTS가 아니라 기존 8.0.40 서버로 검증했다.
- Windows 경로(`mvnw.cmd`, `scripts/test-mysql.ps1`)는 실행하지 않았다.
