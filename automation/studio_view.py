"""Claude Design mockup adapted to live native Tk controls.

No demo data, remote design runtime, fonts or simulated model completions.
"""
import hashlib
import tkinter as tk
from tkinter import ttk, messagebox
from datetime import datetime
import desktop as ui

CARD='#141B18'
PANEL='#19211E'
LINE='#26312C'
AMBER='#EBC07A'
ERROR='#F2968A'
MINT=ui.PURPLE
NAV=[('overview','진행 현황'),('plan','기획과 결정'),('features','기능별 업무'),('review','리뷰와 검증'),('activity','활동·요청·복구')]


def busy(s):return bool(s.get('active') or s.get('running') or s.get('busy'))


def status_chip(s):
    if s.get('pause_requested'):
        return ('Ⅱ 중단 요청됨' if busy(s) else '■ 중단됨',AMBER)
    if (s.get('run_guard') or {}).get('latched'):return ('! 반복 방지 작동',ERROR)
    if busy(s):return ('● 처리 중',MINT)
    if s['stage']=='needs_user':return ('! 판단 필요',AMBER)
    if s['stage']=='pr_ready':return ('◉ 코드 리뷰 대기',AMBER)
    if s['stage']=='awaiting_plan_approval' and not s.get('approved'):return ('◷ 승인 대기',AMBER)
    return ('○ 실행 대기',ui.MUTED)


def usage_state(value):
    if not value:return ('값 없음',ui.MUTED)
    if not value['fresh']:return ('오래됨',AMBER)
    if value['five_hour']>=80 or value['seven_day']>=70:return ('기준 도달',ERROR)
    return ('최신 값',MINT)


def control_actions(s):
    actions=ui.allowed_actions(s)
    if s.get('questions'):actions.discard('approve')
    if (s.get('run_guard') or {}).get('latched'):actions.discard('run')
    else:actions.discard('reset-breaker')
    if not any(r.get('status')=='needs_user' for r in s.get('receipts',[])):actions.discard('retry-sync')
    if s['stage']=='pr_ready':actions.discard('run')
    # Usage gating belongs to Claude work only. Planning/reviews remain Codex jobs.
    if s.get('approved') and s['stage'] in ('implement','awaiting_plan_approval','plan') and usage_state(s.get('usage'))[0]!='최신 값':
        actions.discard('run')
    return actions


def owner(s):
    if s.get('active') or s.get('running'):
        return ('Claude Code','개발 · 개발 테스트') if s['stage']=='implement' else ('Codex',ui.STAGES.get(s['stage'],'기획·검토'))
    if s.get('busy'):return ('제어기','요청 처리 중')
    if s['stage'] in ('awaiting_plan_approval','needs_user','pr_ready') and not (s['stage']=='awaiting_plan_approval' and s.get('approved')):
        return ('나','검토와 판단 차례')
    return ('대기','사용자의 수동 실행을 기다림')


def plan_identity(s):
    return (s.get('parent_revision'),s.get('candidate_hash'),hashlib.sha256(s.get('document','').encode()).hexdigest())


def feature_status(s,f):
    if f['task_id'] in s.get('accepted',[]):return '수락 완료'
    if f['task_id']==s.get('current_feature'):return '리뷰 대기' if s['stage']=='pr_ready' else '현재 작업'
    return '개발 전'


class ScrollBody(tk.Frame):
    def __init__(self,parent,bg=ui.BG):
        super().__init__(parent,bg=bg)
        self.canvas=tk.Canvas(self,bg=bg,highlightthickness=0)
        scrollbar=ttk.Scrollbar(self,command=self.canvas.yview)
        self.canvas.configure(yscrollcommand=scrollbar.set)
        scrollbar.pack(side='right',fill='y');self.canvas.pack(side='left',fill='both',expand=True)
        self.body=tk.Frame(self.canvas,bg=bg)
        window=self.canvas.create_window(0,0,window=self.body,anchor='nw')
        self.canvas.bind('<Configure>',lambda e:self.canvas.itemconfigure(window,width=e.width))
        self.body.bind('<Configure>',lambda e:self.canvas.configure(scrollregion=self.canvas.bbox('all')))


