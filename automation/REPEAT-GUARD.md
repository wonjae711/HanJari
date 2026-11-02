# 반복 방지와 HJ-001 재검증

## 중단 조건

- 동일 요구사항 버전·단계에서 blocked 또는 실행 오류가 3회 연속 발생하면 중단 상태를 고정한다. 한 번 실패해도 자동 재시도하지 않으며, 수동 재개로 누적된 실패도 센다.
- 동일 버전·단계는 최대 6회, 작업 전체는 최대 20회 시도한다. 한도 이후에는 다음 호출 전에 멈춘다.
- 설정은 config.json의 선택적 run_limits 항목으로 지정한다. 기본값은 max_attempts=20, max_stage_attempts=6, max_consecutive_failures=3이다.
- 카운터는 state.json에 저장한다. 프로세스 재시작, resume, 요구사항 수정으로 전체 한도나 중단 상태를 자동 해제하지 않는다.
- 기존 상태는 최초 신규 실행부터 카운터를 적용한다. 보완 이전의 실행 기록을 소급해 카운트하지 않는다.
- 기존 코드 수정 3회 후 관리 재계획 1회 제한, Claude 5시간 80%/주간 70% 사용량 제한, 단계 시간 제한은 유지한다.
- run()의 저장소 단위 잠금으로 서로 다른 state-dir의 동시 실행도 거부한다. 별도로 CLI를 직접 실행하는 프로그램까지 통제하지는 않는다.

자동화 호출에는 Claude --no-session-persistence / Codex --ephemeral을 사용한다. 프롬프트 첫 줄에는 업무·단계·시도 번호를 넣고 attempt 디렉터리의 입출력 로그는 보존한다. 기존 세션 삭제나 아카이브는 수행하지 않는다.

## 명시적 해제

원인을 해결한 후 아래 명령으로 이유를 남긴다. 자동으로 재개되지 않는다. 카운터 초기화 전 기록은 run_guard_history에 보존한다.

```powershell
python automation/controller.py reset-breaker --state-dir automation/.runtime-recheck --text "중단 원인과 해결 내용을 여기에 기록"
```

이후 사용자가 실행 명령으로 다시 활성화한다. 실행 중인 작업은 중단 조건 해제 대상으로 허용하지 않는다. 콘솔과 상태 이벤트에 중단 사유를 남기며, 외부 Codex 알림은 아직 연동되지 않았다.

## 영화 API를 수정하지 않는 재검증

```powershell
cd C:\Users\dnjsw\projects\HanJari
powershell -NoProfile -File .\automation\final-check.ps1 -Recheck
```

테스트 계정 hanjari_test 비밀번호를 숨김 입력한다. 비밀번호는 파일에 저장하지 않는다. 기존 .runtime-pilot 완료 기록을 보존하고 .runtime-recheck에서 final_test만 실행한다. Maven verify → 최신 필수 테스트 결과/소스 해시 확인 → 읽기 전용 Codex 판정 → 정지 순서다. 테스트 DB 영화 데이터는 기존 통합 테스트 방식대로 비우고 생성한다.

성공은 Stage: pr_ready / active: False이며, 실패 시 상태와 attempt 로그를 확인한다. 수정이 필요하다는 판정이 나와도 자동 구현을 시작하지 않는다. 완료된 재검증에 같은 명령을 다시 실행하면 거부한다. 추가 재검증은 별도 사용자 의사에 따라 준비해야 한다.
