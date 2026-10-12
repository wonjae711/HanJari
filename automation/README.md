# HanJari 로컬 제어 프로그램 — 첫 버전

Python 3.11 이상, 추가 패키지 없음. 시작 명령을 실행한 동안에만 작동합니다.
현재 UI는 터미널입니다. 기본 모드는 mock이며 모델 사용량을 소비하지 않습니다.

## 프로젝트 연결 상태

- 원격 저장소: https://github.com/wonjae711/HanJari (사용자 실행으로 origin 등록 완료, 2026-10-10 재확인)
- Notion 업무 DB: https://app.notion.com/p/9f239dfc79d34d9695b79a593d23b6e2
- HJ-001 기획 카드: https://app.notion.com/p/3f5d0420a26281c280b9d349ca4596e5
- 연결 식별자는 integrations.json에 기록합니다. 토큰은 저장하지 않습니다.
- Notion DB와 카드는 생성됐지만 로컬 프로그램의 자동 동기화는 아직 없습니다. 현재는 대화의 Notion 커넥터로 관리합니다.
- GitHub는 아직 최초 커밋/푸시 전입니다. 기본 브랜치 커밋과 기능 브랜치가 준비된 뒤 실제 개발을 시작해야 합니다.
- 새 환경에서는 config.example.json을 config.json으로 복사하고 로컬 경로를 지정하세요. 개인 설정은 Git에서 제외됩니다.

초기 제한된 실행 환경에서 .git/config 쓰기가 거부됐으나 사용자 PowerShell에서 해결됐습니다. 새 환경에서는 다음 명령으로 origin을 등록합니다.
다른 origin이 있으면 덮어쓰지 않고 중단합니다. 커밋/푸시/병합은 수행하지 않습니다.

```powershell
cd C:\Users\dnjsw\projects\HanJari\automation
python connect_repository.py
```

성공 결과는 `.runtime-repository/verified.json`에 기록됩니다.

## 구현 범위

- 기능 하나의 기획 → 사전 리뷰 → 사용자 기획 승인 → Claude 구현 → Codex 리뷰 → Codex 최종 검증 → PR 준비 대기.
- 최초 구현 이후 수정 최대 3회, 관리 Codex의 재계획 1회, 이후 실패는 사용자 판단 대기.
- 수동 활성화, 단계 경계에서 일시정지/재개, 아이디어 기록, 요구사항 버전 변경.
- 상태 파일의 원자적 저장과 실행 잠금. 프로세스 비정상 종료 시 자동 재실행하지 않습니다.
- Claude 작업 전 usage.json의 5h 80% / 7d 70% 검사. 파일 갱신 자체는 하지 않습니다.
- 단계별 입력/출력 로그, 관련 파일 안내를 모은 리뷰 자료 생성.
- 실제 CLI 어댑터 포함. Claude Pro의 읽기 전용 호출/JSON 응답은 확인했습니다. 전체 개발 흐름은 아직 미검증입니다.

## 아직 연결하지 않은 기능

- Notion DB 생성/동기화, GitHub Draft PR 생성/업로드, Codex 앱 알림.
- `pr_ready`는 로컬 검증 흐름 종료를 뜻하며 실제 PR이 생겼다는 뜻이 아닙니다.
- Git 저장소 clone, 브랜치 생성, 커밋/푸시/병합/배포는 자동 수행하지 않습니다.
- 테스트 근거는 에이전트 보고이며 별도 CI 결과와의 자동 대조는 미구현입니다.
- 일시정지는 실행 중인 단계를 즉시 중단하지 않습니다. 긴 작업은 설정된 타임아웃에 종료됩니다.
- 터미널 출력과 state.json의 attention 이벤트만 제공합니다. 이를 Codex 알림이라고 표시하지 않습니다.

## 모의 실행

PowerShell에서 이 폴더로 이동합니다.

```powershell
python controller.py init --task movie-detail.task.json --mode mock
python controller.py run --activate
python controller.py status
python controller.py approve-plan
python controller.py resume --activate
python controller.py review-pack
```

상태/로그는 `.runtime`에 저장됩니다. mock 자료에는 실제 검증이 아님이 표시됩니다.
다른 작업에는 모든 명령에 동일한 `--state-dir <경로>`를 지정합니다.

## 사람의 개입

별도 PowerShell 창에서 실행할 수 있습니다.

```powershell
python controller.py pause
python controller.py note --text '아이디어: 관람등급 설명도 보여주면 어떨까?'
python controller.py status
```

현재 단계가 끝나고 running이 null인지 확인한 뒤 요구사항을 변경합니다.

```powershell
python controller.py revise --task updated.task.json
python controller.py run --activate
```

