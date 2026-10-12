# HJ-001 시험 진행 기록

2026-10-10 KST. 기획안 v1 사용자 승인 후 시험 실행.

- 기본 커밋: `5acb65e` (하네스 초기 상태). 기능 브랜치: `feature/HJ-001-movie-detail`.
- Claude가 영화 상세 API, MySQL 마이그레이션, 테스트 4개 클래스를 작성했다.
- Claude 비대화형 실행에서 Java/Maven 권한 요청이 거부되었다. 제어기는 이를 감지하고 정지했다. 자동화 전체 성공이 아니다. 수정 횟수는 소비하지 않았다.
- GPT가 사용자 승인된 Maven 실행으로 Wrapper 3.3.4 / Maven 3.9.11을 생성하고 `mvnw.cmd -B test`를 실행했다.
- 결과: 컴파일 성공, 테스트 31개 통과, 실패/오류/건너뜀 0개. MovieControllerTest 13개, MovieIdParserTest 16개, MovieServiceTest 2개. 결과 원본: `target/surefire-reports`.
- 초기 테스트는 미커밋 작업 트리에서 수행했다. 이후 기능 커밋은 `78d6c55`이며 아래 최신 검증은 해당 기능 소스의 해시를 기록했다.
- 사용자가 MySQL DB/계정을 설정하고 시험 스크립트를 실행했다. GPT가 생성된 Maven 리포트에서 전체 35개 통과를 확인했다: 단위·WebMvc 31개, 실제 MySQL 통합 4개, 실패/오류/건너뜀 모두 0.
- `automation/test-mysql.ps1`은 테스트 계정 비밀번호를 숨김 입력받아 `mvnw.cmd -B verify`를 실행한다. 비밀번호를 파일에 저장하지 않으며 종료 시 프로세스 환경변수를 복원한다. PowerShell 구문 검사는 통과했다. 이후 사용자 실행으로 실제 연결 검증도 통과했다.
- 실제 MySQL 통합 테스트는 2026-10-10 18:49 KST 실행 리포트로 확인했다. 마이그레이션·Hibernate 검증·저장 후 API 조회·한글 응답·조회 전후 DB 불변·400/404·최대 ID 처리가 통과했다. DB 장애 500은 WebMvc 모의 의존성 테스트에서 확인했다.
- GPT 코드 리뷰: 승인된 HJ-001 범위에서 병합을 막는 결함을 발견하지 못했다. 실제 서버 포트로 HTTP 요청을 보내는 검증, 부하 테스트, CI 실행은 미실행이다. 테스트는 MockMvc와 실제 MySQL 조합이다.
- 초기 하네스 실행은 권한 거부로 정지했다. 이후 복구 결과는 아래 최신 검증에 기록한다.

## 필요 시 수동 테스트 방법

사용자 PowerShell에서:

```powershell
cd C:\Users\dnjsw\projects\HanJari
powershell -NoProfile -File .\automation\test-mysql.ps1
```

`hanjari_test` 계정 비밀번호를 입력한다. 테스트는 `hanjari_test.movies`를 비우고 시험 데이터를 넣는다. 실행 전에 실제 접속 스키마를 확인하며 개발 DB에는 실행하지 않는다. DB 생성·계정 변경은 하지 않는다. 전체 성공은 `BUILD SUCCESS`와 failsafe 결과를 함께 확인한다.

## 기능 코드 읽는 순서

1. `src/main/java/com/hanjari/movie/MovieController.java`, `MovieIdParser.java`: 경로와 ID 검증.
2. `src/main/java/com/hanjari/movie/MovieService.java`: 읽기 전용 트랜잭션과 없는 영화 처리.
3. `src/main/java/com/hanjari/movie/MovieRepository.java`, `Movie.java`, `AgeRating.java`: DB 매핑.
4. `src/main/java/com/hanjari/movie/MovieDetailResponse.java`: 응답 4개 필드.
5. `src/main/java/com/hanjari/common/ApiExceptionHandler.java`: 400/404/500 응답.
6. `src/main/resources/db/migration/V1__create_movies.sql`: 테이블과 제약조건.
7. `src/test/java/com/hanjari/movie`: DB 없는 테스트 3개 클래스와 실제 DB 테스트 `MovieApiIT`.

## 최신 최종 검증 및 사용자 리뷰 대기

- Claude가 실제 `verify`를 실행해 31+4개 테스트에 성공했고, Codex 독립 리뷰도 pass를 반환했다.
- 최종 검증 전용 실행기가 Maven verify를 다시 실행하고 종료 코드 0, 필수 클래스 4개·총 35개 성공, 실패/오류/건너뜀 0을 확인했다.
- Codex 최종 판정 pass, 제어기 상태 `pr_ready`, `active=false`. 사람이 병합을 판단한다.
- 소스·빌드 파일 24개의 SHA-256과 테스트 XML 4개의 SHA-256을 `HJ-001-evidence.json`에 보존했다. 실행 당시 커밋은 `78d6c55`이며 후속 하네스/문서 커밋은 기능 소스를 변경하지 않는다.
- 검증 범위: MockMvc + 실제 MySQL. 별도 실행 서버 HTTP 테스트, 부하 테스트, CI는 미실행.
- 중간 복구와 수동 최종 실행을 거친 파일럿이다. 처음부터 끝까지 무인 실행한 결과는 아니다.
- 남은 하네스 한계: 일반 controller.py의 구현 단계 증거 검사는 일부 새 성공 리포트만으로 통과할 수 있다. 필수 전체 클래스·건너뜀·종료 코드·소스 동일성 검사는 이번 `final_verify.py` 경로가 담당한다. 다음 기능으로 확장하기 전 검증 정책을 통합해야 한다.

## 2026-10-12 재검증과 반복 방지

- 10:30:17 KST Maven verify 완료, 10:31:28 KST Codex 최종 pass. 총 35개 성공, 실패·오류·건너뜀 0. 최신 증거는 HJ-001-evidence.json이다.
- 기존 파일럿 상태를 보존하고 별도 재검증 상태에서 final_test를 정확히 1회 실행했다. pr_ready / active=false / running=null이며 기능 소스 24개와 보고서 4개의 해시를 대조했다.
- 연속 실패 3회, 동일 버전·단계 6회, 전체 20회 제한과 저장소 동시 실행 잠금, 사유를 기록하는 수동 중단 해제를 추가했다. 완료된 재검증은 재실행하지 않는다.
- 자동화 CLI 세션은 저장하지 않으며 실행 로그는 보존한다. 이전 채팅은 삭제하지 않았다. 상세 정책은 REPEAT-GUARD.md를 참고한다.
- 제어기 회귀 테스트 33개 통과. 실제 성공 실행에서는 중단 조건이 발동하지 않았으며 실패·한도·재개 차단은 회귀 테스트로 확인했다.
- 다음 단계는 사용자 기능 리뷰다. 일반 최종 검증 경로 통합, 구현 단계 증거 검사 강화, Notion 자동 동기화와 외부 알림은 후속 과제다.
