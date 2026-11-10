# HJ-002 기존 기반 보존과 전용 MySQL 검증

## 범위
기존 영화 상세 API는 재개발하지 않고 baseline(단위 31·통합 4)을 그대로 유지한다. 운영 코드·마이그레이션·Maven wrapper는 변경하지 않았고 테스트 기반만 추가했다.

## 파일 읽는 순서
1. `src/test/java/com/hanjari/testsupport/TestDatabaseGuard.java`: 환경변수 이름 3개만 조회한다. 누락·빈 값, 연결 실패, `hanjari_test` 이외 스키마는 예외로 실패한다. Flyway 실행 전에 독립 연결로 `SELECT DATABASE()`를 확인한다.
2. `src/test/java/com/hanjari/testsupport/TestDataCleaner.java`: 테스트 격리·정리 규칙을 담는다.
3. `src/test/java/com/hanjari/testsupport/ConcurrentRunner.java`: 시작 barrier와 유한 timeout을 둔다. 실패하면 abort hook을 호출하고 interrupt한 뒤, 모든 작업 스레드가 끝날 때까지 기다리고 나서 반환한다.
4. `src/test/java/com/hanjari/testsupport/IndependentTransactions.java`: 작업마다 독립 JDBC 연결과 트랜잭션을 연다. timeout이 나면 세션 KILL로 rollback하고, 세션 소멸을 확인한다.
5. `src/test/java/com/hanjari/foundation/HjFoundationPolicyTest.java`: DB 없이 실행하는 정책 단위 테스트 4개.
6. `src/test/java/com/hanjari/foundation/HjMysqlFoundationIT.java`: 실제 MySQL 통합 테스트 5개.
7. `src/test/java/com/hanjari/testsupport/UnresponsiveServer.java`: 연결은 받고 응답하지 않는 로컬 서버. 중단 경로의 응답 정지 회귀 검증에 쓴다.

## 테스트 격리·정리 규칙 (후속 FK 도입 대비)
- 각 통합 테스트는 **시작 전에** `TestDataCleaner.cleanAll`로 정리한다.
- `TABLES_CHILD_FIRST`는 FK 자식 → 부모 순서로 유지한다. 새 테이블을 만드는 마이그레이션은 같은 변경에서 이 목록에 등록해야 한다. 등록하지 않으면 `HjMysqlFoundationIT.cleanupRuleCoversEveryTableAndPreservesMigrationHistory`가 실패한다.
- 데이터 정리는 `DELETE`만 사용한다. FK 검사도 끄지 않는다. `flyway_schema_history`는 보존한다.
- 동시성 테스트는 `IndependentTransactions.run`을 사용한다. 판정은 실행기 반환값이나 HTTP 성공 수가 아니라 종료 후 DB 조회로 한다. 임의 sleep은 쓰지 않는다.

## timeout·중단 보장 (`IndependentTransactions`)
1. commit에 들어가지 않은 작업을 ABORTED로 표시한다. 그 작업은 이후 commit하지 않는다.
2. 별도 관리 연결에서 각 작업 세션에 `KILL <connection_id>`를 실행한다. 잠금 대기와 `SLEEP`처럼 interrupt로 멈추지 않는 JDBC 호출도 끝나고, 서버가 미커밋 트랜잭션을 rollback한다. 관리 연결 생성과 KILL 전체는 별도 스레드에서 `AbortPolicy.killTimeout`(기본 5초) 안에 끝나야 한다. 관리 연결 자체에도 connectTimeout·socketTimeout·`setNetworkTimeout`(3초)을 건다.
3. KILL의 성공·실패·timeout과 무관하게(finally) 클라이언트 연결을 `Connection.abort`로 닫는다. 실행기는 abort hook을 `killTimeout + 2초` 상한의 별도 스레드에서 실행하므로 hook이 멈춰도 다음 단계로 넘어간다. 그 뒤 모든 스레드 종료를 최대 10초 기다린다. 이 안에 끝나지 않으면 "test isolation is not guaranteed" AssertionError로 실패한다.
4. 반환 전에 `information_schema.processlist`에서 작업 세션이 사라졌는지 `sessionCheckTimeout`(기본 10초) 안에 확인한다. 세션이 남아 있거나 관리 연결 무응답으로 확인할 수 없으면 격리 실패로 처리한다.
5. 따라서 실패 경로는 서버·네트워크가 응답하지 않아도 `timeout + AbortPolicy.worstCaseAbortDuration()`(기본 timeout + 27초) 안에 반환한다.
6. 중단 결정 전에 이미 commit을 시작한 작업은 그 commit이 끝날 수 있다. 중단 결정 이후에는 commit이 일어나지 않는다.
7. `innodb_lock_wait_timeout`은 전체 timeout보다 1초 길게 둔다. 실행기 timeout이 먼저 적용되고, 이 값은 KILL이 실패할 때를 대비한 DB 쪽 유한 상한이다.
8. 남는 한계: 작업 스레드가 연결 handshake에서 멈춘 경우(연결 객체가 아직 없음)에는 abort할 대상이 없다. 이때는 grace 후 격리 실패로 보고하며, 응답 없는 소켓을 붙든 daemon 스레드가 남을 수 있다.

