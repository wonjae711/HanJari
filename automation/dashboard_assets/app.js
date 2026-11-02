'use strict';
const $ = id => document.getElementById(id);
const names = {not_started:'첫 기획을 시작할 준비가 됐어요',plan:'기획안을 작성하고 있어요',spec_review:'기획안을 독립 검토하고 있어요',awaiting_plan_approval:'기획 검토 완료, 당신의 결정을 기다려요',implement:'승인된 기능을 개발하고 있어요',review:'코드와 검증 근거를 리뷰하고 있어요',final_test:'최종 검증을 진행하고 있어요',pr_ready:'검증 완료, 코드 리뷰를 기다려요',needs_user:'사용자의 판단이 필요해요',manager:'문제를 분석하고 다시 계획하고 있어요'};
const actionNames = {run:'자동화 실행',pause:'중단 요청',note:'아이디어 기록',revise:'기획 수정 요청',approve:'기획 검토 · 승인',sync:'Notion 동기화','accept-feature':'현재 기능 리뷰 완료','retry-sync':'동기화 재시도 허용','reset-breaker':'반복 방지 해제'};
let token='', state=null, selected='hanjari', pending=null, loading=false;
function text(id,value){$(id).textContent=value??'';}
function element(tag,value,cls){const n=document.createElement(tag);if(value!==undefined)n.textContent=value;if(cls)n.className=cls;return n;}
function message(value){text('message',value);$('message').hidden=false;}
async function api(path,body){const response=await fetch(path,{cache:'no-store',method:body?'POST':'GET',headers:body?{'Content-Type':'application/json','X-Dashboard-Token':token}:{},body:body?JSON.stringify(body):undefined});const data=await response.json();if(!response.ok)throw Error(data.error||'연결 오류');return data;}
function list(id,items,render,empty){const root=$(id);root.replaceChildren();if(!items?.length){root.append(element('div',empty,'empty'));return;}items.forEach(x=>root.append(render(x)));}
function render(s){
 document.querySelectorAll('[data-action]').forEach(b=>b.disabled=false);
 state=s;text('title',s.project.title);text('mode',s.mode==='mock'?'MOCK · 모의 실행':'LIVE · 실제 작업');text('stage',names[s.stage]||s.stage);text('revision',s.parent_revision?'기획 r'+s.parent_revision:'시작 전');
 if(s.pause_requested)text('stage-help','중단 요청이 기록됐습니다. 실행 중인 단계가 끝날 때까지 기다려 주세요.');
 else if(s.approved && s.stage==='awaiting_plan_approval') {text('stage','기획 승인 완료, 다음 기능을 시작할 수 있어요');text('stage-help','자동화 실행을 누르면 승인된 다음 기능을 진행합니다.');}
 else text('stage-help',s.active?'에이전트가 실행 중입니다. 새 작업을 중복 실행하지 마세요.':s.busy?'요청을 처리 중입니다. 상태가 자동으로 갱신됩니다.':'수동 실행 상태입니다. 화면을 열어도 AI 작업은 시작되지 않습니다.');
 const stages=['기획 작성','독립 검토','사용자 승인','개발','최종 검증','코드 리뷰'];let index={plan:0,spec_review:1,awaiting_plan_approval:2,implement:3,review:3,final_test:4,pr_ready:5}[s.stage];if(s.approved&&index===2)index=3;
 $('steps').replaceChildren(...stages.map((name,i)=>{const li=element('li',undefined,i===index?'current':i<index?'done':'');li.append(element('span',i<index?'✓':i+1,'step-num'),element('span',name));return li;}));
 for(const [key,label,bar] of [['five_hour','five','five-bar'],['seven_day','seven','seven-bar']]){text(label,s.usage?s.usage[key]+'%':'—');$(bar).value=s.usage?.[key]||0;}
 text('freshness',s.usage?(s.usage.fresh?'최신 값':'갱신 필요')+' · '+Math.floor(s.usage.age_seconds/60)+'분 전':'사용량 파일을 확인하세요');
 text('approval',s.approved?'전체 기획 승인이 기록됐습니다. 기능별 코드 리뷰와 최종 병합 판단은 별도입니다.':'현재 기획은 초안입니다. 전체 기획 승인이 있어야 개발로 넘어갑니다.');
 text('document',s.document||'아직 기획 산출물이 없습니다. 자동화 실행으로 기획을 시작할 수 있습니다.');
 list('questions',s.questions,q=>element('p','결정 필요 · '+q),'현재 기획의 미응답 질문 없음');
 $('run').disabled=s.busy||s.active||s.stage==='needs_user'||(!s.approved&&s.stage==='awaiting_plan_approval');
 $('approve').disabled=s.busy||s.active||s.approved||s.parent_stage!=='awaiting_plan_approval'||!s.candidate_hash;
 $('revise').disabled=s.busy||s.active||s.stage==='not_started';$('sync').disabled=s.busy||s.stage==='not_started';
 $('pause').disabled=s.stage==='not_started';$('accept').disabled=s.busy||s.active||s.stage!=='pr_ready'||!s.current_feature;
 list('feature-list',s.features,f=>{const n=element('article',undefined,'feature');n.append(element('small',f.task_id+' · '+((s.accepted||[]).includes(f.task_id)?'사용자 수락 완료':s.current_feature===f.task_id?'현재 작업':'계획됨')),element('h3',f.title),element('p',f.requirements));const d=element('details');d.append(element('summary','인수 기준과 의존 업무'),element('p',(f.acceptance_criteria||[]).map(t=>'• '+t).join('\n')),element('p','의존 업무: '+((f.depends_on||[]).join(', ')||'없음')));n.append(d);return n;},'기획이 작성되면 기능별 업무가 표시됩니다.');
 list('jobs',[...(s.jobs||[])].reverse(),j=>{const n=element('div',undefined,'entry');n.append(element('strong',actionNames[j.action]),element('span',{running:'처리 중',completed:'처리 완료',failed:'실패'}[j.status],'tag'));if(j.status==='failed')n.append(element('p','요청 처리에 실패했습니다. 아래 로컬 실행 기록을 확인하세요.'),element('small',j.log_file||'서버 실행 환경 확인 필요'));return n;},'이 대시보드에서 보낸 요청이 없습니다.');
 list('results',[...(s.results||[])].reverse(),r=>{const n=element('div',undefined,'entry');n.append(element('strong',r.stage),element('span',r.outcome,'tag'),element('p',r.summary));const detail=element('details');detail.append(element('summary','검증 근거 · 관련 파일 · 검토 의견'),element('p',[...(r.evidence||[]),...(r.review_files||[]),...(r.artifact?.findings||[])].join('\n')||'기록 없음'));n.append(detail);return n;},'저장된 검토 결과가 없습니다.');
 list('events',[...(s.events||[])].sort((a,b)=>b.at.localeCompare(a.at)),e=>{const n=element('div',undefined,'entry');n.append(element('small',new Date(e.at).toLocaleString('ko-KR')),element('p',e.message));return n;},'아직 활동 기록이 없습니다.');
 text('receipts',s.receipts?.length?'미완료 문서 전송 '+s.receipts.length+'건':'미완료 문서 전송 없음');text('updated','최근 확인 '+new Date().toLocaleTimeString('ko-KR'));text('connection','● 연결됨');
}
async function refresh(){if(loading)return;loading=true;const key=selected;try{const s=await api('/api/state?project='+encodeURIComponent(key));if(key===selected)render(s);}catch(e){text('connection','연결 확인 필요');message(e.message);document.querySelectorAll('[data-action]').forEach(b=>b.disabled=true);}finally{loading=false;}}
function openAction(action){if(!state)return;pending={action,project:selected,hash:state.candidate_hash,feature:state.current_feature};text('dialog-title',actionNames[action]);const withText=['note','revise','accept-feature','retry-sync','reset-breaker'].includes(action);$('input').hidden=!withText;$('text-label').hidden=!withText;$('input').required=withText;$('input').value='';$('confirm-check').checked=false;$('confirm-check').required=action==='approve';$('check-label').hidden=action!=='approve';$('dialog-plan').hidden=action!=='approve';text('dialog-plan',state.document+'\n\n승인 대상 해시: '+state.candidate_hash);
 const descriptions={run:'실제 모드에서는 AI 사용량이 발생합니다. 승인 대기나 리뷰 대기 상태에서는 다음 단계가 자동 승인되지 않습니다.',pause:'현재 단계가 끝난 뒤 중단합니다. 강제 종료는 하지 않습니다.',note:'아이디어를 기록합니다. 진행 중인 요구사항을 즉시 바꾸지는 않습니다.',revise:'새 기획 버전을 만들고 기존 전체 승인을 해제합니다. 변경할 요구사항을 구체적으로 적어 주세요. 재기획은 이후 실행 버튼으로 시작합니다.',approve:'아래 전체 기획안을 검토하세요. 현재 버전에 대한 승인만 기록하며 개발을 즉시 실행하지 않습니다.',sync:'기존 결과를 Notion에 동기화합니다. 기획·개발 모델은 실행하지 않습니다.','accept-feature':'관련 파일과 테스트 근거를 확인한 뒤 판단 근거를 남겨 주세요. 기능을 수락하더라도 PR을 자동 병합하지 않습니다.','retry-sync':'문서 게시 실패 원인을 해결한 후 재시도 예산을 복구합니다. 이후 Notion 동기화 버튼을 사용하세요.','reset-breaker':'반복 실패 원인을 해결한 경우에만 제한을 해제하세요. 원인과 조치 내용을 기록합니다.'};text('dialog-description',descriptions[action]);$('dialog').showModal();}
document.querySelectorAll('[data-action]').forEach(button=>button.addEventListener('click',()=>openAction(button.dataset.action)));
$('cancel').onclick=()=>$('dialog').close();
$('action-form').onsubmit=async e=>{e.preventDefault();$('submit').disabled=true;try{await api('/api/action',{...pending,text:$('input').value});$('dialog').close();message('요청을 접수했습니다. 처리 현황에서 완료 여부를 확인하세요.');await refresh();}catch(e){message(e.message);}finally{$('submit').disabled=false;}};
$('project').onchange=()=>{selected=$('project').value;state=null;refresh();};
async function start(){try{const session=await api('/api/session');token=session.token;$('project').replaceChildren(...session.projects.map(p=>{const o=element('option',p.title);o.value=p.id;return o;}));selected=$('project').value;await refresh();setInterval(refresh,3000);}catch(e){text('connection','설정 확인 필요');message(e.message);}}
start();