`note`는 아이디어만 기록하며 요구사항을 바꾸지 않습니다. `revise`는 새 버전으로 기획을 다시 시작합니다.
이전 검증 기록은 보존되지만 새 버전의 근거로 자동 승계되지 않습니다.
프로그램의 상태/제어 파일을 AI 수정 대상에 포함하지 마세요.

## 실제 호출 준비

1. config.json의 project_dir를 실제 Git 저장소로 지정합니다. main/master가 아닌 기능 브랜치를 준비합니다.
2. 사용자 터미널에서 Claude Code와 Codex 구독 로그인이 되는지 확인합니다.
3. API 키 환경변수가 설정된 경우 프로그램이 실행을 보류합니다. 별도의 구독 전용 터미널을 사용하세요.
4. Claude 테스트 실행에 필요한 최소 도구 권한만 claude_allowed_tools에 명시합니다. 예: Maven wrapper가 확정된 후 해당 테스트 명령만 허용.
5. usage.json을 갱신합니다. 수정 시각 5분 이내의 값만 기본적으로 사용합니다. 무인 호출에서 statusline 갱신은 아직 미검증입니다.
6. mock 작업과 다른 상태 폴더로 init --mode live를 실행합니다. 이후 run --activate를 실행하면 실제 사용량이 발생합니다.

Codex는 기본 read-only로 실행됩니다. 이 권한으로 테스트가 불가능하면 blocked로 대기하게 설계했습니다.
실제 테스트 실행 범위는 향후 프로젝트 환경에 맞게 검증 후 조정해야 합니다.
Claude는 acceptEdits를 사용하되 권한 우회 옵션을 사용하지 않습니다. 권한 거부는 로그와 함께 대기합니다.
명령 인자는 shell=False로 전달하며 모델과 추론 강도는 사용자 도구의 기존 설정을 사용합니다.

## 오류 복구

정상 오류는 상태를 보존하고 active=false로 전환합니다. 원인을 해결한 후 resume --activate를 사용합니다.
프로세스 강제 종료나 전원 종료로 잠금이 남았다면 잠금 파일에 기록된 PID와 자식 프로세스가 모두 종료됐는지 먼저 확인하세요.
확인 후 runner.lock/state.lock을 제거하고 state.json의 running을 null, active를 false로 바꿉니다.
부분 변경 코드와 로그를 검토한 뒤 재개합니다. 자동 잠금 제거는 중복 실행 위험 때문에 제공하지 않습니다.
OneDrive 등 동기화 폴더나 여러 PC에서 같은 상태 폴더를 동시에 사용하지 마세요.

## 테스트

```powershell
python -m unittest -v
```

임계값, 오래된 사용량, 중복 실행, 수정 횟수, 일시정지, 버전 변경, 실행 실패 및 승인 대기를 모의 검증합니다.

## 실제 CLI 연결 시험 (2026-10-10)

추가 검증: 사용자 PowerShell에서 Codex 실행 및 파일 읽기가 성공했습니다.
응답에 붙은 끝의 CRLF를 검증기가 불일치로 처리했던 문제를 수정했습니다.
끝의 CR/LF만 정규화하며 공백이나 실제 내용 변경은 허용하지 않습니다.
저장된 response.json으로 재검증하여 codex/verified.json을 기록했습니다. 추가 모델 호출은 없었습니다.
자동 테스트 13개 통과. 로그의 SessionStart hook 실패는 별도 확인 사항이며 이번 파일 읽기는 성공했습니다.
아래의 접근 거부 기록은 에이전트의 제한된 실행 환경에서 발생한 이전 결과입니다.

Codex 앱 내부와 일반 PowerShell의 PATH가 다를 수 있습니다. `codex` 명령을 PATH에서 찾지
못하면 `%LOCALAPPDATA%\OpenAI\Codex\bin\*\codex.exe`에서 최신 수정 시각의 실행 파일을 찾습니다.
전역 PATH는 변경하지 않습니다. 특정 설치를 선택하려면 config.json의 codex_command에 절대 경로를 지정하세요.

Claude Code: Pro 인증, fixture.txt 실제 읽기, 구조화 JSON 검증 성공.
검증 후 콘솔 완료 메시지의 CP949 인코딩 오류가 발생하여 해당 문자를 교체했습니다.
검증 결과는 `.runtime-cli-smoke/claude/verified.json`에 저장되어 있습니다.

Codex: 기존 설정 경로를 자식 프로세스에 CODEX_HOME으로 지정하면 로그인 상태 명령은 성공했습니다.
그러나 에이전트의 제한된 실행 환경에서는 tmp/arg0 및 logs_2.sqlite 접근 거부로 실제 호출은 실패했습니다.
사용자의 Codex 앱이 작동하지 않는다는 의미는 아닙니다. 권한 우회 옵션은 사용하지 않았습니다.
사용자 PowerShell에서 다음 명령을 실행해 호스트 환경의 호출을 확인할 수 있습니다.

