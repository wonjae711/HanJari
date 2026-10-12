# 한자리 — 영화 상세 조회 API 시험 기능

자동화 흐름을 검증하기 위한 HJ-001 파일럿이다. 최종 제품 요구사항은 별도로 확정한다.

Java 17 / Spring Boot 4.1.1 / Maven 3.9.11 / MySQL을 사용한다.

## API

`GET /api/v1/movies/{movieId}`

- 200: `id`, `title`, `runtimeMinutes`, `ageRating` 네 필드.
- 400: 부호·선행 0·0·음수·비숫자·Long 범위 초과 ID (`INVALID_MOVIE_ID`).
- 404: 유효한 ID에 해당하는 영화 없음 (`MOVIE_NOT_FOUND`).
- 500: DB 장애 등 내부 오류 (`INTERNAL_ERROR`). 응답에 내부 예외를 노출하지 않는다.

## 로컬 실행

[MySQL 준비](automation/MYSQL-SETUP.md)를 따라 전용 개발·테스트 스키마와 계정을 준비한다.
PowerShell에서 개발 계정 비밀번호를 숨김 입력하고 애플리케이션을 실행한다.

```powershell
$env:HANJARI_DB_URL = 'jdbc:mysql://localhost:3306/hanjari_dev?allowPublicKeyRetrieval=true&useSSL=false&characterEncoding=UTF-8'
$env:HANJARI_DB_USERNAME = 'hanjari_dev'
$devCredential = Get-Credential -UserName hanjari_dev -Message 'MySQL development account password'
$env:HANJARI_DB_PASSWORD = $devCredential.GetNetworkCredential().Password
try {
    .\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=dev'
} finally {
    Remove-Item Env:HANJARI_DB_PASSWORD -ErrorAction SilentlyContinue
    $devCredential = $null
}
```

이 URL은 로컬 시험용이다. `dev` 프로필에서만 샘플 데이터를 넣는다. 새 DB에서는 샘플 ID가 1이며, 다른 터미널에서 `curl.exe http://localhost:8080/api/v1/movies/1`로 조회할 수 있다. Flyway로 스키마를 관리하고 Hibernate는 validate만 수행한다.

## 테스트

```powershell
# DB 없는 단위·WebMvc 테스트
.\mvnw.cmd -B test

# 실제 MySQL을 포함하는 전체 검증: 테스트 계정 암호를 숨김 입력
powershell -NoProfile -File .\automation\test-mysql.ps1
```

전체 검증은 `hanjari_test.movies` 데이터를 비우고 시험 데이터를 생성한다. 테스트 시작 전에 접속 스키마가 `hanjari_test`인지 확인한다. DB 설정이 없으면 통합 테스트는 실패하며 건너뛰지 않는다.

## 리뷰

[기획 계약](automation/HJ-001-plan.md), [검증 기록과 파일 읽는 순서](automation/HJ-001-validation.md)를 참고한다. 테스트 리포트는 `target/surefire-reports`, `target/failsafe-reports`에 생성된다.

초기 권한 문제 복구 후 Claude의 구현·테스트와 Codex 리뷰가 통과했다. 2026-10-12에는 최종 검증 실행기의 Maven 35개 테스트와 Codex 판정이 다시 통과했다. 일반 제어기의 최종 검증 연결과 Windows 알림·Notion 직접 동기화 어댑터를 추가했다. Windows 알림 센터 도착을 확인했으며 배너 표시와 실제 Notion 토큰 연결은 추가 확인이 필요하다. Codex 앱 푸시는 사용하지 않는다. Draft PR 이후 병합은 사람이 판단한다.
