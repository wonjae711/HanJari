"""Native Windows desktop UI. Uses the controller directly; no HTTP server."""
import argparse
import ctypes
import hashlib
import os
from pathlib import Path
import queue
import re
import threading
import tkinter as tk
from tkinter import ttk, messagebox
from tkinter import font as tkfont
from datetime import datetime
from dashboard import Backend, ROOT, TEXT_ACTIONS

BG = '#101514'
INK = '#e3eee9'
MUTED = '#a1b6ac'
PURPLE = '#73e2bb'
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


def dark_titlebar(window):
    """Theme only this app's Windows frame; never change global OS settings.

    DWM attributes: https://learn.microsoft.com/windows/win32/api/dwmapi/ne-dwmapi-dwmwindowattribute
    Explicit caption/text colors keep the frame dark even with a light OS theme.
    Unsupported attributes are harmless on older Windows versions.
    """
    if os.name!='nt' or not window.winfo_exists():return False
    try:
        window.update_idletasks()
        user32=ctypes.WinDLL('user32')
        user32.GetAncestor.argtypes=[ctypes.c_void_p,ctypes.c_uint]
        user32.GetAncestor.restype=ctypes.c_void_p
        hwnd=user32.GetAncestor(window.winfo_id(),2)
        if not hwnd:return False
        dwm=ctypes.WinDLL('dwmapi')
        dwm.DwmSetWindowAttribute.argtypes=[ctypes.c_void_p,ctypes.c_uint,ctypes.c_void_p,ctypes.c_uint]
        dwm.DwmSetWindowAttribute.restype=ctypes.c_long
        def color(hex_value):
            r,g,b=(int(hex_value[i:i+2],16) for i in (1,3,5))
            return r | g<<8 | b<<16
        results=[]
        for attribute,value in [(20,1),(34,color(BG)),(35,color(BG)),(36,color(INK))]:
            data=ctypes.c_uint32(value)
            results.append(dwm.DwmSetWindowAttribute(hwnd,attribute,ctypes.byref(data),ctypes.sizeof(data)))
        return results[2]==0
    except (OSError,tk.TclError):return False


def theme_window_frame(window):
    # Tk can recreate its native wrapper on mapping; apply to the mapped HWND.
    dark_titlebar(window)
    window.bind('<Map>',lambda event:dark_titlebar(window) if event.widget==window else None,add='+')


def rounded(canvas,x1,y1,x2,y2,r,**options):
    r=min(r,(x2-x1)/2,(y2-y1)/2)
    return canvas.create_polygon(x1+r,y1,x2-r,y1,x2,y1,x2,y1+r,x2,y2-r,x2,y2,x2-r,y2,
                                 x1+r,y2,x1,y2,x1,y2-r,x1,y1+r,x1,y1,smooth=True,splinesteps=24,**options)


