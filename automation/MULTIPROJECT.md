# 아이디어와 사람의 판단을 중심으로 운영하기

자동화는 수동 활성화한 한 번의 흐름만 실행한다. Codex 기획 → 별도 Codex 독립 검토 → 사용자 기획 승인 → Claude Code 구현·테스트 → Codex 코드 리뷰 → 제어기의 Maven verify 및 Codex 최종 판정 → 사용자 코드 리뷰 순서다. 역할을 나눴다고 결과가 자동으로 정확해지는 것은 아니므로, 실제 보고서와 승인된 인수 기준을 계속 대조한다.

현재 지원 범위는 Windows, Java/Spring Boot/Maven/MySQL 백엔드다. 다른 스택이나 프론트엔드의 실행 경로는 아직 구현하지 않았다. DB 계정·인증·유료 서비스·배포처럼 사용자 조치가 필요한 부분은 멈추고 알려준다. 부하 테스트는 사용자가 직접 수행한다.

## 한자리 시작

저장소 루트에서 실행한다. 기존 영화 API 시험 상태를 초기화하지 않는다.

```powershell
python .\automation\workspace.py status --project hanjari
python .\automation\workspace.py run --project hanjari --activate
```

또는 `powershell -NoProfile -File .\automation\start-workspace.ps1 -Activate`를 사용한다.

첫 실행은 전체 기획과 독립 검토까지 진행한다. `.runtime-project/artifacts`의 문서를 읽고 중요한 정책·기술 선택을 판단한다. 질문이 남으면 수정 지시로 답한다. 해시 승인은 그 기획 버전에만 유효하다.

```powershell
python .\automation\workspace.py revise --project hanjari --text "질문에 대한 답변 또는 변경할 요구사항"
python .\automation\workspace.py run --project hanjari --activate
python .\automation\workspace.py approve --project hanjari --hash "출력된 전체 해시"
python .\automation\workspace.py run --project hanjari --activate
```

승인 후 같은 run 명령은 다음 기능 하나를 인계하고 구현·검증한다. DB 설정이 없으면 변수 이름만 안내하며 값을 출력하지 않는다. 기획 변경·중복 실행·반복 실패가 감지되면 멈춘다.

## 기능별 코드 리뷰

`pr_ready`에서 `review-pack.md`를 작성한다. 승인 범위, 인수 기준, 단계별 테스트 근거, 관련 파일·읽는 순서, 실제 변경 파일 목록을 담는다. **pr_ready는 Draft PR 준비 상태이며 GitHub에 PR이 생성되었다는 뜻이 아니다.** 현재 이 명령은 commit/push/PR 생성·병합·배포를 자동 수행하지 않는다. 검증된 변경을 GitHub Draft PR로 올리는 작업은 별도 Git 작업으로 진행한다.

리뷰에서 수정이 필요하면 `revise`로 요구사항과 기획을 갱신한다. 기획 변경은 기존 승인·인계를 무효화하고 기록은 보존한다. 리뷰에 만족하면 다음처럼 결과를 기록한다. 이는 GitHub 병합 명령이 아니다.

```powershell
python .\automation\workspace.py accept-feature --project hanjari --feature HJ-002 --text "읽은 파일, 검증한 동작과 리뷰 결론"
python .\automation\workspace.py run --project hanjari --activate
```

다음 기능의 첫 실행은 깨끗한 작업 폴더를 요구한다. 앞선 기능의 변경은 먼저 리뷰하고 커밋하여 보존해야 한다. 사용자 승인 없이 기존 변경을 덮거나 임의로 커밋하지 않는다. 완료한 기능에서 run을 반복해도 에이전트를 재실행하지 않는다.

## 중간에 개입하기

```powershell
python .\automation\workspace.py note --project hanjari --text "추가 아이디어"
python .\automation\workspace.py pause --project hanjari
python .\automation\workspace.py status --project hanjari
```

note는 기록이다. 승인된 범위를 즉시 바꾸지 않는다. 반영하려면 pause 후 현재 단계 종료를 확인하고 revise한다. pause는 실행 중인 프로세스를 강제로 종료하지 않으며 다음 단계의 에이전트 호출을 막는다. 재개도 run --activate로 명시한다.

