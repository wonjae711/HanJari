# 한자리 프로젝트 기획 자동화

## 지금 실행하면 하는 일

사용자 PowerShell에서 실행한다. 기존 영화 API 시험 상태는 보존하며 `.runtime-project`에 별도로 저장한다.

```powershell
cd C:\Users\dnjsw\projects\HanJari
powershell -NoProfile -File .\automation\start-planning.ps1 -Activate
```

1. 최초 실행에서 Notion 프로젝트 개요를 페이지 나눔과 하위 블록까지 읽어 원본 스냅샷을 만든다.
2. Codex 기획 담당이 전체 MVP 기획, 도메인·API·데이터 모델·예외 정책·인수 기준·작은 개발 업무들을 작성한다.
3. 별도 Codex 실행이 독립 검토한다. 수정 의견은 기획 담당에게 돌아간다. 최초 작성 뒤 최대 3회 수정, 관리 Codex 재계획 1회와 최종 수정·검토 뒤에도 실패하면 사용자 판단으로 멈춘다.
4. 기획·검토·관리 산출물은 로컬과 Notion에 기록한다. 합의하면 Windows 알림을 보내고 `awaiting_plan_approval`에서 정지한다. 사람이 판단할 질문이 있으면 `blocked` 또는 `needs_user`로 멈춘다.
5. 사람이 기획 전체를 읽고 표시된 해시를 승인한다. 승인만으로 개발을 실행하지 않는다.

두 역할은 같은 사용자 Codex 모델 설정을 별도 실행 문맥으로 사용한다. 역할 분리는 서로 다른 모델 공급자를 뜻하지 않는다. API 모델 과금 키를 사용하지 않고 기존 CLI 로그인을 사용한다. Codex 실행은 `--ephemeral`, `--sandbox read-only`, 구조화 응답 스키마를 사용한다. 파일과 Notion 쓰기는 제어기가 맡는다.

## 기획 확인과 승인

`automation/.runtime-project/artifacts`에 각 실행의 문서·의견·질문·기능 목록과 `approval.txt`가 생긴다. 검토한 버전과 같은 해시만 승인할 수 있다. 승인 시 Notion 원본이 변경됐으면 승인하지 않고 재기획을 요구한다.

```powershell
python automation/project.py status
python automation/project.py approve --hash 실제_검토한_해시
```

승인된 기능 정의는 `.runtime-project/handoff/*.task.json`에도 저장된다. 자동 실행에서 이 파일을 편집해 승인 범위를 변경할 수는 없다. 실제 인계는 승인된 상태의 구조화 산출물로 생성한다.

## 개발 인계와 단계별 사람 리뷰

승인된 기능 ID 하나를 선택한다. 출력된 상태 경로를 다음 명령에 사용한다.

```powershell
python automation/project.py handoff --feature HJ-002
python automation/controller.py run --state-dir automation/.runtime-project/features/r1/HJ-002 --activate
```

Claude Code 사용량 갱신과 MySQL 테스트용 환경변수 준비가 필요하다. 기존 5시간 80%·7일 70%·갱신 5분 기준을 유지한다. 인계된 기능은 Claude 개발·개발 테스트 → Codex 리뷰 → 제어기 Maven verify → Codex 최종 판정 → `pr_ready`로 진행한다. 기존 HJ-001 테스트와 이번/이전 기능의 승인된 필수 테스트를 함께 요구한다. 테스트 개수만으로 요구사항 충족을 판정하지 않고 독립 리뷰에서 시나리오를 대조한다.

기능 완료 후 사람이 코드와 검증 근거를 검토한다. 다음 기능으로 넘어갈 준비가 됐을 때만 다음을 실행한다.

```powershell
python automation/project.py accept-feature --feature HJ-002 --text "검토한 내용과 결론"
```

이 명령은 Git 병합을 수행하거나 병합됐다고 주장하지 않는다. 같은 체크아웃에 이전 기능 코드가 준비되어 있어야 한다. 다음 기능은 이전 기능들의 사용자 수락 후 순서대로 인계한다. 기존 상태의 중복 인계는 초기화하지 않는다.

현재 `pr_ready`는 PR용 검증 근거를 준비한 상태다. 제어기 자체의 Git commit/push 및 GitHub Draft PR 생성은 아직 자동화하지 않았다. 실제 PR 생성·업로드는 이 대화의 GitHub 작업 흐름으로 수행한다. PR 병합은 사용자 판단이다.

현재 자동 개발 실행기는 Java/Spring/Maven/MySQL 백엔드를 지원한다. 전체 기획에는 최소 화면을 포함할 수 있지만 프론트엔드 등 다른 도구가 필요한 업무는 후속 실행기 범위로 분리한다.

## 중간 개입

```powershell
python automation/controller.py pause --state-dir automation/.runtime-project
python automation/controller.py note --state-dir automation/.runtime-project --text "추가 아이디어"
python automation/project.py status
python automation/project.py revise --text "답변 또는 변경할 요구사항"
python automation/project.py run --activate
```

