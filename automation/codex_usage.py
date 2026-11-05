"""Read quota metadata using Codex's documented app-server protocol; no turns."""
import json
import math
import queue
import shutil
import subprocess
import threading
import time


def normalize(result):
    buckets=result.get('rateLimitsByLimitId') or {}
    if not buckets and result.get('rateLimits'):
        value=result['rateLimits'];buckets={value.get('limitId') or 'codex':value}
    output=[]
    for key,value in buckets.items():
        windows=[]
        for field in ('primary','secondary'):
            window=value.get(field)
            if not isinstance(window,dict):continue
            used=window.get('usedPercent');minutes=window.get('windowDurationMins');reset=window.get('resetsAt')
            if type(used) not in (int,float) or not math.isfinite(used) or not 0<=used<=100:continue
            windows.append(dict(used=used,minutes=minutes if type(minutes) is int and minutes>0 else None,
                                resets_at=reset if type(reset) is int else None))
        if windows:output.append(dict(id=key,name=value.get('limitName') or key,windows=windows))
    return {'buckets':output,'checked_at':time.time()}


def fetch(command='codex',timeout=20):
    binary=shutil.which(command)
    if not binary:raise ValueError('Codex 실행 파일을 찾지 못했습니다.')
    proc=subprocess.Popen([binary,'app-server'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,
                          stderr=subprocess.DEVNULL,text=True,encoding='utf-8',
                          creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
    messages=queue.Queue()
    def read():
        try:
            for line in proc.stdout:
                try:messages.put(json.loads(line))
                except ValueError:pass
        finally:messages.put(None)
    reader=threading.Thread(target=read,daemon=True);reader.start()
    end=time.monotonic()+timeout
    def send(value):proc.stdin.write(json.dumps(value)+'\n');proc.stdin.flush()
    def response(identifier):
        while True:
            try:item=messages.get(timeout=max(.01,end-time.monotonic()))
            except queue.Empty:raise ValueError('사용량 조회 시간이 초과됐습니다. 잠시 후 다시 시도하세요.') from None
            if item is None:raise ValueError('Codex 사용량 조회 연결이 종료됐습니다.')
            if item.get('id')==identifier:
                if 'error' in item:raise ValueError('사용량을 조회하지 못했습니다. Codex의 ChatGPT 로그인을 확인하세요.')
                return item.get('result',{})
            if time.monotonic()>=end:raise ValueError('Codex 사용량 조회 시간이 초과됐습니다.')
    try:
        send({'id':1,'method':'initialize','params':{'clientInfo':{'name':'hanjari_usage','version':'1.0'}}})
        response(1);send({'method':'initialized','params':{}})
        send({'id':2,'method':'account/rateLimits/read'})
        return normalize(response(2))
    finally:
        proc.stdin.close()
        try:proc.wait(timeout=2)
        except subprocess.TimeoutExpired:proc.kill();proc.wait(timeout=3)
        reader.join(timeout=1);proc.stdout.close()


class Reader:
    def __init__(self):
        self.lock=threading.Lock();self.last=None;self.at=0;self.error=None

    def get(self,command):
        with self.lock:
            if time.monotonic()-self.at>=60:
                try:self.last=fetch(command);self.error=None
                except Exception:self.error='사용량 조회 실패 · Codex 로그인과 연결을 확인하세요.'
                self.at=time.monotonic()
            return dict(self.last or {'buckets':[],'checked_at':None},error=self.error)