### 응답 정지 회귀 검증
- `HjFoundationPolicyTest`(DB 없음): interrupt도 무시하는 abort hook에서도 실행기가 timeout + abortTimeout + grace 안에 반환하고 "abort hook did not finish within"을 suppressed로 남기는지 확인한다. 또 handshake를 보내지 않는 로컬 서버(`UnresponsiveServer`)에 작업 연결 2개가 실제로 붙어 멈춘 상태에서 `IndependentTransactions.run`이 유한 시간 안에 격리 실패를 보고하는지 확인한다(비밀번호 비노출 포함).
- `HjMysqlFoundationIT`(실DB): 작업은 실제 MySQL에서 행 잠금·SLEEP으로 멈추고, 관리 연결만 무응답 서버로 향하게 한다(관리 연결에 timeout을 일부러 걸지 않는다). 유한 시간 안에 반환, "KILL of transaction sessions did not finish within" 기록, 클라이언트 연결 2개 모두 closed, 격리 실패 보고를 확인한다. 이후 실제 관리 연결로 남은 세션을 KILL하고 commit된 변경이 없는지 DB로 확인한다.

## 실행
```bash
sh ./mvnw -B -ntp -V verify   # 기존 wrapper(Linux). 실행 전 HANJARI_TEST_DB_URL / _USERNAME / _PASSWORD 설정 필요
```
Windows에서는 기존 `scripts/test-mysql.ps1`을 그대로 사용한다.

## 빈 스키마 최초 적용 검증 (AC3)
`firstApplyOnEmptyDedicatedSchemaThenRerunIsNoOp`는 다음 순서로 실행한다.
1. 전용 스키마를 다시 확인한다.
2. Flyway `clean`으로 `hanjari_test`를 비운다. DB를 새로 만들거나 삭제하지는 않는다.
3. 테이블이 0개인지 확인한다.
4. 최초 `migrate`를 실행한다. 확인 항목: 실행 건수 = 적용 건수 > 0, initialSchemaVersion 없음, V1 이력 성공.
5. 재실행 2회가 모두 0건인지 확인한다. 이력 행 수는 그대로여야 한다.

**이 단계에는 테스트 계정의 `hanjari_test` 스키마 DROP 권한이 필요하다.** 권한이 없으면 이 테스트는 skip하거나 축소하지 않고 실패한다(직전 실행에서 MySQL 1142로 실패한 기록이 있다).

## 확인 결과 (2026-11-10, Linux 컨테이너, 기존 wrapper `sh ./mvnw -B -ntp -V verify`)
- **BUILD SUCCESS**, 종료 코드 0. Total time 02:21 min, Finished at 2026-11-10T05:19:03Z.
- surefire 35개(기존 31 + HjFoundationPolicyTest 4) 통과. 실패·오류·skip 0.
- failsafe 9개(MovieApiIT 4 + HjMysqlFoundationIT 5) 통과. 실패·오류·skip 0.
- 빈 스키마 최초 적용(실DB 로그)은 다음과 같다.
  - 사전 상태 tables=[flyway_schema_history, movies]. Flyway clean으로 `hanjari_test`를 비우고 테이블 0개를 확인했다.
  - 최초 적용: executed=1, initialSchemaVersion=null, targetSchemaVersion=1, tables=[flyway_schema_history, movies].
  - 재실행: executed=0/0, historyRows=1.
- timeout 중단 실DB 검증 결과는 다음과 같다.
  - 2초 timeout 뒤 2140ms에 반환했다(wrapper 실행 기준).
  - KILL된 2개 세션이 processlist에서 사라졌다.
  - 갱신 행 값(104)이 유지됐고 삽입 표식 행은 0건이었다.
  - 행 잠금도 남지 않았다.
- 관리 연결 무응답 실DB 검증: `[HJ-002] unresponsive admin abort: elapsed=4006ms, clientConnectionsClosed=2, runtimeAfter=104`. 2초 timeout + KILL 상한 1초 + 세션 확인 상한 1초 안에서 격리 실패를 보고했고, 정리 후 commit된 변경은 없었다.
- 실행 환경은 다음과 같다.
  - 기존 Maven wrapper(`.mvn/wrapper/maven-wrapper.properties`: wrapperVersion 3.3.4, distributionType only-script, apache-maven 3.9.11)를 변경 없이 실제 실행했다. wrapper가 MAVEN_HOME을 찾지 못해 지정 배포본 3.9.11을 내려받아 `/scratch/.m2/wrapper/dists/apache-maven-3.9.11/...`에 설치한 뒤 빌드했다.
  - `-V` 출력: `Apache Maven 3.9.11 (3e54c93a704957b63ee3494413a2b544fd3d825b)`, Maven home = wrapper 배포 경로. 지정 버전과 실행 버전이 3.9.11로 일치한다. 이 실행이 AC4의 wrapper 실제 빌드 호환성 증거다. 제어기도 같은 명령으로 최종 재검증한다(2026-11-10 사용자 결정).
  - Windows `mvnw.cmd`와 `scripts/test-mysql.ps1` 경로는 이번에 실행하지 않았다.
  - Java 17.0.17, Spring Boot 4.1.1, Hibernate 7.4.5.Final, Flyway 12.4.0(flyway-mysql 12.4.0), MySQL Connector/J 9.7.0.
  - MySQL 서버 8.0.40.

## 사용자 준비 사항
- 빈 스키마 최초 적용 검증에는 전용 테스트 계정의 `hanjari_test` 스키마 한정 DROP 권한이 필요하다. 이번 실행에서는 권한이 준비되어 있었다. DB·계정 변경은 이 업무에서 수행하지 않았다.

## 한계
- 설계 제안의 MySQL 8.4 LTS가 아니라 기존 8.0.40 서버로 검증했다. 통합 테스트는 8.0.16 이상(CHECK 강제)만 요구한다.
