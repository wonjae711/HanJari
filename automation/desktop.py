"""Native Windows desktop UI. Uses the controller directly; no HTTP server."""
import argparse
import ctypes
import hashlib
import os
from pathlib import Path
import queue
import threading
import tkinter as tk
from tkinter import ttk, messagebox
from datetime import datetime
from dashboard import Backend, ROOT, TEXT_ACTIONS

BG = '#f4f5fa'
INK = '#272c43'
MUTED = '#747c92'
PURPLE = '#6954ce'
STAGES = {'not_started':'첫 기획을 시작할 준비가 됐어요', 'plan':'기획 작성', 'spec_review':'독립 기획 검토',
          'awaiting_plan_approval':'전체 기획 승인 대기', 'implement':'기능 개발', 'review':'코드 리뷰',
          'final_test':'최종 검증', 'pr_ready':'사용자 코드 리뷰 대기', 'manager':'문제 분석 · 재계획', 'needs_user':'사용자 판단 필요'}
NAMES = {'run':'자동화 실행', 'pause':'중단 요청', 'note':'아이디어 기록', 'revise':'수정 요청', 'approve':'기획 승인',
         'sync':'Notion 동기화', 'accept-feature':'기능 리뷰 완료', 'retry-sync':'동기화 재시도 허용', 'reset-breaker':'반복 방지 해제'}
HELP = {
    'run':'선택한 프로젝트의 자동화를 한 번 실행합니다. 실제 모드에서는 AI 사용량이 발생합니다.',
    'pause':'현재 단계를 강제 종료하지 않습니다. 현재 단계가 끝난 뒤 다음 작업을 시작하지 않습니다.',
    'note':'아이디어를 기록합니다. 현재 개발 범위에 즉시 반영되지 않으며, 수정 요청으로 편입할 수 있습니다.',
    'revise':'새 기획 버전을 만들고 기존 전체 승인을 해제합니다. 변경할 요구사항을 적어 주세요. 이후 실행 버튼으로 재기획을 시작합니다.',
    'approve':'표시된 기획안과 검토 근거를 확인하세요. 정확히 이 버전만 승인하며 개발은 별도로 실행합니다.',
    'sync':'저장된 결과를 Notion에 동기화합니다. AI 모델을 실행하지 않습니다.',
    'accept-feature':'관련 코드와 검증 근거를 검토한 후 판단 근거를 기록하세요. PR은 자동 병합하지 않습니다.',
    'retry-sync':'게시 실패 원인을 해결한 뒤 조치 내용을 남깁니다. 이후 Notion 동기화를 실행하세요.',
    'reset-breaker':'반복 실패 원인을 해결한 경우에만 제한을 해제하세요. 원인과 해결 내용을 기록합니다.'}


def allowed_actions(state):
    if not state: return set()
    busy = bool(state.get('busy') or state.get('active') or state.get('running'))
    started = state['stage'] != 'not_started'
    if busy: return {'pause', 'note'} if started else set()
    allowed = {'run'}
    if started: allowed |= {'pause', 'note', 'sync', 'revise', 'retry-sync', 'reset-breaker'}
    if state['stage'] == 'needs_user' or (state['stage'] == 'awaiting_plan_approval' and not state.get('approved')):
        allowed.discard('run')
    if state.get('parent_stage') == 'awaiting_plan_approval' and state.get('candidate_hash') and not state.get('approved'):
        allowed.add('approve')
    if state['stage'] == 'pr_ready' and state.get('current_feature'): allowed.add('accept-feature')
    return allowed


def payload_for(state, action):
    if action not in allowed_actions(state): raise ValueError('현재 상태에서 사용할 수 없는 작업입니다. 상태를 새로고침하세요.')
    return {'action':action, 'project':state['project']['id'], 'hash':state.get('candidate_hash'), 'feature':state.get('current_feature')}