class RoundButton(tk.Canvas):
    """Native Tk control with rounded corners, hover and keyboard activation."""
    def __init__(self,parent,**options):
        self.options=dict(text='',bg='#283a31',fg=INK,state='normal',command=None,
                          activebackground='#365946',activeforeground=INK,disabledforeground='#768b80',padx=16,pady=10,anchor='center')
        self.options.update(options)
        self.font=tkfont.Font(family='맑은 고딕',size=10)
        super().__init__(parent,bg=parent.cget('bg'),highlightthickness=0,bd=0,takefocus=1,cursor='hand2')
        self.hover=False;self.focused=False
        self.bind('<Configure>',lambda e:self.paint())
        self.bind('<Enter>',lambda e:self.over(True))
        self.bind('<Leave>',lambda e:self.over(False))
        self.bind('<ButtonRelease-1>',lambda e:self.activate())
        self.bind('<Return>',lambda e:self.activate())
        self.bind('<space>',lambda e:self.activate())
        self.bind('<FocusIn>',lambda e:self.focus(True))
        self.bind('<FocusOut>',lambda e:self.focus(False))
        self.resize()

    def resize(self):
        super().configure(width=self.font.measure(self.options['text'])+2*self.options['padx'],height=self.font.metrics('linespace')+2*self.options['pady'])
        self.paint()

    def configure(self,cnf=None,**kwargs):
        if cnf:kwargs.update(cnf)
        self.options.update(kwargs);self.resize()
    config=configure

    def cget(self,key):
        return self.options[key] if key in self.options else super().cget(key)

    def paint(self):
        if not self.winfo_exists():return
        self.delete('all')
        width=self.winfo_width() if self.winfo_width()>1 else int(super().cget('width'));height=int(super().cget('height'))
        disabled=self.options['state']=='disabled'
        fill=self.options['activebackground'] if self.hover and not disabled else self.options['bg']
        foreground=self.options['disabledforeground'] if disabled else self.options['activeforeground'] if self.hover else self.options['fg']
        rounded(self,1,1,width-1,height-1,12,fill=fill,outline=PURPLE if self.focused else fill,width=1)
        left=self.options.get('anchor')=='w'
        self.create_text(self.options['padx'] if left else width/2,height/2,text=self.options['text'],fill=foreground,font=self.font,anchor='w' if left else 'center')

    def over(self,value):self.hover=value;self.paint()
    def focus(self,value):self.focused=value;self.paint()
    def activate(self):
        if self.options['state']!='disabled' and self.options['command']:
            self.focus_set();self.options['command']()


class RoundPanel(tk.Canvas):
    def __init__(self,parent,fill,padding=16):
        super().__init__(parent,bg=parent.cget('bg'),highlightthickness=0,bd=0,height=20)
        self.fill=fill;self.padding=padding
        self.body=tk.Frame(self,bg=fill)
        self.window=self.create_window(padding,padding,window=self.body,anchor='nw')
        self.bind('<Configure>',self.layout)
        self.body.bind('<Configure>',self.layout)

    def layout(self,event=None):
        width=max(self.winfo_width(),40)
        self.itemconfigure(self.window,width=max(10,width-2*self.padding))
        height=self.body.winfo_reqheight()+2*self.padding
        if int(self.cget('height'))!=height:self.configure(height=height)
        self.delete('surface')
        rounded(self,0,0,width,height,18,fill=self.fill,outline=self.fill,tags='surface')
        self.tag_lower('surface')


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


def project_label(project):
    # Display alias only: stable project identity and approval hashes are preserved.
    return '영화 예매 MVP' if project['id']=='hanjari' else project['title']


def next_step(state):
    if not state: return ('상태를 확인하고 있어요', '로컬 프로젝트 기록을 불러옵니다.', 'requests', '처리 현황 보기')
    if state.get('busy') or state.get('active') or state.get('running'):
        return ('작업이 진행 중이에요', '필요하면 중단을 요청하거나 아이디어를 남겨 주세요.', 'requests', '처리 현황 보기')
    if state['stage']=='needs_user':
        return ('확인이 필요한 문제가 있어요', '검토 근거와 중단 사유를 확인한 뒤 다음 작업을 결정하세요.', 'review', '검토 근거 보기')
    if state['stage']=='pr_ready':
        return ('이번 기능을 리뷰해 주세요', '관련 파일과 테스트 근거를 확인하고 기능 수락 여부를 결정하세요.', 'review', '코드 리뷰 시작')
    if state['stage']=='awaiting_plan_approval' and not state.get('approved'):
        return ('기획안을 검토할 차례예요', '전체 범위와 인수 기준을 확인한 뒤 승인하거나 수정을 요청하세요.', 'plan', '기획안 검토하기')
    return ('다음 작업을 시작할 수 있어요', '실행 버튼을 누를 때만 AI가 작업합니다. 중요한 결정은 다시 확인합니다.', 'run', '다음 작업 실행')