```powershell
Set-Location 'C:\Users\dnjsw\projects\HanJari\automation'
python smoke_cli.py codex --activate
```

이 명령은 실제 구독 사용량을 소비할 수 있으며 작은 로컬 fixture 읽기만 요청합니다.
성공하면 `.runtime-cli-smoke/codex/verified.json`에 결과가 저장됩니다.
실패하면 같은 폴더의 stderr.log를 확인합니다. 인증 파일이나 토큰을 공유하지 마세요.
Claude를 재검증하려면 동일하게 `python smoke_cli.py claude --activate`를 사용합니다.
사용량 파일이 오래됐다면 Claude 호출을 보류합니다. 이 시험은 statusline 갱신 기능을 검증하지 않습니다.

## HJ-001 최종 검증 경로

기존 작업이 비활성 `final_test` 단계일 때 프로젝트 루트에서 `powershell -NoProfile -File .\automation\final-check.ps1`을 실행한다. 테스트 계정 비밀번호를 숨김 입력받고, 정해진 Maven verify를 실행한 뒤 Codex가 새 검증 근거를 읽기 전용으로 판정한다. 성공 시 `pr_ready`에서 멈춘다. 이미 완료된 상태를 초기화하거나 재실행하지 않는다.

`final_verify.py`는 필수 클래스 4개, 새 리포트, 실패·오류·건너뜀 0, 종료 코드 0, 소스 불변을 확인한다. 검증 근거는 해당 attempt의 verification.json이다. DB 환경변수는 Codex 자식 호출 전에 제외한다.

Windows elevated 초기화가 런타임 파일 잠금으로 실패한 PC에서는 로컬 config.json에 `"codex_windows_sandbox": "unelevated"`를 지정할 수 있다. 이 값은 Codex 자식 호출에만 적용되며 read-only 정책을 유지한다. 생략하면 기존 Codex 설정을 사용한다. 실제 파일 읽기와 독립 리뷰 재개를 확인했다.

현재 검증 정책은 HJ-001 전용이다. 일반 run/resume도 final_test에서 Maven verify → 최신 전체 리포트·소스 불변 확인 → Codex 판정을 수행한다. 구현 단계도 같은 필수 클래스 정책을 사용한다. 다른 업무의 live implement/final_test는 검증 정책을 정의하기 전 중단한다. Windows 알림과 Notion 직접 동기화 어댑터를 구현했다. 실제 Notion 연결은 토큰 설정 후 검증해야 한다. [설정 안내](INTEGRATIONS.md)를 참고한다.

반복 실행 한도, 명시적 중단 해제, 완료 상태를 보존하는 재검증은 [반복 방지 안내](REPEAT-GUARD.md)를 참고한다. HJ-001은 재검증까지 완료되어 현재 사용자 리뷰 대기 상태다.


## 일반 실행 흐름의 검증 연결

- `controller.py run/resume --activate`에서 HJ-001은 review 통과 후 결정된 Maven 검증 경로로 자동 연결된다. test DB 환경변수는 실행 전에 준비해야 하며, 누락 시 final_test에서 멈춘다. 완료된 pilot/recheck 상태는 초기화하지 않는다.
- Claude 구현 결과의 pass는 필수 클래스 네 개의 최신 XML, 최소 테스트 수, 실패·오류·건너뜀 0을 요구한다. malformed/누락 속성/일부 리포트만 있는 경우 거부한다. 이 단계의 XML 검사는 Maven 종료 코드나 테스트 이후 소스 변경까지 증명하지 않는다. 최종 실행기가 Maven을 직접 실행해 종료 코드와 소스 불변을 추가 확인한다.
- 최종 실행 오류는 비활성 final_test로 정지한다. Codex가 changes를 반환하면 일반 실행기는 기존 수정 3회·관리 재계획 정책을 따른다. final-check.ps1 전용 재검증 경로는 한 단계 후 정지한다.
- 회귀 테스트 38개 통과(외부 LLM·DB 모의 처리 포함). 이 연결 변경에 대한 실제 LLM/DB 재실행은 하지 않았다.


## Windows 알림·Notion 직접 동기화

추가 LLM 호출이나 주기 확인 없이 수동 활성화된 작업의 상태 변경을 기록한다. 로컬 config.json의 integration_delivery=true와 integrations.json의 provider/sync_mode로 켠다. 전송 실패는 업무 결과와 분리하고 전송만 제한적으로 재시도한다. 실제 배너 표시와 Notion 토큰·페이지 권한은 별도 연결 시험이 필요하다. [설정과 복구 절차](INTEGRATIONS.md)를 따른다.
