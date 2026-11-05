"""Per-project Windows-user encrypted test database settings."""
import ctypes
from ctypes import wintypes
import json
import os
from pathlib import Path
import re
import tempfile

ROOT = Path(__file__).resolve().parent / '.runtime-db'


def crypt(data, decrypt=False):
    if os.name != 'nt':
        raise ValueError('DB 연결 저장은 Windows에서 지원합니다.')
    class Blob(ctypes.Structure):
        _fields_ = [('size', wintypes.DWORD), ('data', ctypes.POINTER(ctypes.c_ubyte))]
    buffer=ctypes.create_string_buffer(data)
    source=Blob(len(data),ctypes.cast(buffer,ctypes.POINTER(ctypes.c_ubyte)));target=Blob()
    dll=ctypes.WinDLL('crypt32',use_last_error=True)
    fn=dll.CryptUnprotectData if decrypt else dll.CryptProtectData
    fn.argtypes=[ctypes.POINTER(Blob),ctypes.c_void_p,ctypes.c_void_p,ctypes.c_void_p,ctypes.c_void_p,wintypes.DWORD,ctypes.POINTER(Blob)]
    fn.restype=wintypes.BOOL
    if not fn(ctypes.byref(source),None,None,None,None,1,ctypes.byref(target)):
        raise ValueError('DB 연결 정보를 암호화하거나 읽지 못했습니다. 저장한 Windows 계정인지 확인하세요.')
    kernel=ctypes.WinDLL('kernel32');kernel.LocalFree.argtypes=[ctypes.c_void_p];kernel.LocalFree.restype=ctypes.c_void_p
    try:return ctypes.string_at(target.data,target.size)
    finally:
        ctypes.memset(target.data,0,target.size)
        kernel.LocalFree(target.data)


def path_for(key):
    if not isinstance(key,str) or not re.fullmatch(r'[a-z][a-z0-9-]{1,39}',key):
        raise ValueError('프로젝트 ID가 올바르지 않습니다.')
    return ROOT / (key+'.dpapi')


def load(key):
    path=path_for(key)
    if not path.exists():return None
    try:return json.loads(crypt(path.read_bytes(),True))
    except (OSError,ValueError):raise ValueError('저장된 DB 연결 정보를 읽지 못했습니다. 다시 저장하세요.') from None


def public(key):
    value=load(key)
    return dict(saved=bool(value),url=value['url'] if value else '',username=value['username'] if value else '')


def save(key,payload):
    url=payload.get('url');username=payload.get('username');password=payload.get('password')
    if not all(isinstance(v,str) for v in (url,username,password)):
        raise ValueError('DB 주소, 계정, 비밀번호를 확인하세요.')
    url=url.strip();username=username.strip()
    if not url.startswith('jdbc:mysql://') or len(url)>2000 or any(c in url for c in '\r\n'):
        raise ValueError('테스트 DB의 jdbc:mysql:// 주소를 입력하세요.')
    if re.search(r'(?i)(password|user)\s*=|@',url):
        raise ValueError('DB 주소에 인증 정보를 넣지 말고 계정과 비밀번호 칸을 사용하세요.')
    if not username or len(username)>128 or len(password)>4096:
        raise ValueError('DB 계정 또는 비밀번호 길이를 확인하세요.')
    if not password:
        old=load(key)
        if not old:raise ValueError('처음 저장할 때는 비밀번호가 필요합니다.')
        password=old['password']
    encrypted=crypt(json.dumps(dict(url=url,username=username,password=password),ensure_ascii=False).encode())
    path=path_for(key);path.parent.mkdir(parents=True,exist_ok=True)
    temporary=None
    try:
        with tempfile.NamedTemporaryFile(dir=path.parent,delete=False) as f:
            temporary=Path(f.name);f.write(encrypted)
        os.replace(temporary,path)
    finally:
        if temporary and temporary.exists():temporary.unlink()
    return public(key)


def environment(profile,base):
    env=dict(base)
    # Never inherit another project's DB credentials into this workspace process.
    for name in list(env):
        if re.fullmatch(r'[A-Z][A-Z0-9_]*_TEST_DB_(URL|USERNAME|PASSWORD)',name):env.pop(name)
    names=profile['test_db_env'];value=load(profile['id'])
    if value:
        env.update(zip(names,(value['url'],value['username'],value['password'])))
    else:
        env.update({name:base[name] for name in names if name in base})
    return env
