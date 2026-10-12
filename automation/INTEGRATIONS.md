# 알림과 Notion 진행 상태 동기화

사용자가 활성화한 제어기 실행에만 연결한다. 상시 감시, 예약 작업, 추가 LLM 호출은 없다.

## 동작

- 상태 변경과 전송할 이벤트를 같은 state.json에 원자적으로 저장한다.
- integration_delivery=true이면 단계 시작/종료·중단 시 대기 이벤트를 전송한다. 기획 승인 대기, PR 준비, 사용자 판단 필요, 실행 오류와 반복 차단은 Windows 알림 대상이다.
- Windows에 알림을 제출했다는 결과는 사용자가 배너를 보았다는 증거가 아니다. 방해 금지 등 OS 설정에 따라 숨겨질 수 있다. Codex 앱 푸시와는 별도다.
- Notion에는 등록된 업무 페이지의 진행 상태·담당·수정 횟수·관리 재계획만 갱신한다. 요구사항, 제목, 메모, PR 링크, 검증 커밋은 수정하지 않는다.
- 원격 업무 ID와 요구사항 버전을 확인하며, 사람이 완료한 페이지는 재개하지 않는다. Notion 변경이 개발을 시작하거나 자동 승인하는 기능은 없다.
- 전송은 최신 상태로 합친다. 동일 이벤트 재전송과 이미 반영된 상태보다 오래된 다른 state-dir의 덮어쓰기를 막는다. 원격 전송 직후 로컬 기록 전에 프로세스가 종료되면 알림이 중복될 수 있다. 정확히 한 번 전달을 보장하지는 않는다.
- 채널별 실패는 해당 이벤트에서 최대 3회까지만 시도한다. 단계 진행 시 또는 수동 flush 시 한 번씩 시도하며 상시 재시도하지 않는다. 실패/미설정은 콘솔과 outbox에 남고 업무를 재실행하지 않는다. Notion 실패와 Windows 알림 성공은 별도로 기록한다.
- 전송 요청은 동기 방식으로 단계 사이에 처리한다. Windows 토스트 제출·확인과 Notion 요청당 최대 5초의 지연이 발생할 수 있다.
- 개발 작업과 전송 모두 로컬 제어 상태가 기준이다. Notion은 진행 현황의 복사본이며, 사용자 개입은 controller pause/revise/note로 한다.

## 한 번 필요한 Notion 설정

1. [Notion 내부 연결 안내](https://developers.notion.com/guides/get-started/internal-connections)에 따라 해당 워크스페이스의 내부 연결을 만든다.
2. 콘텐츠 읽기·업데이트 권한을 주고, **한자리 자동화 업무 DB**에 연결한다. 전체 개인 워크스페이스를 공개할 필요는 없다.
3. 토큰은 채팅에 보내지 말고 사용자 PowerShell에서 아래 명령의 숨김 입력창에 붙여넣는다.

```powershell
cd C:\Users\dnjsw\projects\HanJari
powershell -NoProfile -File .\automation\set-notion-token.ps1
```

토큰은 현재 Windows 사용자의 DPAPI로 암호화되어 automation/.runtime-delivery/notion-token.dpapi에 저장되며 Git에서 제외된다. 환경변수 HANJARI_NOTION_TOKEN을 직접 설정한 경우 그 값이 우선한다. DPAPI는 같은 사용자로 실행되는 모든 프로그램에 대한 격리 수단은 아니다. 토큰은 AI/Maven 자식 프로세스 환경에서 제외하며 요청/오류 로그에 출력하지 않는다. read-notion-token.ps1은 Python 내부용이므로 직접 실행하지 않는다.

## 연결 시험 (개발·테스트·LLM 재실행 없음)

```powershell
python automation/delivery.py test-notification --activate
python automation/delivery.py flush --state-dir automation/.runtime-recheck --activate
python automation/delivery.py status --state-dir automation/.runtime-recheck
```

첫 명령은 Windows 알림 시험이다. 두 번째는 기존 완료 상태를 한 번 기록하고 대기 이벤트를 보낸다. 토큰 미설정이면 setup_required로 남으며 재시도 횟수는 소비하지 않는다. 모든 대기 이벤트가 처리되지 않으면 flush는 0이 아닌 종료 코드를 반환한다.

## 오류 복구

401은 토큰, 403/404는 페이지 공유와 연결 권한, 속성/버전 불일치는 Notion 업무 상태를 확인한다. 원격 오류 본문은 비밀값 유출을 피하기 위해 콘솔에 출력하지 않는다. 실패 3회 후에는 원인을 해결하고 delivery status에서 event ID를 확인한 다음 재시도 사유를 남긴다.

```powershell
python automation/delivery.py retry --state-dir automation/.runtime-recheck --event-id EVENT_ID --channel notion --reason "권한 연결 완료"
python automation/delivery.py flush --state-dir automation/.runtime-recheck --activate
```

retry는 작업을 활성화하거나 즉시 전송하지 않는다. notification도 같은 방식으로 재시도할 수 있다. 전송 실패 원인을 해결하기 위해 완료된 영화 API를 다시 개발/검증하지 않는다.

## 검증 범위

자동 테스트는 전송 모의 처리로 상태 저장, 최신 상태 합치기, 중복 확인, 세 번 실패 제한, 업무 상태 불변, 요구사항 버전·사람의 완료 상태 보호를 확인한다. 실제 Windows 배너는 사용자 확인이 필요하다. 실제 Notion API 왕복은 토큰 설정 후 확인해야 한다.


## 이 PC 연결 시험 결과

- 2026-10-12: 기존 작업 표시줄 풍선 방식은 배너를 확인하지 못해 native toast로 교체했다.
- Windows PowerShell 시작 앱 ID를 사용하며 알림 센터 기록을 확인한다. 사용자가 알림 센터에서 시험 알림을 확인했다. 화면 배너는 아직 확인되지 않았다.
- 배너가 필요하면 Windows 설정 → 시스템 → 알림 → Windows PowerShell에서 알림 배너 표시를 확인한다. 방해 금지를 유지하려면 우선 순위 알림에 Windows PowerShell을 허용할 수 있다. 프로그램은 이 설정을 변경하지 않는다.
- 같은 태그의 최신 한자리 알림으로 알림 센터 항목을 교체하며, 상세 과거 이벤트는 state.json의 outbox에 남는다.
- 제어기 회귀 테스트 53개 통과. 실제 Notion 토큰/API 연결 시험은 미실행이다.
