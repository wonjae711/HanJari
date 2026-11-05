"""Desktop registration: reuse the CLI's validated, locked profile creation."""
import argparse
import queue
import re
import threading
import tkinter as tk
import uuid
from tkinter import filedialog, messagebox, ttk
from urllib.parse import urlsplit
import workspace


def page_id(value):
    value=value.strip()
    if not value:
        return None
    if value.startswith('https://'):
        parsed=urlsplit(value)
        host=parsed.hostname or ''
        if not any(host==d or host.endswith('.'+d) for d in ('notion.so','notion.site','notion.com')):
            raise ValueError('Notion 페이지 링크 또는 페이지 ID를 입력하세요.')
        value=parsed.path.rstrip('/').split('/')[-1]
        match=re.search(r'([0-9a-fA-F]{32}|[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12})$',value)
        if not match:
            raise ValueError('Notion 페이지 링크에서 페이지 ID를 찾지 못했습니다.')
        value=match[1]
    try:
        return str(uuid.UUID(value))
    except ValueError:
        raise ValueError('Notion 페이지 링크 또는 올바른 페이지 ID를 입력하세요.') from None


def register_project(values, registry=None):
    values={k:v.strip() for k,v in values.items()}
    for key,label in [('project','프로젝트 ID'),('title','프로젝트 이름'),('repo','저장소 폴더'),
                      ('idea','아이디어'),('record_page','Notion 결과 페이지')]:
        if not values.get(key):
            raise ValueError(label+'을(를) 입력하세요.')
    if not re.fullmatch(r'[a-z][a-z0-9-]{1,39}',values['project']):
        raise ValueError('프로젝트 ID는 영문 소문자로 시작하는 2~40자의 소문자·숫자·하이픈입니다.')
    prefix=values.get('prefix') or 'TASK'
    if not re.fullmatch(r'[A-Z][A-Z0-9]{1,11}',prefix):
        raise ValueError('업무 접두사는 대문자로 시작하는 2~12자의 대문자·숫자입니다.')
    args=argparse.Namespace(project=values['project'],title=values['title'],repo=values['repo'],
        idea=values['idea'],idea_file=None,prefix=prefix,baseline=values.get('baseline') or None,
        record_page=page_id(values['record_page']),source_page=page_id(values.get('source_page','')))
    return workspace.register(args, registry=registry if registry is not None else workspace.REGISTRY)


