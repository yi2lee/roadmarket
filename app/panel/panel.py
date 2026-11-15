#!/usr/bin/env python3
"""로드마켓 데모 조작판 서버. 127.0.0.1:8090에서만 열려요. 정해 둔 명령만 demo.sh로 실행해요."""
import json, os, re, subprocess
from http.server import BaseHTTPRequestHandler, HTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
DEMO = os.path.abspath(os.path.join(HERE, "..", "demo.sh"))
CARS = ("A", "B")
POS = {"A": ("37.4127", "127.0947"), "B": ("37.4250", "127.1000")}
MYCAR = {"A": ("흰색 SUV", "1204"), "B": ("검은색 세단", "7788")}
NOARG = {"door", "open", "navi", "near", "request", "request-other", "request-silent",
         "withdraw", "peer-cancel", "dest", "dest-clear", "reset", "spoken"}
NUM = {"gone", "seller-cancel"}
LINE = re.compile(r"^.*?ROADMARKET_\w+\s*\(\s*\d+\):\s*")


def build(cmd, car, arg):
    """(명령 목록들) 또는 오류 글자. 허용 목록 밖이면 None."""
    if car not in CARS:
        return None
    base = [DEMO, car]
    if cmd in NOARG:
        return [base + [cmd]]
    if cmd == "gear" and arg in ("P", "D"):
        return [base + ["gear", arg]]
    if cmd == "night" and arg in ("on", "off"):
        return [base + ["night", arg]]
    if cmd in NUM and arg in ("1", "2", "3"):
        return [base + [cmd, arg]]
    if cmd == "pos":
        return [base + ["pos", *POS[car]]]
    if cmd == "mycar":
        return [base + ["mycar", *MYCAR[car]]]
    if cmd == "say" and isinstance(arg, str):
        t = arg.strip()
        if not t or len(t) > 200 or "'" in t or any(ord(c) < 32 for c in t):
            return None
        return [base + ["say", t]]
    return None


class H(BaseHTTPRequestHandler):
    def _send(self, code, body, ctype="application/json; charset=utf-8"):
        b = body if isinstance(body, bytes) else body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(b)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self):
        if self.path in ("/", "/index.html"):
            with open(os.path.join(HERE, "panel.html"), "rb") as f:
                self._send(200, f.read(), "text/html; charset=utf-8")
        else:
            self._send(404, json.dumps({"ok": False, "out": "없는 주소예요"}, ensure_ascii=False))

    def do_POST(self):
        def fail(code, msg):
            self._send(code, json.dumps({"ok": False, "out": msg}, ensure_ascii=False))
        if self.path != "/run":
            return fail(404, "없는 주소예요")
        host = self.headers.get("Host", "")
        if host not in ("127.0.0.1:8090", "localhost:8090"):
            return fail(403, "허용되지 않은 주소예요")
        try:
            n = int(self.headers.get("Content-Length", "0"))
            if n > 4096:
                return fail(413, "너무 커요")
            d = json.loads(self.rfile.read(n) or b"{}")
            cmd, car, arg = d.get("cmd"), d.get("car", "A"), d.get("arg")
            if not isinstance(cmd, str) or not isinstance(car, str):
                raise ValueError
        except Exception:
            return fail(400, "요청이 이상해요")
        plan = build(cmd, car, arg if arg is None or isinstance(arg, str) else str(arg))
        if plan is None:
            return fail(400, "허용하지 않는 명령이나 값이에요")
        out = ""
        ok = True
        for argv in plan:
            try:
                p = subprocess.run(argv, capture_output=True, text=True, timeout=40)
            except subprocess.TimeoutExpired:
                return fail(200, "시간이 너무 걸려서 멈췄어요")
            out += (p.stdout or "") + (p.stderr or "")
            if p.returncode != 0:
                ok = False
                break
        out = out.strip()
        if cmd == "spoken":
            ok = ok or not out  # 말한 것이 없으면 grep이 1을 돌려줘요
            lines = [LINE.sub("", l).strip() for l in out.splitlines() if "ROADMARKET_" in l]
            out = "\n".join(l for l in lines[-8:] if l)
        elif not ok and not out:
            out = "실패했어요. 에뮬레이터가 켜져 있는지 봐 주세요"
        self._send(200, json.dumps({"ok": ok, "out": out[-2000:]}, ensure_ascii=False))

    def log_message(self, *a):
        pass


if __name__ == "__main__":
    HTTPServer(("127.0.0.1", 8090), H).serve_forever()