def replace_text(widget, value):
    """Keep selection/scroll intact when the polled content has not changed."""
    if getattr(widget, '_content', None) == value: return
    position = widget.yview()[0]
    widget.configure(state='normal')
    widget.delete('1.0', 'end')
    code=False
    for line in value.splitlines(keepends=True):
        if line.strip().startswith('```'):
            code=not code
            continue
        heading=re.match(r'^(#{1,6})\s+(.*)',line.rstrip('\n')) if not code else None
        if heading:
            widget.insert('end',heading[2]+'\n','h1' if len(heading[1])==1 else 'h2')
        elif code:
            widget.insert('end',line,'code')
        else:
            # Support emphasis and inline code without evaluating HTML or links.
            for part in re.split(r'(\*\*[^*\n]+\*\*|`[^`\n]+`)',line):
                if part.startswith('**') and part.endswith('**') and len(part)>4:widget.insert('end',part[2:-2],'bold')
                elif part.startswith('`') and part.endswith('`') and len(part)>2:widget.insert('end',part[1:-1],'code')
                else:widget.insert('end',part)
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
        self.current_view='overview'
        root.title('한자리 — AI 오케스트레이터')
        root.geometry('1280x850')
        root.minsize(980, 720)
        root.configure(bg=BG)
        root.option_add('*Font', ('맑은 고딕', 10))
        root.protocol('WM_DELETE_WINDOW', self.close)
        icon = ROOT/'desktop_assets'/'hanjari.ico'
        if icon.exists(): root.iconbitmap(str(icon))
        self.style = ttk.Style(root)
        self.style.theme_use('clam')
        self.style.configure('TCombobox', padding=7, fieldbackground='#26392f', background='#26392f', foreground=INK, arrowcolor=INK)
        self.style.map('TCombobox', fieldbackground=[('readonly','#26392f')], foreground=[('readonly',INK)])
        self.style.configure('TEntry', fieldbackground='#26392f', foreground=INK, insertcolor=INK, bordercolor='#365946', padding=5)
        self.style.configure('TButton', background='#283a31', foreground=INK, padding=6, borderwidth=0)
        self.style.map('TButton', background=[('active','#365946')])
        self.style.configure('TCheckbutton', background='#19211e', foreground=INK)
        self.style.map('TCheckbutton', background=[('active','#19211e')], foreground=[('active',INK)])
        self.style.configure('Vertical.TScrollbar', background='#365946', troughcolor='#19211e', arrowcolor=INK, borderwidth=0)
        self.style.configure('Treeview', rowheight=32, background='#19211e', fieldbackground='#19211e', borderwidth=0, foreground=INK)
        self.style.configure('Treeview.Heading', font=('맑은 고딕', 10, 'bold'), background='#24332c', foreground=INK, padding=9)
        self.style.map('Treeview', background=[('selected', '#305b49')], foreground=[('selected', '#dcfff0')])
        self.style.configure('Horizontal.TProgressbar', background='#73e2bb', troughcolor='#283a31', borderwidth=0, thickness=6)
        self.buttons = {}
        self.build()
        theme_window_frame(root)
        root.bind('<F5>',lambda e:self.refresh())
        root.bind('<Control-f>',self.focus_search)
        if poll:
            self.root.after(50, self.refresh)
            self.root.after(100, self.drain)

    def label(self, parent, value='', size=10, color=INK, bold=False, bg=None, **kwargs):
        return tk.Label(parent, text=value, font=('맑은 고딕',size,'bold' if bold else 'normal'),
                        fg=color, bg=bg or parent.cget('bg'), anchor='w', **kwargs)

    def button(self, parent, action, primary=False):
        button = RoundButton(parent, text=NAMES[action], command=lambda:self.action(action), relief='flat', bd=0,
                           bg=PURPLE if primary else '#283a31', fg='#0a2b1b' if primary else '#c4e2d3',
                           activebackground='#365946', activeforeground=INK, disabledforeground='#768b80',
                           padx=14, pady=9, cursor='hand2', state='disabled')
        self.buttons.setdefault(action, []).append(button)
        button._primary=primary
        button.bind('<Enter>',lambda e:self.tooltip(button,action),add='+')
        button.bind('<Leave>',lambda e:self.hide_tip(),add='+')
        button.bind('<ButtonPress>',lambda e:self.hide_tip())
        return button

    def hide_tip(self):
        if getattr(self,'tip',None):
            self.tip.destroy();self.tip=None

    def tooltip(self,button,action):
        self.hide_tip()
        explanation=HELP[action]
        if button.cget('state')=='disabled':
            explanation=('기획안을 먼저 검토하고 승인해 주세요.' if action=='run' and self.state and self.state['stage']=='awaiting_plan_approval' else
                         '현재 단계 또는 요청 처리 중에는 사용할 수 없습니다. 진행 현황을 확인하세요.')
        tip=tk.Toplevel(self.root);tip.overrideredirect(True);self.tip=tip
        tk.Label(tip,text=explanation,bg='#30493c',fg='white',wraplength=340,justify='left',padx=12,pady=9).pack()
        tip.geometry('+'+str(min(button.winfo_rootx(),self.root.winfo_screenwidth()-380))+'+'+str(button.winfo_rooty()+button.winfo_height()+5))

    def reader(self, parent):
        frame=tk.Frame(parent,bg='#19211e')
        frame.pack(fill='both',expand=True)
        text=tk.Text(frame,wrap='word',relief='flat',borderwidth=0,padx=22,pady=18,
                     font=('맑은 고딕',11),background='#19211e',foreground=INK,spacing3=8,selectbackground='#365946',state='disabled')
        scrollbar=ttk.Scrollbar(frame,command=text.yview)
        text.configure(yscrollcommand=scrollbar.set)
        scrollbar.pack(side='right',fill='y');text.pack(side='left',fill='both',expand=True)
        text.tag_configure('h1',font=('맑은 고딕',17,'bold'),foreground='#a3f2d2',spacing1=10,spacing3=14)
        text.tag_configure('h2',font=('맑은 고딕',12,'bold'),foreground='#73e2bb',spacing1=14,spacing3=8)
        text.tag_configure('bold',font=('맑은 고딕',11,'bold'))
        text.tag_configure('code',font=('Consolas',10),background='#23362c',foreground='#bdebd5')
        text.tag_configure('match',background='#426f58',foreground=INK)
        text.bind('<Control-a>',lambda e:(text.tag_add('sel','1.0','end-1c'),'break')[-1])
        return text

    def build(self):
        sidebar=tk.Frame(self.root,bg='#0b100e',width=220)
        sidebar.pack(side='left',fill='y');sidebar.pack_propagate(False)
        self.label(sidebar,'한자리',size=25,bold=True,color='white').pack(anchor='w',padx=25,pady=(30,4))
        self.label(sidebar,'AI ORCHESTRATOR',size=8,color='#73e2bb').pack(anchor='w',padx=27)
        self.label(sidebar,'프로젝트 선택',size=9,color='#a1b6ac').pack(anchor='w',padx=25,pady=(28,9))
        self.project_value=tk.StringVar()
        self.selector=ttk.Combobox(sidebar,textvariable=self.project_value,state='readonly',width=17)
        self.selector.pack(padx=20,fill='x')
        self.selector.bind('<<ComboboxSelected>>',self.change_project)
        self.project_keys=[]
        self.nav={}
        for key,name in [('overview','진행 현황'),('plan','기획과 결정'),('features','기능별 업무'),('review','리뷰와 검증'),('activity','활동 기록'),('requests','요청 · 복구')]:
            button=RoundButton(sidebar,text=name,relief='flat',bg='#0b100e',fg='#a1b6ac',activebackground='#244c39',
                             activeforeground='#e3eee9',anchor='w',padx=16,pady=12,command=lambda k=key:self.show(k),cursor='hand2')
            button.pack(fill='x',padx=12,pady=(20 if not self.nav else 3,0));self.nav[key]=button
        self.label(sidebar,'●  내 PC에서 실행 중',size=8,color='#73e2bb').pack(side='bottom',anchor='w',padx=25,pady=25)
        main=tk.Frame(self.root,bg=BG,padx=26,pady=24);main.pack(side='left',fill='both',expand=True)
        header=tk.Frame(main,bg=BG);header.pack(fill='x')
        self.title=self.label(header,'작업 공간',size=20,bold=True);self.title.pack(side='left')
        self.mode=self.label(header,'모의 실행' if self.backend.mode=='mock' else '수동 실행 모드',color=PURPLE);self.mode.pack(side='right',padx=3)
        self.status=self.label(main,'상태를 불러오고 있습니다.',size=14,bold=True);self.status.pack(fill='x',pady=(17,6))
        self.hint=self.label(main,'앱을 열어도 AI 작업이 시작되지 않습니다.',color=MUTED);self.hint.pack(fill='x')
        actions=tk.Frame(main,bg=BG);actions.pack(fill='x',pady=(15,18))
        for action in ('run','pause','note','sync'):
            self.button(actions,action,action=='run').pack(side='left',padx=(0,8))
        self.content=tk.Frame(main,bg='#19211e',highlightthickness=0)
        self.footer=self.label(main,'연결 준비 중  ·  F5 새로고침 / Ctrl+F 문서 검색',size=9,color=MUTED)
        self.footer.pack(side='bottom',fill='x',pady=(12,0))
        self.content.pack(fill='both',expand=True)
        self.views={key:tk.Frame(self.content,bg='#19211e') for key in self.nav}
        overview_host=self.views['overview']
        self.overview_canvas=tk.Canvas(overview_host,bg='#19211e',highlightthickness=0)
        overview_scroll=ttk.Scrollbar(overview_host,command=self.overview_canvas.yview)
        self.overview_canvas.configure(yscrollcommand=overview_scroll.set)
        overview_scroll.pack(side='right',fill='y');self.overview_canvas.pack(side='left',fill='both',expand=True)
        overview=tk.Frame(self.overview_canvas,bg='#19211e')
        overview_window=self.overview_canvas.create_window(0,0,window=overview,anchor='nw')
        self.overview_canvas.bind('<Configure>',lambda e:self.overview_canvas.itemconfigure(overview_window,width=e.width))
        overview.bind('<Configure>',lambda e:self.overview_canvas.configure(scrollregion=self.overview_canvas.bbox('all')))
        self.root.bind('<MouseWheel>',lambda e:self.overview_canvas.yview_scroll(-int(e.delta/120),'units') if self.current_view=='overview' and not isinstance(e.widget,tk.Text) else None)
        callout_shell=RoundPanel(overview,'#203d2e',padding=18)
        callout_shell.pack(fill='x',padx=20,pady=(18,14))
        callout=callout_shell.body
        self.next_title=self.label(callout,'상태를 확인하고 있어요',size=15,bold=True)
        self.next_title.pack(anchor='w')
        self.next_description=self.label(callout,'',color=MUTED,wraplength=650,justify='left')
        self.next_description.pack(anchor='w',pady=(6,10))
        self.next_button=RoundButton(callout,text='기획안 검토하기',command=self.follow_next,bg=PURPLE,fg='#0a2b1b',activebackground='#a1f3d2',activeforeground='#e3eee9',relief='flat',padx=16,pady=8,cursor='hand2')
        self.next_button.pack(anchor='w')
        self.steps=tk.Frame(overview,bg='#19211e');self.steps.pack(fill='x',padx=20,pady=(0,12))
        self.step_labels=[]
        for i,name in enumerate(['기획','검토','승인','개발','검증','리뷰']):
            self.steps.columnconfigure(i,weight=1)
            label=self.label(self.steps,str(i+1)+'  '+name,color=MUTED,bg='#26392f',size=9,padx=8,pady=8)
            label.grid(row=0,column=i,sticky='ew',padx=(0,4));self.step_labels.append(label)
        cards=tk.Frame(overview,bg='#19211e');cards.pack(fill='x',padx=23,pady=6)
        self.usage_labels={};self.bars={}
        for col,(key,title) in enumerate([('five_hour','CLAUDE · 5시간 / 중단 기준 80%'),('seven_day','CLAUDE · 7일 / 중단 기준 70%')]):
            cards.columnconfigure(col,weight=1)
            card_shell=RoundPanel(cards,'#1e2c24',padding=16)
            card_shell.grid(row=0,column=col,sticky='nsew',padx=(0,12) if col==0 else (0,0))
            card=card_shell.body
            self.label(card,title,size=9,color=MUTED).pack(anchor='w')
            self.usage_labels[key]=self.label(card,'확인 필요',size=18,bold=True)
            self.usage_labels[key].pack(anchor='w',pady=6)
            self.bars[key]=ttk.Progressbar(card,maximum=100);self.bars[key].pack(fill='x')
        self.freshness=self.label(overview,'사용량 갱신 시각 확인 중',color=MUTED,size=9)
        self.freshness.pack(anchor='w',padx=23,pady=(4,12))
        self.overview_text=self.reader(overview)
        self.overview_text.configure(height=12)
        toolbar=tk.Frame(self.views['plan'],bg='#19211e',padx=18,pady=12);toolbar.pack(fill='x')
        self.button(toolbar,'approve',True).pack(side='left',padx=(0,8));self.button(toolbar,'revise').pack(side='left')
        searchbar=tk.Frame(self.views['plan'],bg='#1e2c24',padx=18,pady=9);searchbar.pack(fill='x')
        self.label(searchbar,'문서 검색',size=9).pack(side='left',padx=(0,10))
        self.search_var=tk.StringVar()
        self.search_entry=ttk.Entry(searchbar,textvariable=self.search_var,width=26)
        self.search_entry.pack(side='left',fill='x',expand=True)
        self.search_entry.bind('<Return>',lambda e:self.search_plan())
        ttk.Button(searchbar,text='다음 찾기',command=self.search_plan).pack(side='left',padx=7)
        self.search_status=self.label(searchbar,'Ctrl+F',size=9,color=MUTED);self.search_status.pack(side='left')
        self.plan_text=self.reader(self.views['plan'])
        featurebar=tk.Frame(self.views['features'],bg='#19211e',padx=18,pady=12);featurebar.pack(fill='x')
        self.label(featurebar,'기능 검색',size=9).pack(side='left',padx=(0,10))
        self.feature_filter=tk.StringVar()
        self.feature_entry=ttk.Entry(featurebar,textvariable=self.feature_filter)
        self.feature_entry.pack(side='left',fill='x',expand=True)
        self.feature_filter.trace_add('write',self.filter_features)
        self.feature_count=self.label(featurebar,'',color=MUTED,size=9);self.feature_count.pack(side='left',padx=10)
        treeframe=tk.Frame(self.views['features'],bg='#19211e');treeframe.pack(fill='x',padx=18,pady=18)
        self.tree=ttk.Treeview(treeframe,columns=('id','title','status'),show='headings',height=7,selectmode='browse')
        for key,title,width in [('id','업무',90),('title','기능',410),('status','상태',140)]:
            self.tree.heading(key,text=title);self.tree.column(key,width=width,minwidth=60,stretch=key=='title')
        sb=ttk.Scrollbar(treeframe,command=self.tree.yview);self.tree.configure(yscrollcommand=sb.set)
        sb.pack(side='right',fill='y');self.tree.pack(fill='x',expand=True)
        self.tree.bind('<<TreeviewSelect>>',self.feature_selected)
        self.feature_text=self.reader(self.views['features'])
        reviewbar=tk.Frame(self.views['review'],bg='#19211e',padx=18,pady=12);reviewbar.pack(fill='x')
        self.button(reviewbar,'accept-feature',True).pack(side='left')
        self.review_text=self.reader(self.views['review'])
        self.activity_text=self.reader(self.views['activity'])
        recovery=tk.Frame(self.views['requests'],bg='#19211e',padx=18,pady=12);recovery.pack(fill='x')
        for action in ('retry-sync','reset-breaker'):self.button(recovery,action).pack(side='left',padx=(0,8))
        self.requests_text=self.reader(self.views['requests'])
        self.show('overview')

    def show(self, key):
        self.hide_tip()
        self.current_view=key
        for name,view in self.views.items():
            view.pack_forget();self.nav[name].configure(bg='#244c39' if name==key else '#0b100e',fg='white' if name==key else '#a1b6ac')
        self.views[key].pack(fill='both',expand=True)

    def follow_next(self):
        if self.stale or not self.state:return
        target=next_step(self.state)[2]
        if target=='run':self.action('run')
        else:self.show(target)

    def focus_search(self,event=None):
        self.show('plan');self.search_entry.focus_set();self.search_entry.select_range(0,'end')
        return 'break'

    def search_plan(self):
        query=self.search_var.get().strip()
        self.plan_text.tag_remove('match','1.0','end')
        if not query:self.search_status.configure(text='검색어 입력');return
        start=getattr(self,'search_end','1.0') if query==getattr(self,'search_query',None) else '1.0'
        index=self.plan_text.search(query,start,stopindex='end',nocase=True)
        if not index:index=self.plan_text.search(query,'1.0',stopindex='end',nocase=True)
        if index:
            end=f'{index}+{len(query)}c';self.plan_text.tag_add('match',index,end);self.plan_text.see(index)
            self.search_end=end;self.search_status.configure(text='찾음')
        else:self.search_end='1.0';self.search_status.configure(text='결과 없음')
        self.search_query=query

    def filter_features(self,*args):
        if self.state:self.last_features=None;self.render(self.state)

    def change_project(self, event=None):
        self.state=None;self.stale=True;self.last_features=None
        self.enable(set())
        self.refresh()

    def enable(self, actions):
        for action,buttons in self.buttons.items():
            for button in buttons:
                enabled=action in actions
                button.configure(state='normal' if enabled else 'disabled',bg=(PURPLE if button._primary else '#283a31') if enabled else '#222d26',fg='#0a2b1b' if button._primary and enabled else '#c4e2d3')

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
                    self.selector.configure(values=[project_label(p) for p in projects])
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
        self.title.configure(text=project_label(state['project']))
        stage=STAGES.get(state['stage'],state['stage'])
        if state.get('approved') and state['stage']=='awaiting_plan_approval':stage='기획 승인 완료 · 다음 기능 실행 가능'
        self.status.configure(text=stage)
        busy=state.get('active') or state.get('running') or state.get('busy')
        hint='중단 요청됨 · 현재 단계가 끝날 때까지 기다려 주세요.' if state.get('pause_requested') else (
            '처리 중 · 중단 요청과 아이디어 기록은 가능합니다.' if busy else '수동 활성화 대기 · 앱을 열어도 AI가 실행되지 않습니다.')
        self.hint.configure(text=hint)
        title,description,_,label=next_step(state)
        self.next_title.configure(text=title);self.next_description.configure(text=description);self.next_button.configure(text=label)
        stage_index={'plan':0,'spec_review':1,'awaiting_plan_approval':2,'implement':3,'review':3,'final_test':4,'pr_ready':5}.get(state['stage'],-1)
        if state.get('approved') and stage_index==2:stage_index=3
        for i,label_widget in enumerate(self.step_labels):
            label_widget.configure(bg=PURPLE if i==stage_index else '#253f30' if i<stage_index else '#26392f',fg='#0a2b1b' if i==stage_index else '#a1dabc' if i<stage_index else MUTED)
        self.enable(allowed_actions(state) if not self.pending_submit else set())
        usage=state.get('usage')
        for key in self.usage_labels:
            value=usage[key] if usage else 0
            limit=80 if key=='five_hour' else 70
            self.usage_labels[key].configure(text=str(value)+'%' if usage else '확인 필요',fg='#efbd7d' if usage and (not usage['fresh'] or value>=limit) else INK)
            self.bars[key]['value']=usage[key] if usage else 0
        self.freshness.configure(text=('최신 값' if usage['fresh'] else '갱신 필요')+' · '+str(usage['age_seconds']//60)+'분 전 저장됨' if usage else '사용량 파일을 확인하세요. 남은 토큰을 추정하지 않습니다.')
        approval='전체 기획 승인됨' if state.get('approved') else '전체 기획 미승인'
        current='기획 r'+str(state.get('parent_revision','—'))+'  ·  '+approval
        questions='\n'.join('• '+x for x in state.get('questions',[])) or '현재 기획의 미응답 질문 없음'
        latest=state.get('results',[])[-1] if state.get('results') else None
        replace_text(self.overview_text,'## '+current+'\n\n'+questions+'\n\n## 최신 판단\n'+(latest['summary'] if latest else '아직 기획 결과가 없습니다.')+'\n\n기획을 승인한 뒤 실행하면 다음 기능으로 이어집니다. 기능 검증 후에는 사용자 코드 리뷰를 기다립니다.')
        replace_text(self.plan_text,'# 전체 기획안\n'+current+'\n\n'+(state.get('document') or '아직 작성된 기획안이 없습니다.')+'\n\n승인 대상: '+str(state.get('candidate_hash') or '없음'))
        all_features=state.get('features',[])
        query=self.feature_filter.get().strip().casefold()
        features=[f for f in all_features if query in (f['task_id']+' '+f['title']+' '+f['requirements']).casefold()]
        self.feature_count.configure(text=f'{len(features)} / {len(all_features)}개')
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
        self.footer.configure(text='● 연결됨  ·  '+datetime.now().strftime('%H:%M:%S')+' 확인  ·  F5 새로고침 / Ctrl+F 문서 검색')

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
        theme_window_frame(dialog)
        dialog.geometry('800x650' if action=='approve' else '640x400');dialog.configure(bg='#19211e');dialog.grab_set()
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
            editor=tk.Text(dialog,height=6,wrap='word',relief='solid',bd=1,font=('맑은 고딕',11),bg='#26392f',fg=INK,insertbackground=INK);editor.pack(fill='both',expand=True,padx=24)
        row=tk.Frame(dialog,bg='#19211e');row.pack(side='bottom',fill='x',padx=24,pady=20)
        def confirm():
            if action=='approve' and not checked.get():messagebox.showinfo('검토 확인','기획 검토 확인란을 선택하세요.',parent=dialog);return
            content=editor.get('1.0','end-1c').strip() if editor else ''
            if action in TEXT_ACTIONS and not 1<=len(content)<=12000:
                messagebox.showerror('입력 확인','내용을 1~12,000자로 입력하세요.',parent=dialog);return
            payload['text']=content
            dialog.destroy();self.submit(payload)
        RoundButton(row,text='취소',command=dialog.destroy,relief='flat',bg='#283a31',fg=INK,padx=20,pady=9).pack(side='right')
        RoundButton(row,text=NAMES[action],command=confirm,bg=PURPLE,fg='#0a2b1b',relief='flat',padx=20,pady=9).pack(side='right',padx=10)

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
        raise ValueError('한자리가 이미 실행 중입니다. 작업 표시줄에서 기존 창을 열어 주세요.')
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
        from studio_view import Studio
        app=Studio(root,Backend(args.config,args.mode))
        root.deiconify();root.mainloop()
    except Exception as exc:
        messagebox.showerror('한자리 스튜디오',str(exc),parent=root)
    finally:
        if handle:handle[0].CloseHandle(handle[1])


if __name__=='__main__':
    # Share the same widgets/helpers when the view imports desktop from this entry point.
    import sys
    sys.modules.setdefault('desktop',sys.modules[__name__])
    main()
