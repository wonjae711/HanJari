# HJ-001 시험 진행 기록

2026-10-10 KST. 기획안 v1 사용자 승인 후 시험 실행.

- 기본 커밋: `5acb65e` (하네스 초기 상태). 기능 브랜치: `feature/HJ-001-movie-detail`.
- Claude가 영화 상세 API, MySQL 마이그레이션, 테스트 4개 클래스를 작성했다.
- Claude 비대화형 실행에서 Java/Maven 권한 요청이 거부되었다. 제어기는 이를 감지하고 정지했다. 자동화 전체 성공이 아니다. 수정 횟수는 소비하지 않았다.
- GPT가 사용자 승인된 Maven 실행으로 Wrapper 3.3.4 / Maven 3.9.11을 생성하고 `mvnw.cmd -B test`를 실행했다.
- 결과: 컴파일 성공, 테스트 31개 통과, 실패/오류/건너뜀 0개. MovieControllerTest 13개, MovieIdParserTest 16개, MovieServiceTest 2개. 결과 원본: `target/surefire-reports`.
- 검증 대상은 아직 커밋되지 않은 작업 트리다. 기본 커밋을 기능 검증 커밋으로 간주하면 안 된다.
- 사용자가 MySQL DB/계정을 설정하고 시험 스크립트를 실행했다. GPT가 생성된 Maven 리포트에서 전체 35개 통과를 확인했다: 단위·WebMvc 31개, 실제 MySQL 통합 4개, 실패/오류/건너뜀 모두 0.
- `automation/test-mysql.ps1`은 테스트 계정 비밀번호를 숨김 입력받아 `mvnw.cmd -B verify`를 실행한다. 비밀번호를 파일에 저장하지 않으며 종료 시 프로세스 환경변수를 복원한다. PowerShell 구문 검사는 통과했다. 실제 연결 실행은 대기 중이다.
- 실제 MySQL 통합 테스트는 2026-10-10 18:49 KST 실행 리포트로 확인했다. 마이그레이션·Hibernate 검증·저장 후 API 조회·한글 응답·조회 전후 DB 불변·400/404·최대 ID 처리가 통과했다. DB 장애 500은 WebMvc 모의 의존성 테스트에서 확인했다.
- GPT 코드 리뷰: 승인된 HJ-001 범위에서 병합을 막는 결함을 발견하지 못했다. 실제 서버 포트로 HTTP 요청을 보내는 검증, 부하 테스트, CI 실행은 미실행이다. 테스트는 MockMvc와 실제 MySQL 조합이다.
- 하네스 자동 실행은 권한 거부 이후 정지 상태다. 이번 검증을 Claude 자동 실행 성공 또는 독립 Codex CLI 리뷰 성공으로 표시하지 않는다. Draft PR은 수동 보완한 파일럿 결과를 사람이 리뷰하기 위해 준비한다.

## 다음 실행

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