class ProjectDialog:
    def __init__(self, app):
        import desktop as ui
        self.app=app;self.pending=False;self.results=queue.Queue()
        self.window=tk.Toplevel(app.root);self.window.title('프로젝트 추가 — 한자리')
        self.window.configure(bg=ui.BG);self.window.geometry('680x760');self.window.minsize(620,650)
        self.window.transient(app.root);self.window.grab_set()
        self.window.protocol('WM_DELETE_WINDOW',self.close)
        ui.theme_window_frame(self.window)
        body=tk.Frame(self.window,bg=ui.BG,padx=24,pady=16);body.pack(fill='both',expand=True)
        app.label(body,'새 아이디어를 시작할 프로젝트',size=16,bold=True).pack(anchor='w')
        app.label(body,'GitHub origin이 설정된 로컬 저장소를 연결하세요. 등록 후 실행은 별도로 시작합니다.',
                  size=9,color=ui.MUTED,wraplength=600).pack(anchor='w',pady=(6,14))
        self.fields={}
        rows=[('title','프로젝트 이름',''),('project','고유 ID · 예: bookshelf',''),
              ('repo','로컬 저장소 폴더','folder'),('record_page','Notion 결과 페이지 · 링크 또는 ID',''),
              ('source_page','Notion 원본 페이지 · 선택, 결과 페이지와 달라야 함',''),
              ('prefix','업무 접두사 · 예: BK',''),('baseline','기존 테스트 기준 JSON · 기존 테스트가 있을 때 필요','file')]
        for key,label,picker in rows:
            app.label(body,label,size=9,color=ui.MUTED).pack(anchor='w',pady=(5,3))
            row=tk.Frame(body,bg=ui.BG);row.pack(fill='x')
            var=tk.StringVar(value='TASK' if key=='prefix' else '');self.fields[key]=var
            entry=tk.Entry(row,textvariable=var,bg='#19211e',fg=ui.INK,insertbackground=ui.PURPLE,
                           relief='flat',highlightthickness=1,highlightbackground='#304238',highlightcolor=ui.PURPLE)
            entry.pack(side='left',fill='x',expand=True,ipady=5)
            if picker:
                ui.RoundButton(row,text='찾아보기',padx=10,pady=4,
                    command=lambda k=key,p=picker:self.browse(k,p)).pack(side='right',padx=(8,0))
        app.label(body,'아이디어 · 해결할 문제와 원하는 기능',size=9,color=ui.MUTED).pack(anchor='w',pady=(10,4))
        self.idea=tk.Text(body,height=4,bg='#19211e',fg=ui.INK,insertbackground=ui.PURPLE,
                          wrap='word',relief='flat',padx=10,pady=8)
        self.idea.pack(fill='both',expand=True)
        app.label(body,'현재 자동 개발 지원: Java / Spring Boot / Maven / MySQL\nNotion은 기존 통합에 페이지 접근 권한이 필요하며, 실제 연결은 실행·동기화 시 확인합니다.',
                  size=9,color=ui.MUTED,wraplength=600,justify='left').pack(anchor='w',pady=10)
        self.status=app.label(body,'',size=9,color='#ebc07a',wraplength=600,justify='left');self.status.pack(fill='x')
        self.save=ui.RoundButton(body,text='프로젝트 등록',bg=ui.PURPLE,fg='#0a2b1b',command=self.submit)
        self.save.pack(anchor='e',pady=(8,0))

    def browse(self,key,kind):
        value=filedialog.askdirectory(parent=self.window) if kind=='folder' else filedialog.askopenfilename(parent=self.window,filetypes=[('JSON','*.json')])
        if value:self.fields[key].set(value)

    def close(self):
        if self.pending:
            self.status.configure(text='등록 결과를 확인하고 있습니다. 잠시 기다려 주세요.');return
        self.window.destroy()

    def submit(self):
        if self.pending:return
        if self.app.pending_submit or (self.app.state or {}).get('busy'):
            self.status.configure(text='현재 요청 처리가 끝난 뒤 프로젝트를 추가하세요.');return
        values={k:v.get() for k,v in self.fields.items()};values['idea']=self.idea.get('1.0','end').strip()
        self.pending=True;self.app.pending_submit=True;self.save.configure(state='disabled')
        self.status.configure(text='저장소와 입력값을 확인하고 등록 중입니다…')
        def work():
            try:
                with self.app.backend.mutex:
                    if any(j['status']=='running' for j in self.app.backend.jobs):
                        raise ValueError('진행 중인 요청이 끝난 후 등록하세요.')
                    result=register_project(values)
                self.results.put((True,result))
            except Exception as exc:
                self.results.put((False,str(exc)))
        threading.Thread(target=work,daemon=True).start()
        self.window.after(100,self.poll)

    def poll(self):
        try:ok,result=self.results.get_nowait()
        except queue.Empty:
            self.window.after(100,self.poll);return
        self.pending=False;self.app.pending_submit=False;self.save.configure(state='normal')
        if not ok:
            self.status.configure(text='등록하지 못했습니다: '+result);return
        # Updating selection invalidates any in-flight old-project snapshot in Desktop.drain.
        self.app.project_keys.append(result['id'])
        labels=list(self.app.selector.cget('values'))+[result['title']]
        self.app.selector.configure(values=labels);self.app.selector.current(len(labels)-1)
        self.app.change_project();self.window.destroy()
        messagebox.showinfo('프로젝트 등록 완료','프로젝트를 추가했습니다. 내용을 확인한 뒤 「다음 작업 실행」으로 기획을 시작하세요.\nAI는 아직 실행하지 않았습니다.',parent=self.app.root)