3번 수정 → 관리 역할의 분석·재계획 1번 → 다시 실패하면 사용자 판단. 별도로 반복 실패·시도 수 차단기가 적용된다. 원인 해결 후에만 `reset-breaker --text "원인과 조치"`로 해제한다. Claude 사용률 5h 80%/7d 70% 및 사용량 파일 최신성 기준은 유지한다.

## 다른 프로젝트 등록

같은 제어기를 사용한다. 새 저장소를 로컬에 준비하고 GitHub origin을 설정한다. 프로젝트 결과를 쓸 **별도 Notion 페이지**를 만들고 현재 사용 중인 통합에 접근 권한을 부여한다. 다른 워크스페이스에 현재 토큰의 권한이 없으면 동일한 인증으로는 연결할 수 없다. 토큰별 다중 계정 라우팅은 지원하지 않는다.

```powershell
python .\automation\workspace.py register
```

대화형으로 프로젝트 ID, 저장소 폴더, 아이디어, 이름, Notion 결과 페이지 ID를 입력한다. 등록만으로 에이전트가 시작되지는 않는다. 긴 아이디어는 UTF-8 파일로 전달할 수 있다.

```powershell
python .\automation\workspace.py register --project bookshelf --repo "C:\projects\Bookshelf" --title "책장" --prefix BK --idea-file "C:\ideas\bookshelf.md" --record-page "Notion-페이지-UUID"
python .\automation\workspace.py run --project bookshelf --activate
```

Notion에 원본 아이디어가 있다면 `--source-page "별도-원본-UUID"`를 추가한다. 원본과 결과 페이지를 같게 설정하면 자동화의 기록 자체가 요구사항 변경을 일으키므로 등록을 거부한다. 페이지 URL 대신 UUID를 입력한다. 페이지 읽기·쓰기 권한은 실제 실행·동기화 시 검증된다.

신규 프로젝트는 영화 API 정책을 상속하지 않는다. 기존 코드가 있는 프로젝트는 `--baseline baseline.json`으로 회귀 테스트를 지정한다.

```json
{"surefire-reports":{"ExistingServiceTest":5},"failsafe-reports":{"ExistingApiIT":2}}
```

신규 프로젝트의 DB 환경변수는 `APP_TEST_DB_URL`, `APP_TEST_DB_USERNAME`, `APP_TEST_DB_PASSWORD`다. 기획에서 필요한 전용 DB 설계를 결정한 후 사용자가 준비한다. 값은 터미널 환경에서만 제공한다. 한자리는 기존 `HANJARI_TEST_DB_*`를 유지한다. 새 저장소의 도구 권한·네트워크 권한은 별도 준비가 필요할 수 있으며 제어기가 권한을 자동으로 확대하지 않는다.

프로필과 실행 상태는 `.runtime-workspaces/<프로젝트-ID>` 아래에 보관한다. 이 디렉터리를 Git에 올리지 않는다. 저장소 경로·GitHub origin·기능 ID·검증 기준을 상태와 대조해 다른 프로젝트에서 잘못 실행하는 것을 차단한다. 프로필의 중요한 항목을 직접 변경하면 기존 작업은 실행이 거부될 수 있다.

## Notion 기록 위치

- 제품 요구사항·기획 결과·기능 상태·AI 작업 근거·사용자 결정: 해당 프로젝트의 결과 페이지.
- 공통 자동화 운영 방식·프롬프트·개선 검증 기록: 한자리의 `한자리 자동화 기록` 공간.
- 원시 로그·인증정보: 게시하지 않는다.

Notion 동기화 실패는 에이전트 작업을 재실행하지 않는다. 로컬 근거를 보존하고 게시를 재시도한다. `sync --project <ID> --activate`는 기록만 전송한다. 3회 실패 후에는 사용자 조치가 필요하다. 원인을 해결한 후 `retry-sync --project <ID> --text "원인과 조치"`로 게시 재시도를 허용하고 sync한다. 현재 새 프로젝트는 전용 DB의 개별 카드 생성 대신 결과 페이지에 기록을 추가한다.

## 검증 범위

자동화 테스트는 승인 차단, 다른 저장소·프로젝트 혼입 차단, 기능별 리뷰 대기, 재실행 방지, 중간 개입, 기획 변경 시 승인 무효화, Notion 기록 분리 등을 모의 실행으로 검증한다. mock 결과는 실제 개발 성공으로 게시하지 않는다. 새 프로젝트에서 실제 LLM이 생성한 코드의 빌드 성공까지 보장하는 시험은 별도 실사용 검증이다.
