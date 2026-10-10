## 개발 전 기획안 v1
**상태: 사용자 검토 대기. 개발 및 DB 변경은 아직 실행하지 않음.**
### 개발 환경 선택
- Java 17, Spring Boot 4.1.1, Maven 3.9.11을 사용한다. 현재 설치·캐시된 환경을 활용한다. Maven Wrapper로 빌드 버전을 고정한다.
- DB는 MySQL(InnoDB)을 선택한다. 기존 MySQL 8.0.40 서버를 첫 로컬 시험에 활용한다. PostgreSQL 대비 우월하다는 판단이 아니라, 현재 실행 중인 서버와 이후 행 잠금 비교 실험에 맞춘 선택이다.
- 기존 다른 프로젝트 DB를 사용하지 않는다. 전용 개발 스키마 `hanjari_dev`, 테스트 스키마 `hanjari_test`와 전용 계정을 준비한다. 접속 정보는 환경변수로 받으며 기획 문서·Git에 암호를 넣지 않는다.
- 테스트 계정은 테스트 스키마에만 접근하도록 구성한다. 테스트 전 스키마 이름을 확인하고 개발 DB와 같으면 실행을 거부한다.
- JPA/Hibernate, MySQL Connector/J, Flyway를 사용한다. DDL은 버전 관리한 마이그레이션으로 적용하고 Hibernate는 스키마 검증만 수행한다. 관련 의존성의 실제 조합은 빌드 단계에서 검증한다.
- Redis, Kafka, 프론트엔드, 별도 서버 설치는 이번 기능에 포함하지 않는다.
### API 계약
`GET /api/v1/movies/{movieId}`
- 인증 없는 공개 조회 API.
- movieId는 1부터 9223372036854775807까지의 십진 정수. 숫자 이외 문자, 부호, 선행 0, 0, 음수, 범위 초과는 400.
- 유효한 ID의 영화가 없으면 404. DB 접속 실패는 일반 서버 오류 500이며 404로 처리하지 않는다.
- 정상 응답: HTTP 200, application/json.
```json
{
  "id": 1,
  "title": "한자리 테스트 영화",
  "runtimeMinutes": 120,
  "ageRating": "AGE_12"
}
```
- 네 필드는 필수이며 null이 아니다. id와 runtimeMinutes는 JSON 정수, title과 ageRating은 문자열.
- 관람등급은 내부 코드 ALL, AGE_12, AGE_15, ADULT 중 하나. 법률상 관람 정책의 구현은 이번 범위에서 제외.
- 오류 본문: code와 message 문자열. 잘못된 ID는 INVALID_MOVIE_ID / 유효하지 않은 영화 ID입니다. 없는 영화는 MOVIE_NOT_FOUND / 영화를 찾을 수 없습니다. 서버 오류는 INTERNAL_ERROR / 요청을 처리하지 못했습니다.
- 오류에 SQL, 암호, 스택 추적을 노출하지 않는다.
### 최소 데이터 모델
`movies`: id(BIGINT, PK, 자동 증가), title(VARCHAR(200), NOT NULL), runtime_minutes(INT, NOT NULL, 양수), age_rating(VARCHAR(20), NOT NULL).
- 제목 중복은 허용한다. 현재 기능에 개봉일·포스터·줄거리·상영 회차는 추가하지 않는다.
- 개발 샘플 데이터는 명시적으로 활성화한 개발 프로필에서만 넣는다. 테스트 데이터는 테스트 스키마에 별도로 준비한다.
### 검증 가능한 완료 기준
1. 샘플 영화 조회 결과가 DB에 저장된 네 필드와 일치한다.
2. 유효하지만 없는 ID는 404와 MOVIE_NOT_FOUND를 반환한다.
3. abc, 0, -1, 01, +1, 정수 범위 초과 값은 400과 INVALID_MOVIE_ID를 반환한다. URL 전송 시 부호 인코딩을 고려한다.
4. ID 최댓값은 형식 오류가 아닌 유효한 조회로 처리한다. 데이터가 없으면 404.
5. 한글 제목이 손상되지 않는다. 응답은 엔티티 직접 직렬화 대신 DTO로 고정한다.
6. 정상 조회 전후 DB 데이터가 변경되지 않는다.
7. API 테스트는 200/400/404와 JSON 형식을 검증한다. 서버 오류 매핑은 모의 의존성 실패로 검증한다.
8. 실제 MySQL 통합 테스트에서 마이그레이션·저장·조회가 성공한다. DB가 없을 때 통합 테스트를 건너뛰고 전체 통과로 보고하지 않는다.
9. 사용자용 실행 명령, 테스트 명령·결과, 확인한 커밋과 관련 파일 읽는 순서를 제공한다.
### 구현·리뷰 단위
Controller → Service(읽기 전용 트랜잭션) → Repository → Movie 엔티티 / 응답 DTO → 공통 오류 처리 → 테스트 순으로 읽는다.
예상 파일명은 MovieController, MovieService, MovieRepository, Movie, MovieDetailResponse, ApiExceptionHandler이며 실제 리뷰에는 구현된 경로·메서드를 기재한다.
### 사전 검토에서 반영한 사항
- 형식이 틀린 ID와 존재하지 않는 ID를 구분했다.
- DB 장애를 영화 없음으로 숨기지 않도록 했다.
- 테스트용 DB를 개발 데이터와 분리하고 실 DB 테스트의 무음 건너뛰기를 금지했다.
- 등록·수정 API와 추가 화면은 후속 업무로 남겼다.
- 이 사전 검토는 현재 대화의 GPT가 수행했다. 별도의 Codex 리뷰 실행 결과로 표시하지 않는다.
### 개발 시작 전 준비
- 사용자 기획 승인
- 최초 기본 브랜치 커밋과 기능 브랜치 준비
- 전용 DB 스키마·계정 연결, 빌드·테스트 권한 확인
- 자동화가 개발 테스트를 실행할 수 있는지 확인
### 참고
[Spring Boot 공식 시스템 요구사항](https://docs.spring.io/spring-boot/system-requirements.html)
[MySQL InnoDB 잠금 조회](https://dev.mysql.com/doc/refman/8.0/en/innodb-locking-reads.html)