pause는 실행 중인 단계가 끝난 뒤 멈춘다. note는 아이디어를 기록하고 다음 모델 입력에 전달하지만 확정 범위나 기존 승인을 바꾸지 않는다. 기획 승인이 끝났다면 아이디어 반영에 revise가 필요하다. revise는 현재 원본을 다시 읽고 새 요구사항 버전으로 시작하며 이전 승인을 무효화한다. 실행 중인 개발이 있으면 먼저 그 기능 상태 경로로 pause하고 완료를 기다린다. 같은 계획에 속한 기획 변경과 개발 실행은 공유 잠금으로 겹치지 않게 한다.

기존 버전과 개발 상태는 보존된다. 새 버전 인계는 `features/r2/...`처럼 다른 경로를 사용한다. 계획 변경 전후 코드 차이는 리뷰해야 한다. 제어기는 코드를 자동으로 되돌리지 않는다.

전체 호출 20회·단계 6회·같은 단계 연속 실행 오류 3회 제한을 유지한다. 재실행/버전 변경만으로 전체 한도를 초기화하지 않는다. 원인을 확인한 뒤 기존 `reset-breaker --text` 명령으로 사유를 남긴다. 프로세스 강제 종료 후 남은 실행 잠금은 자동 삭제하지 않는다.

## Notion 기록 정책

- 기획 문서와 버전 이력: 기획 본문, 제안/확정 구분, 결정 근거, 인수 기준, 기능 분할.
- 자동화 방식과 실행 안내: 이 문서의 버전별 사본.
- 역할별 프롬프트 모음: 실제 기획/검토/관리 템플릿과 개발/리뷰/최종 판정 역할 지침.
- AI 활용 내역과 주요 결정: 역할, 버전, 판정, 근거, 수정 의견, 질문, 사용자 개입·승인·인계. 새 업무의 진행 상태도 이 기록에 남긴다.

현재 HJ-001은 기존 업무 DB 속성에도 동기화한다. 새 기획·개발 업무의 상태는 기록 페이지에 먼저 남기며, 업무 DB 카드 자동 생성은 아직 제공하지 않는다.

Notion 원본 페이지와 자동화 기록 페이지에 내부 연결의 콘텐츠 읽기·삽입·업데이트 권한을 부여한다. 토큰은 기존 사용자 DPAPI 암호화 파일을 읽는다. 자식 PowerShell 모듈 로드에 의존하지 않고 Windows DPAPI를 직접 사용한다. Windows 사용자/PC가 바뀌면 토큰을 다시 설정해야 한다.

로그 전체를 복사하지 않는다. 환경변수·비밀번호·인증 파일·원시 실행 로그는 수집하지 않으며, 선택한 산출물의 알려진 비밀 문자열은 추가 마스킹한다. 생성된 문장에 대한 마스킹이 모든 종류의 민감정보 탐지를 보장하지는 않는다.

문서는 버전별로 추가하며 기존 사람 메모를 덮어쓰지 않는다. 긴 문서는 분할하고 내용 해시로 중복 전송을 확인한다. 전송 후 응답을 잃으면 원격 기록을 먼저 확인한다. API/클라이언트 장애 전반에서 정확히 한 번 전송을 보장하지는 않는다. 같은 기록을 사람이 편집한 경우 자동 덮어쓰기 없이 전송을 멈춘다.

문서 전송은 업무 실행과 분리한다. 실패 시 원본 산출물은 로컬에 남고, 해당 기록 전송은 최대 3회 시도 후 멈춘다. 다음 단계 또는 아래 명령에서 재시도하며, 상시 폴링/LLM 재호출을 하지 않는다. 전송 문제는 콘솔과 Windows 알림으로 알린다.

```powershell
python automation/knowledge.py status
python automation/knowledge.py flush --activate
python automation/knowledge.py retry --reason "페이지 접근 권한 수정"
python automation/knowledge.py flush --activate
```

`knowledge.py`의 기본 대상은 `.runtime-project`다. 다른 기능은 해당 `--state-dir`를 지정한다. 기존 진행 상태 전송은 `delivery.py status/flush/retry`를 사용한다. mock 실행은 Notion에 게시하지 않는다.

## 검증 근거와 한계

회귀 테스트로 수정 루프, 승인 해시, 원본 변경, 사용자 질문, 인계 변조, 모의/실제 실행 구분, 사용자 수락, 실행 잠금, 페이지 나눔·분할·전송 실패·응답 유실 복구를 검증한다. 모의 테스트를 실제 기획 결과라고 기록하지 않는다. 실제 기획 에이전트의 산출물 품질과 종료까지의 왕복은 사용자 활성화 실행에서 확인한다.

참고: [Codex 비대화형 실행](https://learn.chatgpt.com/docs/non-interactive-mode), [Notion 블록 읽기](https://developers.notion.com/reference/get-block-children), [Notion 요청 제한](https://developers.notion.com/reference/request-limits).
