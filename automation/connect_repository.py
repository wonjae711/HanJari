"""Register the already-authorized repository locally; never commit/push/reset."""
from pathlib import Path
import subprocess
import sys
import controller as c

def git(root, *args, check=True):
    return subprocess.run(["git", "-C", str(root), *args], text=True,
                          encoding="utf-8", errors="replace", capture_output=True, check=check)

def main():
    config = c.read(c.ROOT / "config.json")
    root = Path(config["project_dir"]).resolve()
    target = c.read(c.ROOT / "integrations.json")["github"]["url"] + ".git"
    if not root.is_dir():
        raise ValueError("프로젝트 폴더가 없습니다.")
    if not (root / ".git").exists():
        git(root, "init", "-b", config["base_branch"])
    actual = Path(git(root, "rev-parse", "--show-toplevel").stdout.strip()).resolve()
    if actual != root:
        raise ValueError("프로젝트 루트와 Git 루트가 다릅니다.")
    remotes = git(root, "remote").stdout.splitlines()
    if "origin" in remotes:
        existing = git(root, "remote", "get-url", "origin").stdout.strip()
        if existing.removesuffix(".git") != target.removesuffix(".git"):
            raise ValueError("다른 origin이 이미 있습니다. 자동 변경하지 않습니다.")
    else:
        git(root, "remote", "add", "origin", target)
    verified = git(root, "remote", "get-url", "origin").stdout.strip()
    c.save(c.ROOT / ".runtime-repository" / "verified.json",
           {"verified_at": c.now(), "project_dir": str(root), "origin": verified})
    print("PASS - origin: " + verified)
    print("커밋, 푸시, 병합은 수행하지 않았습니다.")

if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as exc:
        print(exc.stderr or str(exc), file=sys.stderr)
        sys.exit(1)
    except Exception as exc:
        print(str(exc), file=sys.stderr)
        sys.exit(1)