def replace_text(widget, value):
    """Keep selection/scroll intact when the polled content has not changed."""
    if getattr(widget, '_content', None) == value: return
    position = widget.yview()[0]
    widget.configure(state='normal')
    widget.delete('1.0', 'end')
    widget.insert('1.0', value)
    for i, line in enumerate(value.splitlines(), 1):
        if line.startswith('# '): widget.tag_add('h1', f'{i}.0', f'{i}.end')
        elif line.startswith('## '): widget.tag_add('h2', f'{i}.0', f'{i}.end')
    widget.configure(state='disabled')
    widget.yview_moveto(position)
    widget._content = value


class Desktop:
    def __init__(self, root, backend, poll=True):
        self.root, self.backend = root, backend
        self.state = None
        self.mailbox = queue.Queue()
        self.loading = False
        self.stale = True
        self.pending_submit = False
        self.closed = False
        self.last_features = None
        root.title('한자리 스튜디오 — Desktop')
        root.geometry('1280x850')
        root.minsize(980, 720)
        root.configure(bg=BG)
        root.option_add('*Font', ('맑은 고딕', 10))
        root.protocol('WM_DELETE_WINDOW', self.close)
        icon = ROOT/'desktop_assets'/'hanjari.ico'
        if icon.exists(): root.iconbitmap(str(icon))
        self.style = ttk.Style(root)
        self.style.theme_use('clam')
        self.style.configure('TCombobox', padding=7, fieldbackground='#ffffff')
        self.style.configure('Treeview', rowheight=32, background='#ffffff', fieldbackground='#ffffff', borderwidth=0, foreground=INK)
        self.style.configure('Treeview.Heading', font=('맑은 고딕', 10, 'bold'), background='#eeeaf8', padding=9)
        self.style.map('Treeview', background=[('selected', '#e9e4fb')], foreground=[('selected', '#493a92')])
        self.style.configure('Horizontal.TProgressbar', background='#9482e1', troughcolor='#ececf5', borderwidth=0, thickness=6)
        self.buttons = {}
        self.build()
        if poll:
            self.root.after(50, self.refresh)
            self.root.after(100, self.drain)

    def label(self, parent, value='', size=10, color=INK, bold=False, bg=None, **kwargs):
        return tk.Label(parent, text=value, font=('맑은 고딕',size,'bold' if bold else 'normal'),
                        fg=color, bg=bg or parent.cget('bg'), anchor='w', **kwargs)

    def button(self, parent, action, primary=False):
        button = tk.Button(parent, text=NAMES[action], command=lambda:self.action(action), relief='flat', bd=0,
                           bg=PURPLE if primary else '#eae7f5', fg='white' if primary else '#5f538b',
                           activebackground='#d7cff2', activeforeground=INK, disabledforeground='#a7a2b8',
                           padx=14, pady=9, cursor='hand2', state='disabled')
        self.buttons.setdefault(action, []).append(button)
        return button

    def reader(self, parent):
        frame=tk.Frame(parent,bg='white')
        frame.pack(fill='both',expand=True)
        text=tk.Text(frame,wrap='word',relief='flat',borderwidth=0,padx=22,pady=18,
                     font=('맑은 고딕',10),background='white',foreground=INK,spacing3=7,selectbackground='#ddd5f7',state='disabled')
        scrollbar=ttk.Scrollbar(frame,command=text.yview)
        text.configure(yscrollcommand=scrollbar.set)
        scrollbar.pack(side='right',fill='y');text.pack(side='left',fill='both',expand=True)
        text.tag_configure('h1',font=('맑은 고딕',17,'bold'),foreground='#483886',spacing1=10,spacing3=14)
        text.tag_configure('h2',font=('맑은 고딕',12,'bold'),foreground='#60508f',spacing1=14,spacing3=8)
        return text

    def build(self):
        sidebar=tk.Frame(self.root,bg='#25273d',width=208)
        sidebar.pack(side='left',fill='y');sidebar.pack_propagate(False)
        self.label(sidebar,'한자리',size=25,bold=True,color='white').pack(anchor='w',padx=25,pady=(30,4))
        self.label(sidebar,'AUTOMATION STUDIO',size=8,color='#a7a0cf').pack(anchor='w',padx=27)
        self.label(sidebar,'PROJECT',size=8,color='#9997b0').pack(anchor='w',padx=25,pady=(36,9))
        self.project_value=tk.StringVar()
        self.selector=ttk.Combobox(sidebar,textvariable=self.project_value,state='readonly',width=17)
        self.selector.pack(padx=20,fill='x')
        self.selector.bind('<<ComboboxSelected>>',self.change_project)
        self.project_keys=[]
        self.nav={}
        for key,name in [('overview','진행 현황'),('plan','기획과 결정'),('features','기능별 업무'),('review','리뷰와 검증'),('activity','활동 기록'),('requests','요청 · 복구')]:
            button=tk.Button(sidebar,text=name,relief='flat',bg='#25273d',fg='#c4c2d4',activebackground='#45415e',
                             activeforeground='white',anchor='w',padx=16,pady=12,command=lambda k=key:self.show(k),cursor='hand2')
            button.pack(fill='x',padx=12,pady=(20 if not self.nav else 3,0));self.nav[key]=button
        self.label(sidebar,'●  DESKTOP · LOCAL',size=8,color='#a6c9bd').pack(side='bottom',anchor='w',padx=25,pady=25)
        main=tk.Frame(self.root,bg=BG,padx=26,pady=24);main.pack(side='left',fill='both',expand=True)
        header=tk.Frame(main,bg=BG);header.pack(fill='x')
        self.title=self.label(header,'작업 공간',size=23,bold=True);self.title.pack(side='left')
        self.mode=self.label(header,self.backend.mode.upper()+' · 독립 데스크톱 앱',color=PURPLE);self.mode.pack(side='right',padx=3)
        self.status=self.label(main,'상태를 불러오고 있습니다.',size=14,bold=True);self.status.pack(fill='x',pady=(17,6))
        self.hint=self.label(main,'앱을 열어도 AI 작업이 시작되지 않습니다.',color=MUTED);self.hint.pack(fill='x')
        actions=tk.Frame(main,bg=BG);actions.pack(fill='x',pady=(15,18))
        for action in ('run','pause','note','sync'):
            self.button(actions,action,action=='run').pack(side='left',padx=(0,8))
        self.content=tk.Frame(main,bg='white',highlightbackground='#e5e5ee',highlightthickness=1)
        self.content.pack(fill='both',expand=True)
        self.views={key:tk.Frame(self.content,bg='white') for key in self.nav}
        overview=self.views['overview']
        self.label(overview,'지금 확인할 내용',size=16,bold=True).pack(anchor='w',padx=23,pady=(20,4))
        self.steps=self.label(overview,'기획  →  검토  →  승인  →  개발  →  검증  →  코드 리뷰',color=PURPLE)
        self.steps.pack(fill='x',padx=23,pady=12)
        cards=tk.Frame(overview,bg='white');cards.pack(fill='x',padx=23,pady=6)
        self.usage_labels={};self.bars={}
        for col,(key,title) in enumerate([('five_hour','CLAUDE · 5시간 / 중단 기준 80%'),('seven_day','CLAUDE · 7일 / 중단 기준 70%')]):
            cards.columnconfigure(col,weight=1)
            card=tk.Frame(cards,bg='#f6f4fc',padx=16,pady=13)
            card.grid(row=0,column=col,sticky='nsew',padx=(0,10) if col==0 else (0,0))
            self.label(card,title,size=9,color=MUTED).pack(anchor='w')
            self.usage_labels[key]=self.label(card,'—',size=23,bold=True)
            self.usage_labels[key].pack(anchor='w',pady=6)
            self.bars[key]=ttk.Progressbar(card,maximum=100);self.bars[key].pack(fill='x')
        self.freshness=self.label(overview,'사용량 갱신 시각 확인 중',color=MUTED,size=9)
        self.freshness.pack(anchor='w',padx=23,pady=(4,12))
        quick=tk.Frame(overview,bg='white');quick.pack(fill='x',padx=23)
        self.button(quick,'approve',True).pack(side='left',padx=(0,8))
        self.button(quick,'revise').pack(side='left')
        self.overview_text=self.reader(overview)
        toolbar=tk.Frame(self.views['plan'],bg='white',padx=18,pady=12);toolbar.pack(fill='x')
        self.button(toolbar,'approve',True).pack(side='left',padx=(0,8));self.button(toolbar,'revise').pack(side='left')
        self.plan_text=self.reader(self.views['plan'])
        treeframe=tk.Frame(self.views['features'],bg='white');treeframe.pack(fill='x',padx=18,pady=18)
        self.tree=ttk.Treeview(treeframe,columns=('id','title','status'),show='headings',height=7,selectmode='browse')
        for key,title,width in [('id','업무',90),('title','기능',410),('status','상태',140)]:
            self.tree.heading(key,text=title);self.tree.column(key,width=width,minwidth=60,stretch=key=='title')
        sb=ttk.Scrollbar(treeframe,command=self.tree.yview);self.tree.configure(yscrollcommand=sb.set)
        sb.pack(side='right',fill='y');self.tree.pack(fill='x',expand=True)
        self.tree.bind('<<TreeviewSelect>>',self.feature_selected)
        self.feature_text=self.reader(self.views['features'])
        reviewbar=tk.Frame(self.views['review'],bg='white',padx=18,pady=12);reviewbar.pack(fill='x')
        self.button(reviewbar,'accept-feature',True).pack(side='left')
        self.review_text=self.reader(self.views['review'])
        self.activity_text=self.reader(self.views['activity'])
        recovery=tk.Frame(self.views['requests'],bg='white',padx=18,pady=12);recovery.pack(fill='x')
        for action in ('retry-sync','reset-breaker'):self.button(recovery,action).pack(side='left',padx=(0,8))
        self.requests_text=self.reader(self.views['requests'])
        self.footer=self.label(main,'연결 준비 중',size=9,color=MUTED);self.footer.pack(fill='x',pady=(12,0))
        self.show('overview')

    def show(self, key):
        for name,view in self.views.items():
            view.pack_forget();self.nav[name].configure(bg='#45415e' if name==key else '#25273d',fg='white' if name==key else '#c4c2d4')
        self.views[key].pack(fill='both',expand=True)

    def change_project(self, event=None):
        self.state=None;self.stale=True;self.last_features=None
        self.enable(set())
        self.refresh()

    def enable(self, actions):
        for action,buttons in self.buttons.items():
            for button in buttons: button.configure(state='normal' if action in actions else 'disabled')

    def refresh(self):
        if self.closed or self.loading: return
        self.loading=True
        chosen=self.project_keys[self.selector.current()] if self.selector.current()>=0 else None
        def read():
            try:
                projects=self.backend.projects()
                key=chosen or projects[0]['id']
                state=self.backend.snapshot(key)
                self.mailbox.put(('state',chosen,projects,state))
            except Exception:
                self.mailbox.put(('error','상태를 읽지 못했습니다. config.json과 프로젝트 파일을 확인하세요.'))
        threading.Thread(target=read,daemon=True).start()

    def drain(self):
        if self.closed:return
        try:
            while True:
                item=self.mailbox.get_nowait()
                if item[0]=='state':
                    _,requested,projects,state=item
                    self.loading=False
                    current=self.project_keys[self.selector.current()] if self.selector.current()>=0 else None
                    if current!=requested:
                        self.refresh();continue
                    self.project_keys=[p['id'] for p in projects]
                    self.selector.configure(values=[p['title']+'  ·  '+p['id'] for p in projects])
                    self.selector.current(self.project_keys.index(state['project']['id']))
                    self.render(state);self.stale=False
                elif item[0]=='error':
                    self.loading=False;self.stale=True;self.enable(set());self.footer.configure(text=item[1])
                elif item[0]=='submitted':
                    self.pending_submit=False
                    self.footer.configure(text='요청을 접수했습니다. 「요청 · 복구」에서 처리 결과를 확인하세요.')
                    self.refresh()
                elif item[0]=='submit_error':
                    self.pending_submit=False;self.refresh();messagebox.showerror('요청 실패',item[1],parent=self.root)
        except queue.Empty: pass
        self.root.after(100,self.drain)
        # Refresh independently of action completion; all Tk operations stay on this thread.
        if not self.loading and not hasattr(self,'refresh_timer'):
            def scheduled():
                del self.refresh_timer
                self.refresh()
            self.refresh_timer=self.root.after(3000,scheduled)

    def render(self, state):
        self.state=state
        self.title.configure(text=state['project']['title']+'의 작업 공간')
        stage=STAGES.get(state['stage'],state['stage'])
        if state.get('approved') and state['stage']=='awaiting_plan_approval':stage='기획 승인 완료 · 다음 기능 실행 가능'
        self.status.configure(text=stage)
        busy=state.get('active') or state.get('running') or state.get('busy')
        hint='중단 요청됨 · 현재 단계가 끝날 때까지 기다려 주세요.' if state.get('pause_requested') else (
            '처리 중 · 중단 요청과 아이디어 기록은 가능합니다.' if busy else '수동 활성화 대기 · 앱을 열어도 AI가 실행되지 않습니다.')
        self.hint.configure(text=hint)
        self.enable(allowed_actions(state) if not self.pending_submit else set())
        usage=state.get('usage')
        for key in self.usage_labels:
            self.usage_labels[key].configure(text=str(usage[key])+'%' if usage else '—')
            self.bars[key]['value']=usage[key] if usage else 0
        self.freshness.configure(text=('최신 값' if usage['fresh'] else '갱신 필요')+' · '+str(usage['age_seconds']//60)+'분 전 저장됨' if usage else '사용량 파일을 확인하세요. 남은 토큰을 추정하지 않습니다.')
        approval='전체 기획 승인됨' if state.get('approved') else '전체 기획 미승인'
        current='기획 r'+str(state.get('parent_revision','—'))+'  ·  '+approval
        questions='\n'.join('• '+x for x in state.get('questions',[])) or '현재 기획의 미응답 질문 없음'
        latest=state.get('results',[])[-1] if state.get('results') else None
        replace_text(self.overview_text,'## '+current+'\n\n'+questions+'\n\n## 최신 판단\n'+(latest['summary'] if latest else '아직 기획 결과가 없습니다.')+'\n\n기획을 승인한 뒤 실행하면 다음 기능으로 이어집니다. 기능 검증 후에는 사용자 코드 리뷰를 기다립니다.')
        replace_text(self.plan_text,'# 전체 기획안\n'+current+'\n\n'+(state.get('document') or '아직 작성된 기획안이 없습니다.')+'\n\n승인 대상: '+str(state.get('candidate_hash') or '없음'))
        features=state.get('features',[])
        signature=[(f['task_id'],f['title'],f['requirements'],f.get('acceptance_criteria'),f.get('depends_on')) for f in features],state.get('accepted',[]),state.get('current_feature')
        if signature!=self.last_features:
            old=self.tree.selection()
            self.tree.delete(*self.tree.get_children())
            for f in features:
                status='수락 완료' if f['task_id'] in state.get('accepted',[]) else '현재 작업' if f['task_id']==state.get('current_feature') else '계획됨'
                self.tree.insert('', 'end',iid=f['task_id'],values=(f['task_id'],f['title'],status))
            self.last_features=signature
            selected=old[0] if old and self.tree.exists(old[0]) else features[0]['task_id'] if features else None
            if selected:self.tree.selection_set(selected)
            self.feature_selected()
        review=[]
        for r in reversed(state.get('results',[])):
            review.extend(['## '+r['stage']+' · '+r['outcome'],r['summary'],'검증 근거:']+r.get('evidence',[])+['관련 파일:']+r.get('review_files',[])+r.get('artifact',{}).get('findings',[])+[''])
        replace_text(self.review_text,'# 리뷰와 검증 근거\n저장된 기록입니다. 화면 조회 시 테스트를 재실행하지 않습니다.\n\n'+'\n'.join(review))
        events=sorted(state.get('events',[]),key=lambda e:e['at'],reverse=True)
        replace_text(self.activity_text,'# 활동 기록\n\n'+'\n\n'.join(e['at']+'\n'+e['message'] for e in events))
        jobs=[]
        for job in reversed(state.get('jobs',[])):
            jobs.append(NAMES[job['action']]+' · '+{'running':'처리 중','completed':'처리 완료','failed':'실패'}[job['status']])
            if job.get('log_file'):jobs.append('실행 기록: '+job['log_file'])
            jobs.append('')
        replace_text(self.requests_text,'# 요청 처리 현황\n\n'+('\n'.join(jobs) or '이 앱에서 보낸 요청이 없습니다.')+'\n\n미완료 문서 전송: '+str(len(state.get('receipts',[])))+'건\n\n앱 재시작 시 이 요청 목록은 초기화됩니다. 실제 프로젝트 상태와 활동 기록은 유지됩니다.')
        self.footer.configure(text='● 연결됨  ·  마지막 조회 '+datetime.now().strftime('%H:%M:%S')+'  ·  브라우저 / 웹 서버 사용 안 함')

    def feature_selected(self,event=None):
        selection=self.tree.selection()
        feature=next((f for f in (self.state or {}).get('features',[]) if selection and f['task_id']==selection[0]),None)
        if not feature:replace_text(self.feature_text,'기획이 작성되면 기능별 업무가 표시됩니다.');return
        replace_text(self.feature_text,'# '+feature['task_id']+' · '+feature['title']+'\n\n'+feature['requirements']+'\n\n## 인수 기준\n'+'\n'.join('• '+x for x in feature['acceptance_criteria'])+'\n\n의존 업무: '+(', '.join(feature['depends_on']) or '없음')+'\n\n제외 범위:\n'+'\n'.join(feature.get('excluded',[])))

    def action(self,action):
        if self.stale or self.pending_submit:return
        try:payload=payload_for(self.state,action)
        except ValueError as exc:messagebox.showerror('작업 불가',str(exc),parent=self.root);return
        # Capture the reviewed version now; polling must never change this consent payload.
        dialog=tk.Toplevel(self.root);dialog.title(NAMES[action]);dialog.transient(self.root)
        dialog.geometry('800x650' if action=='approve' else '640x400');dialog.configure(bg='white');dialog.grab_set()
        dialog.protocol('WM_DELETE_WINDOW',dialog.destroy)
        self.label(dialog,NAMES[action],size=18,bold=True).pack(anchor='w',padx=24,pady=(20,12))
        self.label(dialog,HELP[action],color=MUTED,wraplength=720 if action=='approve' else 580,justify='left').pack(fill='x',padx=24)
        editor=None
        checked=tk.BooleanVar(value=False)
        if action=='approve':
            doc=self.reader(dialog)
            replace_text(doc,self.state.get('document','')+'\n\n승인 대상 해시: '+str(payload['hash']))
            ttk.Checkbutton(dialog,text='전체 기획안과 검토 내용을 확인하고 표시된 버전을 승인합니다.',variable=checked).pack(anchor='w',padx=24,pady=10)
        elif action in TEXT_ACTIONS:
            self.label(dialog,'내용 · 판단 근거',color=MUTED).pack(anchor='w',padx=24,pady=(18,5))
            editor=tk.Text(dialog,height=6,wrap='word',relief='solid',bd=1,font=('맑은 고딕',10));editor.pack(fill='both',expand=True,padx=24)
        row=tk.Frame(dialog,bg='white');row.pack(side='bottom',fill='x',padx=24,pady=20)
        def confirm():
            if action=='approve' and not checked.get():messagebox.showinfo('검토 확인','기획 검토 확인란을 선택하세요.',parent=dialog);return
            content=editor.get('1.0','end-1c').strip() if editor else ''
            if action in TEXT_ACTIONS and not 1<=len(content)<=12000:
                messagebox.showerror('입력 확인','내용을 1~12,000자로 입력하세요.',parent=dialog);return
            payload['text']=content
            dialog.destroy();self.submit(payload)
        tk.Button(row,text='취소',command=dialog.destroy,relief='flat',padx=20,pady=9).pack(side='right')
        tk.Button(row,text=NAMES[action],command=confirm,bg=PURPLE,fg='white',relief='flat',padx=20,pady=9).pack(side='right',padx=10)

    def submit(self,payload):
        self.pending_submit=True;self.enable(set())
        def send():
            try:self.backend.submit(payload);self.mailbox.put(('submitted',))
            except ValueError as exc:self.mailbox.put(('submit_error',str(exc)))
            except Exception:self.mailbox.put(('submit_error','요청 처리 실패: 설정과 실행 상태를 확인하세요.'))
        threading.Thread(target=send,daemon=True).start()

    def close(self):
        with self.backend.mutex:busy=any(j['status']=='running' for j in self.backend.jobs)
        busy=busy or self.pending_submit or (self.state or {}).get('active') or (self.state or {}).get('running')
        if busy:
            messagebox.showinfo('작업 진행 중','먼저 「중단 요청」을 누르고 현재 단계와 요청 처리가 끝날 때까지 기다려 주세요. 작업을 강제 종료하지 않고 창을 유지합니다.',parent=self.root)
            return
        self.closed=True;self.root.destroy()


def instance_handle(identity):
    if os.name!='nt':return None
    kernel=ctypes.WinDLL('kernel32',use_last_error=True)
    kernel.CreateMutexW.argtypes=[ctypes.c_void_p,ctypes.c_bool,ctypes.c_wchar_p]
    kernel.CreateMutexW.restype=ctypes.c_void_p
    kernel.CloseHandle.argtypes=[ctypes.c_void_p]
    handle=kernel.CreateMutexW(None,False,'Local\\HanJariDesktop-'+hashlib.sha256(identity.encode()).hexdigest()[:24])
    error=ctypes.get_last_error()
    if not handle:raise OSError('Desktop instance lock failed')
    if error==183:
        kernel.CloseHandle(handle)
        raise ValueError('한자리 스튜디오가 이미 실행 중입니다. 작업 표시줄에서 기존 창을 열어 주세요.')
    return kernel,handle


def main():
    parser=argparse.ArgumentParser(description='HanJari native desktop app')
    parser.add_argument('--config',type=Path,default=ROOT/'config.json')
    parser.add_argument('--mode',choices=['live','mock'],default='live')
    args=parser.parse_args()
    if os.name=='nt':
        ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID('HanJari.Automation.Studio')
        try:ctypes.windll.shcore.SetProcessDpiAwareness(1)
        except OSError:pass
    root=tk.Tk();root.withdraw()
    handle=None
    try:
        handle=instance_handle(str(args.config.resolve()).casefold()+':'+args.mode)
        app=Desktop(root,Backend(args.config,args.mode))
        root.deiconify();root.mainloop()
    except Exception as exc:
        messagebox.showerror('한자리 스튜디오',str(exc),parent=root)
    finally:
        if handle:handle[0].CloseHandle(handle[1])


if __name__=='__main__':main()