class Studio(ui.Desktop):
    def __init__(self,root,backend,poll=True):
        self.pinned_plan=None
        self.plan_stale=False
        self.feature_scope=None
        self.toc_signature=None
        self.wrap_widgets=[]
        self.last_project=None
        super().__init__(root,backend,poll)
        root.geometry('1440x900');root.minsize(1024,720)
        for number,(key,_) in enumerate(NAV,1):root.bind('<Control-Key-'+str(number)+'>',lambda e,k=key:self.show(k))
        root.bind('<Configure>',self.resize,add='+')
        root.bind('<MouseWheel>',self.wheel,add='+')

    def text_label(self,parent,text='',size=10,color=ui.INK,bold=False,wrap=True):
        label=self.label(parent,text,size=size,color=color,bold=bold,justify='left',wraplength=450 if wrap else 0)
        if wrap:self.wrap_widgets.append(label)
        return label

    def heading(self,parent,title,subtitle=None):
        self.text_label(parent,title,size=16,bold=True).pack(fill='x',pady=(0,6))
        if subtitle:self.text_label(parent,subtitle,size=10,color=ui.MUTED).pack(fill='x',pady=(0,16))

    def panel(self,parent,color=CARD,padding=18):
        shell=ui.RoundPanel(parent,color,padding=padding)
        return shell,shell.body

    def link_button(self,parent,text,command):
        return ui.RoundButton(parent,text=text,command=command,bg='#222C28',fg=ui.INK,padx=12,pady=7)

    def build(self):
        self.sidebar=tk.Frame(self.root,bg='#0B100E',width=220)
        self.sidebar.pack(side='left',fill='y');self.sidebar.pack_propagate(False)
        self.label(self.sidebar,'한자리',size=19,bold=True,color=ui.INK).pack(anchor='w',padx=20,pady=(20,2))
        self.label(self.sidebar,'AI 오케스트레이터',size=9,color=ui.MUTED).pack(anchor='w',padx=20,pady=(0,20))
        self.label(self.sidebar,'프로젝트',size=9,color=ui.MUTED).pack(anchor='w',padx=18,pady=(0,6))
        self.project_value=tk.StringVar();self.project_keys=[]
        self.selector=ttk.Combobox(self.sidebar,textvariable=self.project_value,state='readonly',width=19)
        self.selector.pack(fill='x',padx=12);self.selector.bind('<<ComboboxSelected>>',self.change_project)
        self.nav={}
        for i,(key,label) in enumerate(NAV,1):
            button=ui.RoundButton(self.sidebar,text=label,command=lambda k=key:self.show(k),anchor='w',bg='#0B100E',fg=ui.MUTED,padx=12,pady=11)
            button.pack(fill='x',padx=10,pady=(16 if i==1 else 4,0));self.nav[key]=button
        self.label(self.sidebar,'Ctrl+1 ~ 5  화면 이동',size=8,color='#80948A').pack(anchor='w',padx=20,pady=14)
        health=tk.Frame(self.sidebar,bg='#0B100E');health.pack(side='bottom',fill='x',padx=12,pady=14)
        usage_shell,usage_body=self.panel(health,CARD,12);usage_shell.pack(fill='x')
        self.label(usage_body,'Claude 사용량',size=10,bold=True).pack(anchor='w')
        self.usage_badge=self.label(usage_body,'값 없음',size=8,color=ui.MUTED);self.usage_badge.pack(anchor='w',pady=5)
        self.usage_values={};self.usage_canvases={}
        for key,name in [('five_hour','5시간'),('seven_day','주간')]:
            row=tk.Frame(usage_body,bg=CARD);row.pack(fill='x',pady=(6,3))
            self.label(row,name,size=9,color=ui.MUTED).pack(side='left')
            value=self.label(row,'—',size=9,bold=True);value.pack(side='right');self.usage_values[key]=value
            canvas=tk.Canvas(usage_body,bg=CARD,height=11,highlightthickness=0)
            canvas.pack(fill='x');self.usage_canvases[key]=canvas
        self.freshness=self.label(usage_body,'실행 전 갱신 확인',size=8,color=ui.MUTED,wraplength=165,justify='left');self.freshness.pack(fill='x',pady=(8,0))
        self.local_status=self.label(health,'● 제어기  상태 확인 중',size=8,color=ui.MUTED);self.local_status.pack(anchor='w',pady=(10,4))
        self.notion_status=self.label(health,'○ Notion  기록 확인 중',size=8,color=ui.MUTED);self.notion_status.pack(anchor='w')
        main=tk.Frame(self.root,bg=ui.BG);main.pack(side='left',fill='both',expand=True)
        top=tk.Frame(main,bg='#101714',padx=22,pady=12);top.pack(fill='x')
        self.breadcrumb=self.label(top,'진행 현황',size=11,bold=True);self.breadcrumb.pack(side='left')
        self.global_chip=self.label(top,'○ 연결 중',size=9,color=AMBER);self.global_chip.pack(side='left',padx=16)
        self.button(top,'sync').pack(side='right');self.button(top,'note').pack(side='right',padx=8)
        self.alert=tk.Label(main,bg='#33251D',fg=AMBER,text='',padx=20,pady=9,anchor='w',justify='left',wraplength=900)
        self.footer=self.label(main,'상태를 불러오는 중 · F5 새로고침',size=8,color='#80948A');self.footer.pack(side='bottom',fill='x',padx=24,pady=8)
        self.content=tk.Frame(main,bg=ui.BG);self.content.pack(fill='both',expand=True,padx=24,pady=(20,0))
        self.views={key:tk.Frame(self.content,bg=ui.BG) for key,_ in NAV}
        self.make_overview();self.make_plan();self.make_features();self.make_review();self.make_activity()
        self.show('overview')

    def make_overview(self):
        scroll=ScrollBody(self.views['overview']);scroll.pack(fill='both',expand=True);self.overview_canvas=scroll.canvas
        body=scroll.body
        hero,self.hero=self.panel(body,PANEL,20);hero.pack(fill='x',pady=(0,16))
        self.hero.columnconfigure(0,weight=3);self.hero.columnconfigure(1,weight=1,minsize=210)
        left=tk.Frame(self.hero,bg=PANEL);left.grid(row=0,column=0,sticky='nsew',padx=(0,22))
        self.hero_chip=self.text_label(left,'◷ 승인 대기',size=9,color=AMBER);self.hero_chip.pack(fill='x')
        self.hero_title=self.text_label(left,'상태를 확인하고 있어요',size=19,bold=True);self.hero_title.pack(fill='x',pady=(10,8))
        self.hero_description=self.text_label(left,'',color=ui.MUTED);self.hero_description.pack(fill='x')
        row=tk.Frame(left,bg=PANEL);row.pack(fill='x',pady=(14,10))
        self.primary=ui.RoundButton(row,text='기획안 검토하기',command=self.follow_next,bg=MINT,fg='#062018',activebackground='#9BEDD0',activeforeground='#062018',padx=14,pady=9)
        self.primary.pack(side='left')
        self.hero_secondary=row
        self.hero_revise=self.button(row,'revise');self.hero_revise.pack(side='left',padx=7)
        self.hero_pause=self.button(row,'pause')
        self.block_reason=self.text_label(left,'',size=9,color=ui.MUTED);self.block_reason.pack(fill='x')
        person=tk.Frame(self.hero,bg=PANEL,padx=16);person.grid(row=0,column=1,sticky='nsew')
        self.label(person,'지금 담당',size=9,color=ui.MUTED).pack(anchor='w')
        self.owner_name=self.text_label(person,'—',size=17,bold=True);self.owner_name.pack(fill='x',pady=(12,4))
        self.owner_role=self.text_label(person,'',size=9,color=ui.MUTED);self.owner_role.pack(fill='x')
        self.owner_details=self.text_label(person,'',size=9,color=ui.MUTED);self.owner_details.pack(fill='x',pady=(16,0))
        flow,self.flow_body=self.panel(body);flow.pack(fill='x',pady=(0,16))
        self.label(self.flow_body,'전체 단계',size=11,bold=True).pack(anchor='w')
        self.flow=tk.Canvas(self.flow_body,bg=CARD,height=92,highlightthickness=0);self.flow.pack(fill='x',pady=(4,0))
        self.flow.bind('<Configure>',lambda e:self.paint_flow())
        self.flow_note=self.text_label(self.flow_body,'기능별 개발·리뷰·검증을 반복합니다.',size=8,color=ui.MUTED);self.flow_note.pack(fill='x')
        self.summary_grid=tk.Frame(body,bg=ui.BG);self.summary_grid.pack(fill='x')
        self.summary_grid.columnconfigure(0,weight=1);self.summary_grid.columnconfigure(1,weight=1)
        self.latest_shell,latest=self.panel(self.summary_grid);self.latest_shell.grid(row=0,column=0,sticky='nsew',padx=(0,8))
        self.label(latest,'최신 검토 결과',size=11,bold=True).pack(anchor='w')
        self.latest_badge=self.text_label(latest,'기록 없음',size=9,color=ui.MUTED);self.latest_badge.pack(fill='x',pady=(8,6))
        self.latest_text=self.text_label(latest,'',color=ui.INK);self.latest_text.pack(fill='x')
        self.link_button(latest,'검토 근거 보기 →',lambda:self.show('review')).pack(anchor='w',pady=(12,0))
        self.attention_shell,attention=self.panel(self.summary_grid);self.attention_shell.grid(row=0,column=1,sticky='nsew',padx=(8,0))
        self.label(attention,'주의가 필요한 항목',size=11,bold=True).pack(anchor='w')
        self.attention_text=self.text_label(attention,'',color=ui.MUTED);self.attention_text.pack(fill='x',pady=(12,0))
        recent_shell,recent=self.panel(body);recent_shell.pack(fill='x',pady=16)
        self.label(recent,'최근 활동',size=11,bold=True).pack(anchor='w')
        self.recent_text=self.text_label(recent,'',size=9,color=ui.MUTED);self.recent_text.pack(fill='x',pady=8)
        self.link_button(recent,'전체 기록 보기 →',lambda:self.show('activity')).pack(anchor='w')

    def make_plan(self):
        view=self.views['plan']
        self.heading(view,'기획과 결정','문서를 검토한 뒤 승인하거나 수정 사항을 남겨 주세요.')
        bar=tk.Frame(view,bg=ui.BG);bar.pack(fill='x',pady=(0,10))
        self.plan_version=self.label(bar,'기획안',size=10,bold=True);self.plan_version.pack(side='left')
        self.search_var=tk.StringVar();self.search_entry=ttk.Entry(bar,textvariable=self.search_var,width=23)
        self.search_entry.pack(side='right');self.search_entry.bind('<Return>',lambda e:self.search_plan())
        self.link_button(bar,'다음 찾기',self.search_plan).pack(side='right',padx=6)
        self.search_status=self.label(bar,'Ctrl+F',size=8,color=ui.MUTED);self.search_status.pack(side='right',padx=8)
        self.plan_panes=tk.PanedWindow(view,orient='horizontal',bg=ui.BG,sashwidth=7,bd=0)
        self.plan_panes.pack(fill='both',expand=True)
        self.toc_frame=tk.Frame(self.plan_panes,bg=CARD)
        self.label(self.toc_frame,'문서 목차',size=10,bold=True).pack(anchor='w',padx=12,pady=12)
        self.toc=tk.Listbox(self.toc_frame,bg=CARD,fg=ui.MUTED,selectbackground='#263D32',selectforeground=ui.INK,relief='flat',bd=0,highlightthickness=0,exportselection=False,font=('맑은 고딕',9))
        self.toc.pack(fill='both',expand=True,padx=6,pady=6);self.toc.bind('<<ListboxSelect>>',self.jump_heading)
        self.plan_panes.add(self.toc_frame,width=180,minsize=120)
        doc=tk.Frame(self.plan_panes,bg=PANEL);self.plan_panes.add(doc,minsize=260,stretch='always')
        self.plan_text=self.reader(doc)
        self.decision_scroll=ScrollBody(self.plan_panes,bg=PANEL);self.plan_panes.add(self.decision_scroll,width=290,minsize=235)
        decision=self.decision_scroll.body
        self.decision_summary=self.text_label(decision,'',size=10,bold=True);self.decision_summary.pack(fill='x',padx=15,pady=16)
        self.read_check=tk.BooleanVar(value=False)
        ttk.Checkbutton(decision,text='전체 기획안과 검토를\n확인했어요',variable=self.read_check,command=self.refresh_controls).pack(anchor='w',padx=12,pady=8)
        self.button(decision,'approve',True).pack(fill='x',padx=14,pady=7)
        self.button(decision,'revise').pack(fill='x',padx=14,pady=7)
        self.reload_plan=self.link_button(decision,'최신 버전 다시 열기',self.adopt_plan);self.reload_plan.pack(fill='x',padx=14,pady=7)
        self.hash_button=self.link_button(decision,'승인 대상 해시 보기',self.toggle_hash);self.hash_button.pack(fill='x',padx=14,pady=7)
        self.hash_text=self.text_label(decision,'',size=8,color=ui.MUTED);self.hash_text.pack(fill='x',padx=14,pady=6)
        self.decision_text=self.text_label(decision,'',size=9,color=ui.MUTED);self.decision_text.pack(fill='x',padx=14,pady=(10,20))

    def make_features(self):
        view=self.views['features'];self.heading(view,'기능별 업무','한 번에 하나의 작은 기능을 개발하고, 검토 후 다음 기능으로 이동합니다.')
        bar=tk.Frame(view,bg=ui.BG);bar.pack(fill='x',pady=(0,12))
        self.feature_filter=tk.StringVar();entry=ttk.Entry(bar,textvariable=self.feature_filter);entry.pack(side='left',fill='x',expand=True)
        self.feature_filter.trace_add('write',self.filter_features)
        self.feature_scope=tk.StringVar(value='전체')
        select=ttk.Combobox(bar,textvariable=self.feature_scope,values=['전체','개발 전','현재 작업','리뷰 대기','수락 완료'],state='readonly',width=12)
        select.pack(side='left',padx=8);select.bind('<<ComboboxSelected>>',self.filter_features)
        self.feature_count=self.label(bar,'',size=9,color=ui.MUTED);self.feature_count.pack(side='right')
        panes=tk.PanedWindow(view,orient='horizontal',bg=ui.BG,sashwidth=8,bd=0);panes.pack(fill='both',expand=True)
        listing=tk.Frame(panes,bg=CARD);panes.add(listing,width=430,minsize=270)
        self.tree=ttk.Treeview(listing,columns=('id','title','status'),show='headings',selectmode='browse')
        for key,title,width in [('id','업무',75),('title','기능',210),('status','상태',90)]:
            self.tree.heading(key,text=title);self.tree.column(key,width=width,minwidth=55,stretch=key=='title')
        scroll=ttk.Scrollbar(listing,command=self.tree.yview);self.tree.configure(yscrollcommand=scroll.set)
        scroll.pack(side='right',fill='y');self.tree.pack(fill='both',expand=True)
        self.tree.bind('<<TreeviewSelect>>',self.feature_selected)
        detail=tk.Frame(panes,bg=PANEL);panes.add(detail,minsize=270,stretch='always');self.feature_text=self.reader(detail)

    def make_review(self):
        view=self.views['review'];self.heading(view,'리뷰와 검증','자동 검증과 사용자 수락은 별개의 판단입니다. 아래 근거를 확인하세요.')
        metrics=tk.Frame(view,bg=ui.BG);metrics.pack(fill='x',pady=(0,12))
        self.review_metrics=[]
        for i,title in enumerate(['자동 테스트 · 최종 검증','독립 코드 리뷰','사용자 수락']):
            metrics.columnconfigure(i,weight=1)
            shell,body=self.panel(metrics);shell.grid(row=0,column=i,sticky='nsew',padx=(0,8))
            self.label(body,title,size=9,color=ui.MUTED).pack(anchor='w')
            value=self.label(body,'기록 없음',size=12,bold=True);value.pack(anchor='w',pady=(8,0));self.review_metrics.append(value)
        bar=tk.Frame(view,bg=ui.BG);bar.pack(fill='x',pady=(0,10))
        self.button(bar,'accept-feature',True).pack(side='left')
        self.link_button(bar,'판단 보류 · 메모 기록',lambda:self.action('note')).pack(side='left',padx=8)
        panes=tk.PanedWindow(view,orient='horizontal',bg=ui.BG,sashwidth=8,bd=0);panes.pack(fill='both',expand=True)
        files=tk.Frame(panes,bg=CARD);panes.add(files,width=290,minsize=210)
        self.label(files,'관련 파일 · 추천 읽기 순서',size=10,bold=True).pack(anchor='w',padx=14,pady=14)
        self.file_text=self.reader(files)
        details=tk.Frame(panes,bg=PANEL);panes.add(details,minsize=300,stretch='always');self.review_text=self.reader(details)

    def make_activity(self):
        view=self.views['activity'];self.heading(view,'활동·요청·복구','실행 기록과 실패 원인을 확인하고, 원인을 해결한 뒤 재시도하세요.')
        bar=tk.Frame(view,bg=ui.BG);bar.pack(fill='x',pady=(0,10))
        self.activity_mode=tk.StringVar(value='활동 기록')
        select=ttk.Combobox(bar,textvariable=self.activity_mode,values=['활동 기록','요청 처리','복구'],state='readonly',width=16)
        select.pack(side='left');select.bind('<<ComboboxSelected>>',lambda e:self.update_activity())
        self.recovery_check=tk.BooleanVar(value=False)
        ttk.Checkbutton(bar,text='실패 원인을 확인하고 해결했어요',variable=self.recovery_check,command=self.refresh_controls).pack(side='left',padx=16)
        recovery=tk.Frame(view,bg=ui.BG);recovery.pack(fill='x',pady=(0,12))
        self.button(recovery,'retry-sync').pack(side='left');self.button(recovery,'reset-breaker').pack(side='left',padx=8)
        self.activity_text=self.reader(view)

    def show(self,key):
        if key=='requests':key='activity'
        self.hide_tip();self.current_view=key
        for name,view in self.views.items():
            view.pack_forget();self.nav[name].configure(bg='#1B2922' if name==key else '#0B100E',fg=MINT if name==key else ui.MUTED)
        self.views[key].pack(fill='both',expand=True)
        if self.state:self.breadcrumb.configure(text=ui.project_label(self.state['project'])+'   ›   '+dict(NAV)[key])

    def resize(self,event=None):
        if event and event.widget is not self.root:return
        for label in self.wrap_widgets:
            if label.winfo_exists():
                width=label.winfo_width()
                if width>40:label.configure(wraplength=width)
        if self.root.winfo_width()<1200 and str(self.toc_frame) in self.plan_panes.panes():self.plan_panes.forget(self.toc_frame)
        elif self.root.winfo_width()>=1200 and str(self.toc_frame) not in self.plan_panes.panes():self.plan_panes.add(self.toc_frame,before=self.plan_panes.panes()[0],width=180,minsize=120)
        self.paint_usage()

    def wheel(self,event):
        if isinstance(event.widget,tk.Text):return
        if self.current_view=='overview':self.overview_canvas.yview_scroll(-int(event.delta/120),'units')

    def change_project(self,event=None):
        self.pinned_plan=None;self.plan_stale=False;self.read_check.set(False);self.recovery_check.set(False)
        self.last_project=None;self.state=None;self.stale=True;self.last_features=None
        self.enable(set());self.refresh()

    def paint_usage(self):
        value=(self.state or {}).get('usage')
        label,color=usage_state(value);self.usage_badge.configure(text=label,fg=color)
        for key,canvas in self.usage_canvases.items():
            self.usage_values[key].configure(text=str(value[key])+'%' if value else '—',fg=color)
            canvas.delete('all');width=max(30,canvas.winfo_width());limit=80 if key=='five_hour' else 70
            canvas.create_rectangle(0,3,width,8,fill='#2A3631',outline='')
            if value:canvas.create_rectangle(0,3,width*value[key]/100,8,fill=color,outline='')
            canvas.create_line(width*limit/100,0,width*limit/100,11,fill=ui.INK)
        self.freshness.configure(text=f"{value['age_seconds']//60}분 전 저장 · 기준 80% / 70%" if value else '실행 전 Claude Code에서 갱신')

    def paint_flow(self):
        if not hasattr(self,'flow'):return
        s=self.state or {};stage=s.get('stage','not_started')
        current={'not_started':0,'plan':1,'spec_review':2,'awaiting_plan_approval':3,'implement':4,'review':5,'final_test':6,'pr_ready':7}.get(stage,-1)
        if s.get('approved') and current==3:current=4
        features=s.get('features',[])
        if features and all(f['task_id'] in s.get('accepted',[]) for f in features):current=8
        names=['아이디어','기획 작성','독립 검토','전체 승인','개발·테스트','코드 리뷰','최종 검증','PR 준비','사용자 수락']
        self.flow.delete('all');width=max(650,self.flow.winfo_width());step=width/9
        for i,name in enumerate(names):
            x=step*(i+.5);color=MINT if i<current else AMBER if i==current else '#52655B'
            if i<8:self.flow.create_line(x,22,x+step,22,fill='#2A3631',width=2)
            self.flow.create_oval(x-9,13,x+9,31,fill=color if i<=current else CARD,outline=color,width=1)
            self.flow.create_text(x,22,text='✓' if i<current else str(i+1),fill='#062018' if i<=current else ui.MUTED,font=('맑은 고딕',8))
            self.flow.create_text(x,47,text=name,fill=ui.INK if i==current else ui.MUTED,font=('맑은 고딕',9),width=step-2)
        self.flow_note.configure(text='현재 기능: '+str(s.get('current_feature') or '기획 단계')+' · 기능별 개발–리뷰–검증을 반복합니다.')

    def render(self,s):
        self.state=s
        self.root.title('한자리 — '+ui.project_label(s['project']))
        self.breadcrumb.configure(text=ui.project_label(s['project'])+'   ›   '+dict(NAV)[self.current_view])
        label,color=status_chip(s);self.global_chip.configure(text=label,fg=color);self.hero_chip.configure(text=label,fg=color)
        title,description,_,button=ui.next_step(s)
        if s.get('pause_requested'):title='현재 단계를 마친 뒤 멈춰요' if busy(s) else '작업이 중단됐어요'
        if s.get('approved') and s['stage']=='awaiting_plan_approval':title='기획 승인 완료 · 실행을 기다려요'
        if (s.get('run_guard') or {}).get('latched'):title='반복 방지가 작동했어요';description='실패 원인과 조치 내용을 확인한 뒤 제한 해제 여부를 결정하세요.';button='원인 확인하기'
        self.hero_title.configure(text=title);self.hero_description.configure(text=description)
        self.primary.configure(text=button)
        name,role=owner(s);self.owner_name.configure(text=name);self.owner_role.configure(text=role)
        self.owner_details.configure(text='검토 대상\n기획 r'+str(s.get('parent_revision','—'))+'\n\n현재 단계\n'+ui.STAGES.get(s['stage'],s['stage']))
        self.hero_pause.pack_forget();self.hero_revise.pack_forget()
        (self.hero_pause if busy(s) else self.hero_revise).pack(side='left',padx=7)
        why='전체 기획을 승인하기 전에는 개발을 시작하지 않습니다.' if 'approve' in ui.allowed_actions(s) else '기획 승인과 실행, 기능 수락과 PR 병합은 각각 별개의 행동입니다.'
        if busy(s):why='중단 요청 → 현재 단계 완료 → 중단됨. 즉시 강제 종료하지 않습니다.'
        if s.get('approved') and 'run' not in control_actions(s) and s['stage']!='pr_ready':why='실행 전 사용량·현재 작업·반복 방지 상태를 확인해 주세요.'
        self.block_reason.configure(text=why)
        self.paint_usage();self.paint_flow()
        results=s.get('results',[])
        latest=next((r for r in reversed(results) if r['stage'] in ('spec_review','review','final_test')),None)
        self.latest_badge.configure(text=(ui.STAGES.get(latest['stage'],latest['stage'])+' · '+{'pass':'통과','changes':'수정 필요','blocked':'중단'}.get(latest['outcome'],latest['outcome'])) if latest else '검토 기록 없음',fg=MINT if latest and latest['outcome']=='pass' else ui.MUTED)
        self.latest_text.configure(text=latest['summary'] if latest else '기획과 검토가 진행되면 실제 결과를 표시합니다.')
        warnings=[]
        if s.get('questions'):warnings.append(f"! 미결정 질문 {len(s['questions'])}건 · 기획과 결정에서 확인")
        if s.get('receipts'):warnings.append(f"! 문서 전송 미완료 {len(s['receipts'])}건 · 복구 화면 확인")
        if usage_state(s.get('usage'))[0]!='최신 값':warnings.append('! Claude 사용량 '+usage_state(s.get('usage'))[0])
        if (s.get('run_guard') or {}).get('latched'):warnings.append('! 반복 방지 작동 · 원인 확인 필요')
        self.attention_text.configure(text='\n\n'.join(warnings) or '현재 기록에서 확인된 주의 항목이 없습니다.')
        events=sorted(s.get('events',[]),key=lambda e:e['at'],reverse=True)
        recent=[]
        for event in events[:4]:
            line=' '.join(self.event_line(event).split())
            recent.append(line[:150]+'…' if len(line)>150 else line)
        self.recent_text.configure(text='\n\n'.join(recent) or '아직 활동 기록이 없습니다.')
        self.local_status.configure(text='● 제어기  상태 파일 읽음',fg=MINT)
        self.notion_status.configure(text='! Notion  전송 미완료' if s.get('receipts') else '○ Notion  미완료 기록 없음',fg=AMBER if s.get('receipts') else ui.MUTED)
        self.update_plan();self.update_features();self.update_review();self.update_activity();self.refresh_controls()
        self.footer.configure(text='● 로컬 상태 확인 '+datetime.now().strftime('%H:%M:%S')+'   ·   F5 새로고침 / Ctrl+F 문서 검색')
        self.resize()

    @staticmethod
    def event_line(e):
        try:stamp=datetime.fromisoformat(e['at']).astimezone().strftime('%m.%d %H:%M')
        except ValueError:stamp=e['at']
        return stamp+'  ·  '+e['message']

    def update_plan(self):
        s=self.state
        if self.pinned_plan is None:self.pinned_plan=(plan_identity(s),s.get('document',''),s.get('candidate_hash'))
        self.plan_stale=self.pinned_plan[0]!=plan_identity(s)
        self.plan_version.configure(text='기획 r'+str(self.pinned_plan[0][0] or '—')+' · '+('새 버전 도착' if self.plan_stale else '승인됨' if s.get('approved') else '초안'))
        if self.plan_stale:
            self.read_check.set(False);self.alert.configure(text='새 기획 버전이 도착했습니다. 현재 문서는 그대로 보존됩니다. 「최신 버전 다시 열기」로 검토한 뒤 승인하세요.')
            if not self.alert.winfo_manager():self.alert.pack(before=self.content,fill='x')
        else:self.alert.pack_forget()
        ui.replace_text(self.plan_text,self.pinned_plan[1] or '# 기획안이 아직 없어요\n진행 현황에서 기획을 시작하면 문서가 표시됩니다.')
        document=self.pinned_plan[1]
        if document!=self.toc_signature:
            self.toc.delete(0,'end');self.heading_positions=[]
            start='1.0'
            for line in document.splitlines():
                if line.startswith('## '):
                    title=line[3:].strip();self.toc.insert('end',title)
                    found=self.plan_text.search(title,start,stopindex='end')
                    self.heading_positions.append(found or '1.0');start=found+'+1c' if found else start
            self.toc_signature=document
        self.decision_summary.configure(text='전체 기획 승인\n\n'+('새 버전 재검토 필요' if self.plan_stale else '승인 완료' if s.get('approved') else f"미결정 질문 {len(s.get('questions',[]))}건"))
        constraints=s.get('confirmed_constraints',[]);proposals=s.get('proposals',[])
        self.decision_text.configure(text='승인해도 개발은 자동으로 시작하지 않습니다.\n\n사용자 확정\n'+('\n• '.join(['']+constraints) if constraints else '별도 확정 기록 없음')+'\n\nAI 판단·제안\n'+('\n• '.join(['']+proposals) if proposals else '별도 제안 기록 없음')+'\n\n미결정 질문\n'+('\n'.join(s.get('questions',[])) or '없음'))

    def adopt_plan(self):
        self.pinned_plan=None;self.plan_stale=False;self.read_check.set(False);self.hash_text.configure(text='')
        self.search_query=None;self.search_end='1.0';self.update_plan();self.plan_text.yview_moveto(0);self.refresh_controls()

    def toggle_hash(self):self.hash_text.configure(text='' if self.hash_text.cget('text') else str(self.pinned_plan[2] or '승인 대상 없음'))

    def jump_heading(self,event=None):
        selected=self.toc.curselection()
        if selected:self.plan_text.see(self.heading_positions[selected[0]])

    def search_plan(self):
        query=self.search_var.get().strip();self.plan_text.tag_remove('match','1.0','end')
        if not query:self.search_status.configure(text='검색어 입력');return
        matches=[];start='1.0'
        while True:
            found=self.plan_text.search(query,start,stopindex='end',nocase=True)
            if not found:break
            matches.append(found);self.plan_text.tag_add('match',found,f'{found}+{len(query)}c');start=f'{found}+{len(query)}c'
        self.search_index=(getattr(self,'search_index',-1)+1)%len(matches) if matches and query==getattr(self,'search_query',None) else 0
        self.search_query=query
        if matches:self.plan_text.see(matches[self.search_index]);self.search_status.configure(text=f'{self.search_index+1} / {len(matches)}곳')
        else:self.search_status.configure(text='결과 없음')

    def update_features(self):
        s=self.state;query=self.feature_filter.get().strip().casefold();scope=self.feature_scope.get()
        features=[f for f in s.get('features',[]) if query in (f['task_id']+' '+f['title']+' '+f['requirements']).casefold() and (scope=='전체' or feature_status(s,f)==scope)]
        self.feature_count.configure(text=f"{len(features)} / {len(s.get('features',[]))}개")
        signature=repr(features)+repr(s.get('accepted'))+str(s.get('current_feature'))+s['stage']
        if signature==self.last_features:return
        old=self.tree.selection();self.tree.delete(*self.tree.get_children())
        for f in features:self.tree.insert('','end',iid=f['task_id'],values=(f['task_id'],f['title'],feature_status(s,f)))
        selected=old[0] if old and self.tree.exists(old[0]) else features[0]['task_id'] if features else None
        if selected:self.tree.selection_set(selected)
        self.last_features=signature;self.feature_selected()
        if not features:ui.replace_text(self.feature_text,'# 검색 결과가 없어요\n검색어나 상태 필터를 변경해 주세요.' if s.get('features') else '# 기능이 아직 없어요\n기획을 작성하면 기능별 업무가 표시됩니다.')

    def filter_features(self,*args):
        if self.state:self.update_features()

    def update_review(self):
        s=self.state;results=s.get('results',[])
        for index,stage in enumerate(['final_test','review']):
            result=next((r for r in reversed(results) if r['stage']==stage),None)
            value={'pass':'✓ 통과','changes':'! 수정 필요','blocked':'! 중단'}.get(result['outcome'],'확인 필요') if result else '기록 없음'
            self.review_metrics[index].configure(text=value,fg=MINT if result and result['outcome']=='pass' else ui.MUTED)
        accepted=s.get('current_feature') in s.get('accepted',[]) if s.get('current_feature') else False
        self.review_metrics[2].configure(text='✓ 수락 완료' if accepted else '수락 기록 없음',fg=MINT if accepted else ui.MUTED)
        files=[];lines=[]
        for result in reversed(results):
            lines+=['## '+ui.STAGES.get(result['stage'],result['stage'])+' · '+result['outcome'],result['summary'],'\n검증 근거']+result.get('evidence',[])+result.get('artifact',{}).get('findings',[])+['']
            for file in result.get('review_files',[]):
                if file not in files:files.append(file)
        ui.replace_text(self.review_text,'# 검토와 검증 기록\n실행 시 저장된 결과입니다. 이 화면에서 테스트를 재실행하지 않습니다.\n\n'+'\n'.join(lines))
        ui.replace_text(self.file_text,'\n\n'.join(f'{i+1}. {file}' for i,file in enumerate(files)) or '아직 관련 파일 기록이 없습니다.')

    def update_activity(self):
        if not self.state:return
        s=self.state;mode=self.activity_mode.get()
        if mode=='활동 기록':text='\n\n'.join(self.event_line(e) for e in sorted(s.get('events',[]),key=lambda e:e['at'],reverse=True)) or '아직 활동 기록이 없습니다.'
        elif mode=='요청 처리':
            text='\n\n'.join(ui.NAMES[j['action']]+' · '+{'running':'처리 중','completed':'처리 완료','failed':'실패'}[j['status']]+'\n'+j.get('log_file','') for j in reversed(s.get('jobs',[]))) or '이 앱에서 보낸 요청이 없습니다.'
        else:
            guard=s.get('run_guard') or {}
            text='반복 방지: '+('작동 중' if guard.get('latched') else '작동 기록 없음')+'\n원인: '+str(guard.get('reason') or '기록 없음')+'\n기록된 시도 횟수: '+str(guard.get('attempts','기록 없음'))+'\n\n문서 전송 미완료: '+str(len(s.get('receipts',[])))+'건\n\n원인 해결 후 확인란을 선택하고 조치 내용을 남겨 재시도를 허용하세요. 재시도 허용은 AI 실행이 아닙니다.'
        ui.replace_text(self.activity_text,'# '+mode+'\n\n'+text)

    def refresh_controls(self):
        if not self.state:return
        actions=control_actions(self.state)
        if self.plan_stale or not self.read_check.get():actions.discard('approve')
        if not self.recovery_check.get():actions-= {'retry-sync','reset-breaker'}
        if self.pending_submit:actions=set()
        self.enable(actions)

    def follow_next(self):
        if self.stale or not self.state:return
        if (self.state.get('run_guard') or {}).get('latched'):self.activity_mode.set('복구');self.update_activity();self.show('activity');return
        target=ui.next_step(self.state)[2]
        if target=='run':self.action('run')
        else:self.show(target)

    def action(self,action):
        if not self.state or self.stale or self.pending_submit:return
        if action not in control_actions(self.state):messagebox.showinfo('상태 확인','현재 작업·미결정 질문·사용량·반복 방지 상태를 확인해 주세요.',parent=self.root);return
        if action=='approve' and (self.plan_stale or not self.read_check.get()):self.show('plan');return
        if action in ('retry-sync','reset-breaker') and not self.recovery_check.get():self.show('activity');return
        super().action(action)
