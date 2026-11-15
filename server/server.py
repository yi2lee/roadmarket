#!/usr/bin/env python3
"""로드마켓 서버 (요건표 7-0). 파이썬 3 표준 라이브러리만 써요.

차와 차는 이 서버를 거쳐 주고받아요. 앱은 1초마다 POST /sync 로 자기 상태와 명령을 올리고,
자기에게 온 일(events)과 주변 물건(near)을 받아 가요.
20초(답 없음)와 60초(고르지 않음) 타이머는 이 서버에서만 세요. 앱은 세지 않아요.
가짜 차(D-01~D-10)도 이 서버 안의 대본이에요. demo.sh가 POST /demo/<명령> 으로 불러내요.

실행: PORT(없으면 8099), 주소 0.0.0.0.  KAKAO_REST_KEY 는 환경 변수, 없으면 같은 폴더의 .env 에서 읽어요.
키 값은 어디에도 찍지 않아요.
"""
import collections
import concurrent.futures
import hashlib
import hmac
import html.parser
import json
import math
import os
import queue
import re
import secrets
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))

# ───── 요건표의 값 (8절) ─────
ANSWER_SEC = 60          # S-24, B-46 파는 사람이 답할 시간 (03_성사: 1분)
HOME_ANSWER_SEC = 180    # 집 물건(폰)의 답 기다리는 시간 3분 (약속 2절, 임의)
MAX_DESC = 200           # 물건 설명 길이 (약속 1절)
MAX_NICK = 12            # 닉네임 길이 (약속 3절)
URGENT_MIN = 10          # 급구 범위 10분 (02_발견 8절)
ROUTE_EXTRA_MAX = 15     # 경로 추천: 이보다 더 돌아가면 보내지 않아요 (약속 5절)
PICK_SEC = 60            # B-51, S-27 사는 사람이 고를 시간
NEAR_MIN = 10            # "주변" = 10분 거리 안
KMH = 30                 # D-15 거리를 분으로 바꾸는 속도
TIE_KM = 0.5             # M-20 "비슷하다"의 기준 500m
ROUTE_CANDS = 5          # M-20 길찾기로 다시 재는 후보 수
TIE_SEC = 60             # M-20 "비슷하다"의 기준 (길찾기) 1분
ROUTE_TTL = 60           # D-15 같은 두 위치를 다시 묻지 않는 시간(초)
PHOTO_MAX = 2 * 1024 * 1024   # 사진 본문 상한 (JPEG)
PHOTO_KEEP = 300         # 서버가 맡아 두는 사진 수. 넘으면 오래된 것부터 지워요

# ───── 요건표에 없는 값 (임의) ─────
OFFLINE_SEC = 10         # 이만큼 /sync 가 없으면 그 차는 주변에 안 보여요
KAKAO_TIMEOUT = 3        # 카카오 호출 한도(초)
KAKAO_SIZE = 15          # 종류별로 받는 수 (카카오 최대 15)
KAKAO_CATS = ("CS2", "OL7", "PK6")   # 편의점, 주유소, 주차장
KAKAO_TOTAL = 6          # 카카오 세 종류를 합한 전체 한도(초)
MAX_BODY = 64 * 1024     # 요청 본문 상한(바이트)
SOCK_TIMEOUT = 10        # 소켓 timeout(초)
MAX_NAME = 60            # 물건·찾는 물건 이름 길이
MAX_ITEMS = 50           # 물건·찾는 물건 각 개수
MAX_PRICE = 1_000_000_000    # 가격 상한(원)
MAX_DEALS = 1_000_000    # 거래 횟수 상한
MAX_CARINFO = 100        # 차 설명 길이
MAX_ID = 64              # 차 식별자·세션·물건 id 길이
MAX_CMDS = 100           # 한 요청의 명령 수
IDLE_SEC = int(os.environ.get("ROADMARKET_IDLE_SEC", "600"))   # 이만큼 조용한 차, 끝난 지 이만큼 지난 거래는 지워요 (시험용으로 줄일 수 있어요)
PHONE_KEEP_SEC = int(os.environ.get("ROADMARKET_PHONE_KEEP_SEC", "86400"))   # 받지 않은 폰 일이 있는 차는 이만큼 기다렸다 지워요(하루)
CMD_RETRY = 3            # 같은 명령이 이만큼 실패하면 건너뛰어요
ROUTE_URL = "https://apis-navi.kakaomobility.com/v1/directions"
ROUTE_TIMEOUT = 3        # 길찾기 한 번의 시간 제한(초)
ROUTE_FAR_MIN = 2 * NEAR_MIN   # 직선으로 이보다 멀면 주변 물건 계산에 길찾기를 묻지 않아요
ROUTE_WORKERS = 2        # 주변 물건용 길찾기를 뒤에서 묻는 일꾼 수
ROUTE_QUEUE = 200        # 뒤에서 물을 길찾기 대기 상한
ROUTE_CACHE_MAX = 5000   # 길찾기 기억 상한
MAX_PAIRS = 10000        # 폰 연결 번호·폰 토큰 상한
PAIR_FAILS = 10          # 한 곳에서 이만큼 틀리면
PAIR_FAIL_SEC = 60       # 이 시간 동안 연결을 막아요(초)
PAIR_FAILS_ALL = 60      # 서버 전체에서 PAIR_FAIL_SEC 동안 이만큼 틀리면 모든 연결 시도를 막아요
MAX_PHONE_PENDING = 30   # 차가 아직 받지 않은 폰 일의 상한
# 폰 로그인과 계정 (임의 값)
MAX_ACCOUNTS = 10000     # 계정 수 상한. 넘으면 새 계정을 만들지 않아요
STATE_SEC = 600          # 카카오 로그인 state 유효 시간(10분, 의뢰서)
MAX_STATES = 1000        # 아직 돌아오지 않은 카카오 로그인 state 상한
KAKAO_LOGIN_TIMEOUT = 5  # 카카오 토큰·사용자 호출 한 번의 시간 제한(초)
TEST_NAME_MAX = 20       # 시험용 로그인 이름 길이(의뢰서)
# 링크로 채우기 (의뢰서)
LINK_HOSTS = {"daangn.com", "www.daangn.com", "m.bunjang.co.kr", "bunjang.co.kr", "www.bunjang.co.kr"}
LINK_IMG_HOSTS = {"dnvefa72aowie.cloudfront.net", "img.kr.gcp-karroter.net", "media.bunjang.co.kr"}  # 두 사이트의 그림 호스트
LINK_TIMEOUT = 5         # 쪽 하나, 그림 하나를 받는 시간 제한(초)
LINK_MAX = 1024 * 1024   # 받는 쪽의 상한. 넘으면 앞의 1MB만 읽어요
LINK_REDIRECTS = 3
LINK_IMG_TYPES = {"image/jpeg", "image/png", "image/webp"}
LINK_UA = "Mozilla/5.0 (compatible; RoadMarket/1.0)"

# D-14 손으로 적어 둔 후보 장소 (이름과 좌표는 지어낸 것)
PLACES = [
    ("데모 편의점 앞", 37.4400, 127.0900),
    ("데모 주유소", 37.3948, 127.1112),
    ("데모 공영주차장", 37.4200, 127.1000),
    ("데모 휴게 쉼터", 37.4650, 127.0600),
]

# D-02 가짜 파는 차: (물건, 가격, 분, 상대 거래, 차, 답하는 초 또는 None, 수락?)
FAKE_SELLERS = [
    ("주니어 카시트", 40000, 5, 3, "흰색 세단 · 번호 끝 1204", 4, False),
    ("주니어 카시트", 30000, 6, 12, "회색 SUV · 번호 끝 5821", 6, True),
    ("주니어 카시트", 50000, 8, 1, "검은색 세단 · 번호 끝 7740", 8, True),
    ("차량용 거치대", 8000, 4, 5, "흰색 SUV · 번호 끝 9016", 4, True),
    ("골프 거리측정기", 60000, 7, 2, "은색 세단 · 번호 끝 4453", None, False),
    # 6번부터: 목록이 실제처럼 차 보이게 더한 물건 (1~5번은 시연 대본에 묶여 있어 바꾸지 않아요)
    ("차량용 무선 청소기", 35000, 3, 8, "남색 해치백 · 번호 끝 2287", 5, True),
    ("캠핑 의자 2개", 25000, 9, 4, "카키색 SUV · 번호 끝 6630", 7, False),
    ("2채널 블랙박스", 70000, 6, 15, "흰색 RV · 번호 끝 3815", 5, True),
    ("접이식 유모차", 90000, 10, 2, "베이지색 SUV · 번호 끝 0472", 9, True),
    ("보온 텀블러 세트", 12000, 2, 21, "빨간색 경차 · 번호 끝 8159", 3, True),
    ("휴대용 타이어 공기주입기", 28000, 5, 6, "검은색 SUV · 번호 끝 1938", None, False),
    ("차량용 햇빛가리개", 6000, 7, 0, "회색 세단 · 번호 끝 5504", 6, False),
]
# 가짜 파는 차 번호 -> server/seed_photos/ 의 그림 파일. 없는 번호는 사진 없이 보여요 (절반쯤만 달아 둬요)
FAKE_PHOTOS = {2: "fake_02_junior_carseat.jpg", 4: "fake_04_phone_mount.jpg", 6: "fake_06_car_vacuum.jpg",
               8: "fake_08_dashcam.jpg", 9: "fake_09_stroller.jpg", 11: "fake_11_tire_inflator.jpg"}
# /demo/seed 가 부른 차의 「파는 물건」에 넣는 물건: (이름, 가격, 그림 파일 또는 "")
DEMO_SEED_SELLS = [
    ("트렁크 정리함", 15000, "seed_trunk_organizer.jpg"),
    ("차량용 목쿠션 2개", 10000, "seed_neck_pillow.jpg"),
    ("휴대용 아이스박스", 22000, "seed_cooler_box.jpg"),
    ("차량용 방향제 세트", 7000, ""),
    ("충전 케이블 3종", 5000, ""),
    ("접이식 우산 2개", 8000, ""),
]
# D-04~D-06 가짜 사는 차
FAKE_BUYER_MIN = 3
FAKE_BUYER_DEALS = 9
FAKE_BUYER_CAR = "파란색 SUV · 번호 끝 3307"
FAKE_BUYER_PICK_SEC = 5


def env_value(name):
    """환경 변수, 없으면 같은 폴더의 .env 에서 읽어요. 값은 어디에도 찍지 않아요."""
    k = os.environ.get(name)
    if k is not None:
        return k.strip()
    try:
        with open(os.path.join(HERE, ".env"), encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line.startswith(name + "="):
                    return line.split("=", 1)[1].strip().strip('"').strip("'")
    except OSError:
        pass
    return ""


KAKAO_KEY = env_value("KAKAO_REST_KEY")
KAKAO_SECRET = env_value("KAKAO_CLIENT_SECRET")
KAKAO_JS = env_value("KAKAO_JS_KEY")              # 10절: 폰 지도용 공개 JavaScript 키 (값은 찍지 않아요)    # 카카오 로그인 Client Secret (토큰 받을 때)
BOOT = uuid.uuid4().hex[:8]
# 인터넷에 열린 서버에서는 데모 명령(가짜 차)을 아무나 못 부르게 해요. 값은 환경 변수로만 받아요.
DEMO_TOKEN = os.environ.get("DEMO_TOKEN", "")
LOCK = threading.RLock()


ON_RAILWAY = bool(os.environ.get("RAILWAY_ENVIRONMENT"))


def clean(s):
    """로그에 찍을 글에서 줄바꿈을 지워요."""
    return str(s).replace("\r", " ").replace("\n", " ")


def log(*a):
    sys.stdout.write(" ".join([time.strftime("%H:%M:%S")] + [clean(v) for v in a]) + "\n")    # 여러 스레드가 한 줄씩
    sys.stdout.flush()


def short(cid):
    cid = str(cid)
    return cid if cid.startswith("fake") else cid[:6]


# ───── 거리 ─────
def km(a, b):
    """직선거리(km). a, b = (위도, 경도)"""
    la1, lo1, la2, lo2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    h = math.sin((la2 - la1) / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin((lo2 - lo1) / 2) ** 2
    return 6371.0 * 2 * math.asin(min(1.0, math.sqrt(h)))


def minutes(a, b):
    """D-15: 직선거리 ÷ 시속 30km, 분으로 올림."""
    return int(math.ceil(km(a, b) / KMH * 60 - 1e-9))


def offset_north(p, mins):
    """가짜 차의 자리: 내 차에서 북쪽으로 (분 × 시속 30km) 만큼. (임의)"""
    return (p[0] + mins * KMH / 60.0 / 111.0, p[1])


# ───── 길찾기 (D-15, M-20) ─────
# 네트워크 호출(route_fetch)은 LOCK 밖에서만 해요. LOCK 안에서는 기억(route_seen)만 봐요.
route_cache = {}         # 반올림한 두 위치 -> (물은 때, (초, m) 또는 None)
route_lock = threading.Lock()
route_pending = set()
route_q = queue.Queue()


def rkey(a, b):
    return (round(a[0], 3), round(a[1], 3), round(b[0], 3), round(b[1], 3))


def route_seen(a, b):
    """(1분 안에 물었나, 값 또는 None, 예전에라도 물은 적 있나)"""
    with route_lock:
        v = route_cache.get(rkey(a, b))
    if v is None:
        return False, None, False
    return time.time() - v[0] < ROUTE_TTL, v[1], True


def route_fetch(a, b):
    """카카오 길찾기. (걸리는 초, 거리 m) 또는 None. 같은 두 위치는 1분 동안 다시 묻지 않아요."""
    if not KAKAO_KEY:
        return None
    fresh, v, _ = route_seen(a, b)
    if fresh:
        return v
    val = None
    try:
        q = urllib.parse.urlencode(dict(origin="%.6f,%.6f" % (a[1], a[0]), destination="%.6f,%.6f" % (b[1], b[0]),
                                        summary="true"))
        req = urllib.request.Request(ROUTE_URL + "?" + q, headers={"Authorization": "KakaoAK " + KAKAO_KEY})
        with urllib.request.urlopen(req, timeout=ROUTE_TIMEOUT) as res:
            j = json.loads(res.read(256 * 1024).decode("utf-8"))
        r = j["routes"][0]
        code = r.get("result_code", 0)
        if code == 0:
            sm = r["summary"]
            val = (float(sm["duration"]), float(sm["distance"]))
        elif code == 104:            # 출발지와 도착지가 5m 안: 길이 없음 = 0
            val = (0.0, 0.0)
        else:
            log("ROUTE 답 없음 code", code)
    except Exception as e:           # 키 값은 머리글에만 있어요. 오류 글에는 실리지 않아요
        log("ROUTE 실패:", type(e).__name__, getattr(e, "code", ""))
    with route_lock:
        if len(route_cache) >= ROUTE_CACHE_MAX:
            for k in list(route_cache)[:ROUTE_CACHE_MAX // 5]:
                route_cache.pop(k, None)
        route_cache[rkey(a, b)] = (time.time(), val)
    if val is not None:
        log("ROUTE %.4f,%.4f -> %.4f,%.4f %d초 %dm" % (a[0], a[1], b[0], b[1], val[0], val[1]))
    return val


def route_later(a, b):
    """주변 물건용: 뒤에서 물어 기억을 채워요. /sync 를 막지 않아요."""
    k = rkey(a, b)
    with route_lock:
        if k in route_pending or len(route_pending) >= ROUTE_QUEUE:
            return
        route_pending.add(k)
    route_q.put((k, a, b))


def route_worker():
    while True:
        k, a, b = route_q.get()
        try:
            route_fetch(a, b)
        except Exception as e:
            log("ROUTE 일꾼 오류", repr(e))
        finally:
            with route_lock:
                route_pending.discard(k)


def near_minutes(a, b):
    """D-15: 길찾기 시간(분, 올림). 아직 모르거나 실패하면 직선거리 ÷ 시속 30km."""
    m = minutes(a, b)
    if not KAKAO_KEY or m > ROUTE_FAR_MIN:
        return m
    fresh, v, ever = route_seen(a, b)
    if not fresh:
        route_later(a, b)
    if not ever or v is None:          # 처음이면 일단 직선거리 값, 1분이 지나 다시 묻는 동안에는 지난 값
        return m
    return int(math.ceil(v[0] / 60 - 1e-9))


# ───── 사진과 폰 연결 (C-23, C-24, P-02~P-06) ─────
photos = collections.OrderedDict()     # 사진 id -> (바이트, 형식). 서버를 다시 켜면 사라져요
photo_lock = threading.Lock()
pair_codes = {}          # 차 id -> 여섯 자리 번호
pair_cars = {}           # 번호 -> 차 id
phone_tokens = {}        # sha256(폰 토큰) -> 계정 열쇠. 토큰 원문은 어디에도 두지 않아요
accounts = {}            # 계정 열쇠 -> {kind, sells:[{name,price,photo}] 맨 앞이 맨 위, wants:[...], car: 차 id 또는 None}
car_owner = {}           # 차 id -> 계정 열쇠
kakao_states = {}        # 카카오 로그인 state -> 끝나는 때
pair_fails = {}          # 보낸 곳 -> [틀린 때...]
pair_fails_all = []      # 서버 전체의 틀린 때 (PAIR_FAIL_SEC 안의 것만)
seq_last = 0             # 서버 전체에서 하나로 늘어나는 일 번호. 차를 지웠다 다시 만들어도 뒤로 가지 않아요
PHOTO_ID = re.compile(r"^[0-9a-f]{32}$")


def pair_code(cid):
    c = pair_codes.get(cid)
    if c is not None:
        return c
    if len(pair_codes) >= MAX_PAIRS:                    # 가장 오래된 번호를 버려요 (이미 이은 계정은 그대로)
        old = next(iter(pair_codes))
        pair_cars.pop(pair_codes.pop(old), None)
    while True:
        c = "%06d" % (100000 + secrets.randbelow(900000))
        if c not in pair_cars:
            break
    pair_codes[cid] = c
    pair_cars[c] = cid
    return c


def photo_put(b, ctype="image/jpeg"):
    pid = uuid.uuid4().hex
    with photo_lock:
        photos[pid] = (b, ctype)
        while len(photos) > PHOTO_KEEP:
            photos.popitem(last=False)
    return pid


def photo_get(pid):
    """(바이트, 형식) 또는 None"""
    with photo_lock:
        return (photos.get(pid) or seed_photos.get(pid)) if isinstance(pid, str) else None


# 시연용 씨앗 사진: server/seed_photos/ 의 그림. PHOTO_KEEP 밀어내기와 따로 두어 지워지지 않아요.
# id 는 파일 이름에서 정해서(32자리 16진수) 서버를 다시 켜도 같아요. 저장해 둔 물건의 사진도 그대로 이어져요.
SEED_DIR = os.path.join(HERE, "seed_photos")
SEED_TYPES = {".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".png": "image/png", ".webp": "image/webp"}
seed_photos = {}         # 사진 id -> (바이트, 형식)


def seed_id(name):
    return hashlib.md5(("seed:" + name).encode("utf-8")).hexdigest()


def seed_photo(name):
    """그림 파일 이름 -> 사진 id. 파일이 없거나 이름이 비면 "" (사진 없이)."""
    if not name:
        return ""
    pid = seed_id(name)
    return pid if pid in seed_photos else ""


def load_seed_photos():
    try:
        names = sorted(os.listdir(SEED_DIR))
    except OSError:
        return
    for n in names:
        ct = SEED_TYPES.get(os.path.splitext(n)[1].lower())
        if n.startswith(("_", ".")) or ct is None:
            continue
        try:
            with open(os.path.join(SEED_DIR, n), "rb") as f:
                b = f.read(PHOTO_MAX + 1)
        except OSError:
            continue
        if 0 < len(b) <= PHOTO_MAX:
            seed_photos[seed_id(n)] = (b, ct)
    log("씨앗 사진 %d장을 올렸어요" % len(seed_photos))


def norm(s):
    return "".join(str(s).split())


def phone_pending(x, kind):
    return [e for e in x.events if e.get("t") == kind]


# ───── 계정 (폰 로그인) ─────
DATA = os.environ.get("ROADMARKET_DATA_DIR") or os.path.join(HERE, "data")   # 시험할 때는 임시 폴더로 돌려요
ACCOUNTS_FILE = os.path.join(DATA, "accounts.json")
OWNERS_FILE = os.path.join(DATA, "owners.json")
SALT = ""


def ensure_data():
    os.makedirs(DATA, mode=0o700, exist_ok=True)


def load_salt():
    """계정 열쇠를 만드는 비밀 값. 환경 변수 ACCOUNT_SALT, 없으면 data/salt (없으면 새로 만들어요). 값은 찍지 않아요."""
    v = env_value("ACCOUNT_SALT")
    if v:
        return v
    ensure_data()
    p = os.path.join(DATA, "salt")
    try:
        with open(p, encoding="utf-8") as f:
            v = f.read().strip()
        if v:
            return v
    except OSError:
        pass
    v = secrets.token_hex(32)
    fd = os.open(p, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        f.write(v)
    return v


def save_accounts():
    """임시 파일에 쓰고 바꿔 넣어요. LOCK 안에서 불러요."""
    try:
        ensure_data()
        tmp = ACCOUNTS_FILE + ".tmp"
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(dict(v=1, accounts=accounts, tokens=phone_tokens), f, ensure_ascii=False)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, ACCOUNTS_FILE)
    except OSError as e:
        log("계정 저장 실패", type(e).__name__)


def load_accounts():
    try:
        with open(ACCOUNTS_FILE, encoding="utf-8") as f:
            j = json.load(f)
    except FileNotFoundError:
        return
    except (OSError, ValueError) as e:
        log("계정 읽기 실패", type(e).__name__)
        return
    for k, a in (j.get("accounts") or {}).items():
        if not isinstance(a, dict):
            continue
        sells = [dict(name=str(s.get("name", ""))[:MAX_NAME], price=s.get("price") if is_int(s.get("price"), 0, MAX_PRICE) else 0,
                      photo=str(s.get("photo") or "")[:MAX_ID]) for s in a.get("sells", []) if isinstance(s, dict)][:MAX_ITEMS]
        wants = [str(w)[:MAX_NAME] for w in a.get("wants", []) if isinstance(w, str)][:MAX_ITEMS]
        cid = a.get("car") if isinstance(a.get("car"), str) else None
        accounts[k] = dict(kind=str(a.get("kind", "")), sells=sells, wants=wants, car=cid)
        if plate_ok(a.get("plate")):
            accounts[k]["plate"] = a["plate"].strip()
        if cid:
            car_owner[cid] = k
        if (sells or wants) and k not in owners:          # 예전 계정 목록을 주인 목록으로 옮겨요 (한 번만)
            o = owner(k)
            o["sells"] = [new_sell(s["name"], s["price"], "", "car", s.get("photo") or "", src="phone") for s in sells]
            o["wants"] = list(wants)
            o["ever"] = True
            accounts[k]["sells"], accounts[k]["wants"] = [], []
            mark()
    for h, k in (j.get("tokens") or {}).items():
        if k in accounts:
            phone_tokens[h] = k
    log("계정 %d개, 폰 토큰 %d개를 읽었어요" % (len(accounts), len(phone_tokens)))


PLATE_MAX = 12
PLATE_RE = re.compile(r"\d{4}$")


def plate_ok(v):
    """10절: 번호판은 12자까지, 숫자 네 자리로 끝나야 해요."""
    return isinstance(v, str) and 0 < len(v.strip()) <= PLATE_MAX and PLATE_RE.search(v.strip()) is not None


def plate4_of(ok):
    """그 주인 번호판의 뒤 네 자리 (없으면 ""). 남에게는 이것만 줘요."""
    a = accounts.get(ok) if ok else None
    p = a.get("plate") if a else None
    return p[-4:] if p else ""


def car_text(cid):
    """상대에게 주는 차 글자: 번호판이 있으면 "번호 끝 3456", 없으면 차가 올린 값."""
    p4 = plate4_of(car_owner.get(cid))
    return "번호 끝 " + p4 if p4 else car(cid).car_info


def tok_hash(t):
    return hashlib.sha256(t.encode("utf-8")).hexdigest()


def account_of(token):
    """(열쇠, 계정) 또는 (None, None)"""
    if not isinstance(token, str) or not 0 < len(token) <= MAX_ID:
        return None, None
    k = phone_tokens.get(tok_hash(token))
    return (k, accounts[k]) if k in accounts else (None, None)


def make_account(key, kind):
    if key in accounts:
        return True
    if len(accounts) >= MAX_ACCOUNTS:
        return False
    accounts[key] = dict(kind=kind, sells=[], wants=[], car=None)
    return True


def new_token(key):
    if len(phone_tokens) >= MAX_PAIRS:
        phone_tokens.pop(next(iter(phone_tokens)))
    t = secrets.token_hex(16)
    phone_tokens[tok_hash(t)] = key
    return t


def account_key(prefix, raw):
    return prefix + hashlib.sha256((raw + SALT).encode("utf-8")).hexdigest()[:24]


def same_item(a, b):
    return norm(a["name"]) == norm(b["name"]) and a["price"] == b["price"]


def live_photo(pid):
    return pid if pid and photo_get(pid) is not None else ""


def linked_car(acc):
    """이어진 차 (없으면 None). 차가 서버에서 지워졌으면 다시 만들어 바로 지워지지 않게 지금 시각으로 둬요."""
    cid = acc.get("car")
    if not cid:
        return None
    if cid not in cars:
        car(cid).last_seen = time.time()
    return car(cid)


def pair_account(key, cid):
    """계정에 차를 이어요. 한 계정에 차 하나, 한 차에 계정 하나.
    차 주인(c:<차>)의 물건·찾는 물건을 계정으로 합쳐요(이름+가격이 같으면 하나로). 옛 앱이면 계정 것을 차에 일로 보내요."""
    acc = accounts[key]
    old = car_owner.get(cid)
    if old and old != key and old in accounts:
        accounts[old]["car"] = None
    if acc["car"] and acc["car"] != cid:
        car_owner.pop(acc["car"], None)
    acc["car"] = cid
    car_owner[cid] = key
    x = linked_car(acc)
    o = owner(key)
    co = owners.pop("c:" + cid, None)
    car_have, car_wants = [], []
    if co:
        car_have = [s for s in co["sells"] if s.get("src") == "car"]
        car_wants = list(co["wants"])
        for s in co["sells"]:
            if not any(same_item(s, h) for h in o["sells"]) and len(o["sells"]) < MAX_ITEMS:
                o["sells"].append(s)
        for w in co["wants"]:
            if norm(w) not in [norm(v) for v in o["wants"]] and len(o["wants"]) < MAX_ITEMS:
                o["wants"].append(w)
        if not o.get("home") and co.get("home"):
            o["home"] = co["home"]
        o["deals"] = max(o["deals"], co["deals"])
        o["ever"] = o["ever"] or co["ever"]
        if co.get("stars_n"):                                      # 받은 별점도 합쳐요 (9절)
            o["stars_sum"] = o.get("stars_sum", 0) + co.get("stars_sum", 0)
            o["stars_n"] = o.get("stars_n", 0) + co["stars_n"]
            o["rating"] = round(o["stars_sum"] / o["stars_n"], 1)
    sent = 0
    if (co and co.get("legacy") == cid) or x.legacy_app:          # 옛 앱: 예전처럼 일로 보내요
        o["legacy"] = cid
        for s in o["sells"]:
            if not any(s is h for h in car_have):
                s["src"] = "phone"
        have = [s for s in car_have]
        for s in reversed(o["sells"]):
            if s["loc"] != "car" or s["status"] != "sale" or any(s is h for h in car_have):
                continue
            if len(have) >= MAX_ITEMS or any(same_item(s, h) for h in have):
                continue
            phone_event(x, "phone_add_sell", name=s["name"], price=s["price"], photo=live_photo(s.get("photo")))
            have.append(s)
            sent += 1
        wn = [norm(w) for w in car_wants]
        for w in reversed(o["wants"]):
            if norm(w) in wn or len(wn) >= MAX_ITEMS:
                continue
            phone_event(x, "phone_add_want", name=w)
            wn.append(norm(w))
            sent += 1
    x.sells = car_items(o)
    mark()
    return sent


def phone_view(key):
    """폰이 보는 상태. 예전 값(sells, wants, car)도 그대로 실어요."""
    acc = accounts[key]
    m = me_of(key)
    hid = "home:" + key
    rq = []
    for r in reqs.values():
        if r["seller"] == hid and r["state"] == "delivered":
            rq.append(dict(id=r["id"], item=dict(name=r["item"]["name"], price=r["item"]["price"]),
                           buyer=person(r["buyer"]), min=r["fake_min"]))
    dl = None
    for d in deals.values():
        if d["seller"] == hid and hid not in d["ended"]:     # 사는 차가 먼저 끝내도 폰의 물음(ask)이 남아요 (9절)
            bp = side_pos(d, d["buyer"])
            pl = (d["place"][1], d["place"][2]) if d["place"] else None
            dl = dict(id=d["id"], item=dict(name=d["item"]["name"], price=d["item"]["price"]),
                      place=dict(name=d["place"][0], lat=d["place"][1], lon=d["place"][2]) if d["place"] else None,
                      buyer=person(d["buyer"]), buyer_pos=dict(lat=bp[0], lon=bp[1]) if bp else None,
                      buyer_eta_min=eta_min(bp, pl) if bp and pl else None, ask=bool(d.get("ask", {}).get(hid)))
    lc = acc.get("car")
    if dl is None and lc:                                # 차와 이어진 계정: 그 차가 got_out 을 보낸 거래 (9절)
        for d in deals.values():
            if lc in (d["seller"], d["buyer"]) and lc not in d["ended"] and d.get("ask", {}).get(lc):
                peer = d["buyer"] if lc == d["seller"] else d["seller"]
                dl = dict(id=d["id"], item=dict(name=d["item"]["name"], price=d["item"]["price"]),
                          place=dict(name=d["place"][0], lat=d["place"][1], lon=d["place"][2]) if d["place"] else None,
                          buyer=person(peer), buyer_pos=None, buyer_eta_min=None, ask=True)
    us = [dict(id=u["id"], name=u["name"], desc=u["desc"], nick=u["nick"]) for u in urgents.values() if key in u["phones"]]
    return dict(ok=True, sells=m["sells"], wants=m["wants"], car=bool(acc.get("car")), me=m, requests=rq, deal=dl, urgents=us)


# ───── 상태 ─────
class Car:
    def __init__(self, cid):
        self.id = cid
        self.session = None
        self.last_seen = 0.0
        self.last_cid = 0
        self.trade_on = False
        self.busy = False
        self.sells = []          # [{id, name, price}] 맨 앞이 맨 위
        self.wants = []
        self.pos = None
        self.car_info = ""
        self.deals = 0
        self.seq = 0
        self.events = []         # 아직 앱이 받았다고 하지 않은 일
        self.fake_near = False   # D-03
        self.fake_gone = set()   # D-08 로 사라진 가짜 파는 차 번호(1~len(FAKE_SELLERS))
        self.fail_cid = 0        # 처리하다 실패한 명령 번호와 횟수
        self.fail_n = 0
        self.dest = None         # 내비 목적지 (약속 2절)
        self.driving = False
        self.browse = False      # 주변 물건 전체 목록을 보는 중
        self.new_app = False     # 새 명령(cmds)을 한 번이라도 보냈나
        self.legacy_app = False  # state 에 driving 이 없고 새 명령도 없으면 옛 앱으로 봐요
        self.route_dest = None   # 경로 추천을 이미 보낸 목적지
        self.deal_places = []    # 최근 거래의 만날 곳 (lat, lon). 이 근처 목적지는 새 목적지로 치지 않아요

    def online(self, now):
        return now - self.last_seen <= OFFLINE_SEC

    def push(self, ev):
        global seq_last
        with LOCK:
            seq_last += 1
            self.seq = seq_last
        ev["seq"] = self.seq
        self.events.append(ev)


cars = {}        # id -> Car
lists = {}       # (buyer, list_id) -> {buyer, id, rows:{no:{key, gone, req}}, sent, first_accept}
reqs = {}        # req id -> dict
deals = {}       # deal id -> dict
REQ_EVENTS = {"request", "request_end", "reply", "reply_no", "no_answer", "seller_cancel", "gone", "not_picked"}
# 주변 물건의 key: 상대 차 식별자를 앱에 보내지 않으려고 뜻 없는 임시 값을 써요
keys = {}        # (차 id, 물건 id 글자) -> 임시 key
key_of = {}      # 임시 key -> (차 id, 물건 id 글자)


def item_key(cid, item_id):
    k = (cid, str(item_id))
    t = keys.get(k)
    if t is None:
        t = keys[k] = "k" + uuid.uuid4().hex[:16]
        key_of[t] = k
    return t


def forget_keys(cid):
    for k in [k for k in keys if k[0] == cid]:
        key_of.pop(keys.pop(k), None)


def car(cid):
    c = cars.get(cid)
    if c is None:
        c = cars[cid] = Car(cid)
    return c


def is_fake(cid):
    return cid.startswith("fake")


def fake_seller_no(key):
    """'fake:s2' -> 2"""
    return int(key.split(":s")[1]) if key.startswith("fake:s") else None


# ───── 주변 물건 ─────
def other_trade(o, xid):
    """B-20, B-56: o 차가 x 아닌 사람과 요청이나 거래를 주고받는 중인가."""
    with_x = False
    for r in reqs.values():
        if r["buyer"] == o.id or (r["seller"] == o.id and r["state"] in ("delivered", "accepted")):
            peer = r["seller"] if r["buyer"] == o.id else r["buyer"]
            if peer != xid and r["buyer"] == o.id:      # 8절: 파는 쪽의 받은 요청은 줄을 세우므로 막지 않아요
                return True
            if peer == xid:
                with_x = True
    for d in deals.values():
        if o.id in (d["seller"], d["buyer"]) and o.id not in d["ended"]:
            peer = d["buyer"] if d["seller"] == o.id else d["seller"]
            if peer != xid:
                return True
            with_x = True
    return o.busy and not with_x       # 앱이 진행 중이라고 했는데 x와 주고받는 것이 아니면


def reach(x, p):
    """(주변인가, 분). 목적지가 있으면 돌아가는 시간 ≤ 10분, 없으면 지금 자리에서 ≤ 10분 (약속 2절)."""
    m = near_minutes(x.pos, p)
    if x.dest:
        det = m + near_minutes(p, x.dest) - near_minutes(x.pos, x.dest)
        return det <= NEAR_MIN, m
    return m <= NEAR_MIN, m


def near_for(x, now, with_busy=False):
    """x 차의 주변 물건. {key: {key,item,price,minutes,deals,seller,item_id,photo,busy,desc,loc,gu,owner}}
    with_busy=False 면 다른 거래 중인 차의 물건은 빼요 (B-20)."""
    out = {}
    if not x.trade_on:
        return out
    if x.fake_near:
        for i, (item, price, mins, d, _car, _r, _a) in enumerate(FAKE_SELLERS, 1):
            if i in x.fake_gone:
                continue
            key = "fake:s%d" % i
            out[key] = dict(key=key, item=item, price=price, minutes=mins, deals=d, seller=key, item_id=i,
                            photo=seed_photo(FAKE_PHOTOS.get(i, "")), busy=False,
                            desc="", loc="car", gu="", owner=None)
    if x.pos is None:
        return out
    mine = owner_key(x.id)
    for o in cars.values():
        if o.id == x.id or not o.trade_on or not o.online(now) or o.pos is None:
            continue
        ok, m = reach(x, o.pos)
        if not ok:
            continue
        b = other_trade(o, x.id)
        if b and not with_busy:
            continue
        ok2 = owner_key(o.id)
        for s in car_items(owner(ok2)):
            key = item_key(o.id, s["id"])
            out[key] = dict(key=key, item=s["name"], price=s["price"], minutes=m, deals=owner_deals(ok2), seller=o.id,
                            item_id=s["id"], photo=s.get("photo") or "", busy=b, desc=s.get("desc", ""), loc="car", gu="", owner=ok2)
    for ok2, ow in owners.items():                       # 집 물건: 집 위치로 계산하고 남에게는 구 이름만
        h = ow.get("home")
        if ok2 == mine or not h:
            continue
        items = [s for s in ow["sells"] if s["loc"] == "home" and s["status"] == "sale" and not s["hidden"]]
        if not items:
            continue
        ok, m = reach(x, (h["lat"], h["lon"]))
        if not ok:
            continue
        hid = "home:" + ok2
        b = other_trade(FakeCar(hid), x.id)
        if b and not with_busy:
            continue
        for s in items:
            key = item_key(hid, s["id"])
            out[key] = dict(key=key, item=s["name"], price=s["price"], minutes=m, deals=owner_deals(ok2), seller=hid,
                            item_id=s["id"], photo=live_photo(s.get("photo")), busy=b, desc=s.get("desc", ""), loc="home",
                            gu=h.get("gu", ""), owner=ok2)
    return out


def active_role(cid):
    for r in reqs.values():
        if r["buyer"] == cid or (r["seller"] == cid and r["state"] in ("delivered", "accepted")):
            return True
    for d in deals.values():
        if cid in (d["seller"], d["buyer"]) and cid not in d["ended"]:
            return True
    return False


def deliverable(seller, item_id, now):
    """S-30, S-31: 꺼져 있거나 진행 중인 차에는 요청이 가지 않아요."""
    s = cars.get(seller)
    if s is None or not s.trade_on or not s.online(now) or s.busy or active_role(seller):
        return False
    return any(str(i["id"]) == str(item_id) for i in car_items(owner(owner_key(seller))))


# ───── 요청 끝내기 ─────
def end_req(r, buyer_ev=None, seller_why=None):
    """요청 하나를 끝내요. buyer_ev: 사는 차에게 보낼 일 이름, seller_why: 파는 차에게 보낼 끝난 이유."""
    reqs.pop(r["id"], None)
    L = lists.get((r["buyer"], r["list"]))
    if L is not None and r["no"] in L["rows"]:
        L["rows"][r["no"]]["req"] = None
    if buyer_ev and not is_virt(r["buyer"]):
        car(r["buyer"]).push(dict(t=buyer_ev, list=r["list"], no=r["no"]))
    if seller_why and not is_virt(r["seller"]) and r["state"] in ("delivered", "accepted"):
        car(r["seller"]).push(dict(t="request_end", req=r["id"], why=seller_why))
    if r["state"] in ("delivered", "accepted"):          # 8절: 줄의 다음 요청을 보내요
        next_in_queue(r["seller"], r["item"]["id"])
    if L is not None:
        settle(L)


def settle(L):
    """수락한 곳이 하나도 안 남으면 고르는 시간을 멈춰요 (앱의 예전 settle)."""
    if not any(r["state"] == "accepted" for r in list_reqs(L)):
        L["first_accept"] = None


def list_reqs(L):
    return [reqs[row["req"]] for row in L["rows"].values() if row["req"] in reqs]


def drop_list(L, seller_why):
    """목록을 닫아요. 남은 요청은 seller_why 이유로 끝나요."""
    for r in list_reqs(L):
        end_req(r, None, seller_why)
    lists.pop((L["buyer"], L["id"]), None)


def end_requests_of(cid):
    """C-15 다시 시작, 거래 알림 꺼짐: 그 차가 주고받던 요청을 모두 끝내요."""
    for L in [L for L in lists.values() if L["buyer"] == cid]:
        drop_list(L, "withdrew")                            # 파는 차에는 S-29
    for r in [r for r in reqs.values() if r["seller"] == cid]:
        if r["state"] == "accepted":
            end_req(r, "seller_cancel", None)               # 사는 차에는 B-47
        elif r["state"] == "delivered":
            end_req(r, "reply_no", None)                    # 사는 차에는 B-45
        # 'sent'(가지 않은 요청)는 20초 뒤 답 없음으로 끝나요


# ───── 거래 ─────
def make_deal(r, now):
    """거래가 정해짐 (S-25, B-44, B-49)."""
    reqs.pop(r["id"], None)
    did = uuid.uuid4().hex[:12]
    seller, buyer = r["seller"], r["buyer"]
    d = dict(id=did, seller=seller, buyer=buyer, item=r["item"], decided={}, ended=set(), place=None, computing=False)
    deals[did] = d
    sinfo = fake_info(seller) if is_fake(seller) else virt_info(seller) if is_virt(seller) else (car_text(seller), car(seller).deals)
    binfo = fake_info(buyer) if is_fake(buyer) else (car_text(buyer), car(buyer).deals)
    d["owner"] = r.get("owner")
    set_status(r.get("owner"), r["item"]["id"], "deal")                # 거래가 정해지면 그 물건은 거래 중
    for q in [q for q in reqs.values() if q["seller"] == seller and str(q["item"]["id"]) == str(r["item"]["id"])]:
        end_req(q, "reply_no", None)                                    # 8절: 같은 물건의 나머지 요청은 거절
    if seller.startswith("home:"):
        h = owner(seller[5:]).get("home") or {}
        d["decided"][seller] = dict(with_dest=False, dest=None, pos=(h["lat"], h["lon"]) if h else None)
    if not is_virt(buyer):
        L = lists.get((buyer, r["list"]))
        if L is not None:
            L["rows"][r["no"]]["req"] = None
            drop_list(L, "picked_other")                    # 나머지는 S-26
        # 집 물건이면 home:true — 사는 차는 목적지를 묻지 않아도 돼요 (만날 곳은 서버가 바로 정해요)
        car(buyer).push(dict(t="deal", deal=did, selling=False, item=r["item"]["name"], price=r["item"]["price"],
                             peer_car=sinfo[0], peer_deals=sinfo[1], list=r["list"], no=r["no"],
                             **(dict(home=True) if seller.startswith("home:") else {})))
    if not is_virt(seller):
        car(seller).push(dict(t="deal", deal=did, selling=True, item=r["item"]["name"], price=r["item"]["price"],
                              sell_id=r["item"]["id"], peer_car=binfo[0], peer_deals=binfo[1]))
    # 가짜 상대는 목적지 없이 곧바로 정해요. 자리는 진짜 차에서 북쪽으로 그 분만큼 (임의)
    for side in (seller, buyer):
        if is_fake(side):
            real = buyer if side == seller else seller
            p = car(real).pos
            d["decided"][side] = dict(with_dest=False, dest=None, pos=offset_north(p, r["fake_min"]) if p else None)
    log("DEAL", did, "seller", short(seller), "buyer", short(buyer), r["item"]["name"])
    if seller.startswith("home:"):          # 집 물건: 사는 차의 목적지 답을 기다리지 않고 바로 정해요 (나중 dest 는 무시)
        if not is_virt(buyer):
            d["decided"][buyer] = dict(with_dest=False, dest=None, pos=car(buyer).pos)
        maybe_place(d)
    return d


def fake_info(cid):
    if cid == "fake:buyer":
        return (FAKE_BUYER_CAR, FAKE_BUYER_DEALS)
    n = fake_seller_no(cid)
    return (FAKE_SELLERS[n - 1][4], FAKE_SELLERS[n - 1][3])


def parties(d):
    return [p for p in (d["seller"], d["buyer"]) if not is_virt(p) and p not in d["ended"]]


def maybe_place(d):
    """M-20, M-21: 두 사람 모두 목적지가 정해지면 만날 곳을 골라 두 차에 같은 곳을 보내요."""
    if d["place"] is not None or d["computing"]:
        return
    if not all(p in d["decided"] for p in (d["seller"], d["buyer"])):
        return
    for p in (d["seller"], d["buyer"]):          # 진짜 차는 지금 위치를 그때 읽어요
        if not is_virt(p):
            d["decided"][p]["pos"] = car(p).pos
    if d["seller"].startswith("home:"):          # 집 물건: 판매자 집 근처
        snap = {k: dict(v) for k, v in d["decided"].items()}
        hc = home_cands_cached(snap[d["seller"]]["pos"])
        if KAKAO_KEY and hc is not None:             # 미리 받아 둔 후보가 있으면 수락하는 순간 바로 정해요
            apply_place(d, home_pick(snap[d["seller"]]["pos"], snap[d["buyer"]], hc))
        elif KAKAO_KEY:
            d["computing"] = True
            threading.Thread(target=home_place_async, args=(d["id"], d["seller"], d["buyer"], snap), daemon=True).start()
        else:
            apply_place(d, home_pick(snap[d["seller"]]["pos"], snap[d["buyer"]], None))
        return
    if KAKAO_KEY:
        d["computing"] = True
        snap = {k: dict(v) for k, v in d["decided"].items()}
        threading.Thread(target=place_async, args=(d["id"], d["seller"], d["buyer"], snap), daemon=True).start()
    else:
        apply_place(d, choose(d["seller"], d["buyer"], d["decided"], PLACES, "목록"))


def place_async(did, seller, buyer, decided):
    pick = None
    try:
        cands, src = None, "목록"
        ps = [decided[p]["pos"] for p in (seller, buyer) if decided[p]["pos"]]
        if len(ps) == 2:
            cands = kakao_candidates(ps[0], ps[1])
            if cands:
                src = "카카오"
        pick = choose(seller, buyer, decided, cands or PLACES, src, by_route=True)    # 잠금 밖
    except Exception as e:
        log("PLACE 오류", repr(e))
    finally:
        with LOCK:
            d = deals.get(did)
            if d is not None:
                d["computing"] = False
                if d["place"] is None:
                    if pick is None:                    # 실패하면 적어 둔 목록으로 물러나요
                        try:
                            pick = choose(seller, buyer, decided, PLACES, "목록")
                        except Exception as e:
                            log("PLACE 목록 오류", repr(e))
                            pick = PLACES[1]
                    apply_place(d, pick)


def apply_place(d, pick):
    name, lat, lon = pick
    d["place"] = pick
    extra_kw = dict(note=HOME_PLACE_NOTE) if d["seller"].startswith("home:") else {}
    for p in parties(d):
        car(p).deal_places = (car(p).deal_places + [(lat, lon)])[-5:]
        car(p).push(dict(t="place", deal=d["id"], name=name, lat=lat, lon=lon, **extra_kw))


def extra(info, c):
    """그 사람이 장소 c 때문에 더 가야 하는 거리(km). 목적지가 없으면 지금 위치에서 c까지 (M-20)."""
    p = info["pos"]
    if info["with_dest"] and info["dest"]:
        dst = info["dest"]
        return km(p, c) + km(c, dst) - km(p, dst)
    return km(p, c)


def route_choose(s, b, top):
    """M-20: 직선거리로 추린 후보만 길을 따라 더 걸리는 시간(초)으로 다시 계산해요. 하나라도 실패하면 None.
    잠금 밖에서만 불러요. 호출은 한꺼번에 해서 전체가 3초 남짓이에요."""
    pairs = set()
    for info in (s, b):
        p = info["pos"]
        dst = info["dest"] if info["with_dest"] and info["dest"] else None
        if dst:
            pairs.add((p, dst))
        for x in top:
            c = (x[2][1], x[2][2])
            pairs.add((p, c))
            if dst:
                pairs.add((c, dst))
    pairs = list(pairs)
    with concurrent.futures.ThreadPoolExecutor(max_workers=min(12, len(pairs))) as ex:
        got = dict(zip(pairs, ex.map(lambda ab: route_fetch(ab[0], ab[1]), pairs)))
    if any(v is None for v in got.values()):
        return None

    def ext(info, c):
        p = info["pos"]
        dst = info["dest"] if info["with_dest"] and info["dest"] else None
        if dst:
            return got[(p, c)][0] + got[(c, dst)][0] - got[(p, dst)][0]
        return got[(p, c)][0]

    scored = []
    for x in top:
        c = (x[2][1], x[2][2])
        es, eb = ext(s, c), ext(b, c)
        scored.append((es + eb + abs(es - eb) / 2, es, x[2]))
    best = min(x[0] for x in scored)
    near = [x for x in scored if x[0] - best <= TIE_SEC]     # 비슷하면(1분 안) 판매자가 덜 가는 곳
    pick = min(near, key=lambda x: (x[1], x[0]))
    log("PLACE (길찾기 %d곳 다시 계산) -> %s 값 %d초 판매자 %d초" % (len(top), pick[2][0], pick[0], pick[1]))
    return pick[2]


def choose(seller, buyer, decided, cands, src, by_route=False):
    """by_route=True 는 잠금 밖에서만 (길찾기를 불러요)."""
    s, b = decided[seller], decided[buyer]
    if s["pos"] is None or b["pos"] is None:
        # 위치를 모르면 계산할 수 없어요. 예전 정해 둔 두 곳으로 물러나요 (임의)
        anyd = (s["with_dest"] and s["dest"]) or (b["with_dest"] and b["dest"])
        pick = PLACES[0] if anyd else PLACES[1]
        log("PLACE (위치 모름) ->", pick[0])
        return pick
    scored = []
    for (name, lat, lon) in cands:
        es, eb = extra(s, (lat, lon)), extra(b, (lat, lon))
        scored.append((es + eb + abs(es - eb) / 2, es, (name, lat, lon)))
    if by_route and KAKAO_KEY:
        top = sorted(scored, key=lambda x: x[0])[:ROUTE_CANDS]
        try:
            rp = route_choose(s, b, top)
        except Exception as e:
            log("PLACE 길찾기 오류", repr(e))
            rp = None
        if rp is not None:
            return rp
        log("PLACE 길찾기 실패 -> 직선거리로 정해요")
    best = min(x[0] for x in scored)
    near = [x for x in scored if x[0] - best <= TIE_KM]     # 비슷하면(500m 안) 판매자가 덜 가는 곳
    pick = min(near, key=lambda x: (x[1], x[0]))
    log("PLACE (%s %d곳) -> %s 값 %.2fkm 판매자 %.2fkm" % (src, len(cands), pick[2][0], pick[0], pick[1]))
    return pick[2]


def kakao_candidates(a, b):
    """두 차의 중간 지점에서 편의점·주유소·주차장을 찾아요. 실패하거나 0개면 None."""
    mid = ((a[0] + b[0]) / 2, (a[1] + b[1]) / 2)
    radius = int(min(20000, max(1000, km(a, b) * 1000 / 2 + 1000)))     # 임의: 두 차 거리의 절반 + 1km
    out = []
    end = time.time() + KAKAO_TOTAL
    for cat in KAKAO_CATS:          # 한 종류가 실패해도 받은 것은 써요
        left = end - time.time()
        if left <= 0.2:
            log("KAKAO 시간 다 씀:", cat)
            break
        try:
            q = urllib.parse.urlencode(dict(category_group_code=cat, x="%.6f" % mid[1], y="%.6f" % mid[0],
                                            radius=radius, size=KAKAO_SIZE, sort="distance"))
            req = urllib.request.Request("https://dapi.kakao.com/v2/local/search/category.json?" + q,
                                         headers={"Authorization": "KakaoAK " + KAKAO_KEY})
            with urllib.request.urlopen(req, timeout=min(KAKAO_TIMEOUT, left)) as res:
                docs = json.loads(res.read(1024 * 1024).decode("utf-8")).get("documents", [])
            for doc in docs:
                lat, lon = float(doc["y"]), float(doc["x"])
                if math.isfinite(lat) and math.isfinite(lon):
                    out.append((str(doc["place_name"]), lat, lon))
        except Exception as e:      # 키 값은 머리글에만 있어요. 오류 글에는 실리지 않아요
            log("KAKAO 실패:", cat, type(e).__name__, getattr(e, "code", ""))
    log("KAKAO 후보 %d곳 (반경 %dm)" % (len(out), radius))
    return out or None


# ───── 앱이 보낸 명령 ─────
def handle_cmd(x, c, now):
    t = c.get("t")
    if t == "list":                                   # B-20: 앱이 목록을 열었어요
        near = near_for(x, now, with_busy=True)
        rows = {}
        for r in c.get("rows", []):
            o = near.get(r["key"])
            rows[int(r["no"])] = dict(key=r["key"], gone=o is None, busy=bool(o and o["busy"]), req=None)
            if o and o["busy"]:
                x.push(dict(t="busy", list=int(c["list"]), no=int(r["no"])))       # B-56
        for L in [L for L in lists.values() if L["buyer"] == x.id]:
            drop_list(L, "withdrew")
        lists[(x.id, int(c["list"]))] = dict(buyer=x.id, id=int(c["list"]), rows=rows, sent=0, first_accept=None)
    elif t == "close_list":                           # B-29
        L = lists.get((x.id, int(c["list"])))
        if L:
            drop_list(L, "withdrew")
    elif t == "send":                                 # B-40, B-41
        L = lists.get((x.id, int(c["list"])))
        no = int(c["no"])
        if not L or no not in L["rows"]:
            return
        row = L["rows"][no]
        if row["req"] is not None and row["req"] in reqs:     # 이미 요청을 보낸 줄이면 무시해요
            return
        near = near_for(x, now, with_busy=True)
        o = near.get(row["key"])
        if row["gone"] or o is None:
            row["gone"] = True
            x.push(dict(t="gone", list=L["id"], no=no))
            return
        if row.get("busy") or o["busy"]:              # B-56: 다른 거래 중인 차에는 보내지 않아요
            if not row.get("busy"):
                row["busy"] = True
                x.push(dict(t="busy", list=L["id"], no=no))
            return
        rid = uuid.uuid4().hex[:12]
        r = dict(id=rid, buyer=x.id, list=L["id"], no=no, seller=o["seller"],
                 item=dict(id=o["item_id"], name=o["item"], price=o["price"], photo=o["photo"]),
                 state="sent", t_sent=now, t_acc=None, reply_at=None, pick_at=None, script=None, fake_min=o["minutes"],
                 owner=o.get("owner"))
        reqs[rid] = r
        row["req"] = rid
        L["sent"] += 1                                # 실제로 요청을 만든 것만 세요 (B-44)
        if not is_fake(o["seller"]) and item_queue_busy(o["seller"], o["item_id"], rid):
            r["state"] = "queued"                     # 8절: 같은 물건에 먼저 온 요청이 있으면 줄을 서요
        elif o["seller"].startswith("home:"):         # 집 물건: 주인의 폰으로 (3절)
            r["state"] = "delivered"
            home_cands_warm(o["seller"])
        elif is_fake(o["seller"]):
            spec = FAKE_SELLERS[fake_seller_no(o["seller"]) - 1]
            r["state"] = "delivered"
            if spec[5] is not None:
                r["reply_at"] = now + spec[5]
                r["script"] = "accept" if spec[6] else "reject"
        elif deliverable(o["seller"], o["item_id"], now):
            r["state"] = "delivered"
            car(o["seller"]).push(dict(t="request", req=rid, sell_id=o["item_id"], minutes=o["minutes"], deals=x.deals,
                                       photo=o["photo"], buyer=person(x.id)))
        log("SEND", short(x.id), "->", short(o["seller"]), o["item"], r["state"])
    elif t == "withdraw":                             # B-54
        L = lists.get((x.id, int(c["list"])))
        if L:
            drop_list(L, "withdrew")
    elif t == "pick":                                 # B-49
        L = lists.get((x.id, int(c["list"])))
        if not L:
            return
        row = L["rows"].get(int(c["no"]))
        r = reqs.get(row["req"]) if row else None
        if r and r["state"] == "accepted":
            make_deal(r, now)
    elif t in ("accept", "reject", "cancel_accept", "ignore"):
        r = reqs.get(c.get("req"))
        if r is None or r["seller"] != x.id:
            return
        if t == "accept" and r["state"] == "delivered":          # S-22
            r["state"] = "accepted"
            r["t_acc"] = now
            for q in [q for q in reqs.values() if q["seller"] == r["seller"] and q["state"] == "queued"
                      and str(q["item"]["id"]) == str(r["item"]["id"])]:
                end_req(q, "reply_no", None)                      # 8절: 수락하면 줄의 나머지는 거절
            if is_fake(r["buyer"]):
                if r["script"] in ("me", "other"):
                    r["pick_at"] = now + FAKE_BUYER_PICK_SEC
                else:
                    r["pick_at"] = now + PICK_SEC                 # S-27 가짜 사는 차가 고르지 않음
                return
            L = lists.get((r["buyer"], r["list"]))
            if L and L["sent"] == 1:                              # B-44 한 곳에만 보냄
                make_deal(r, now)
                return
            if L and L["first_accept"] is None:
                L["first_accept"] = now
            car(r["buyer"]).push(dict(t="reply", list=r["list"], no=r["no"], ok=True))   # B-43
        elif t == "reject" and r["state"] == "delivered":         # S-23
            end_req(r, "reply_no", None)
        elif t == "cancel_accept" and r["state"] == "accepted":   # S-28
            end_req(r, "seller_cancel", None)
        elif t == "ignore" and r["state"] == "delivered":         # 앱이 받을 수 없었음: 가지 않은 요청으로
            r["state"] = "sent"
    elif t == "dest":                                 # M-02~M-04, M-21: 내 목적지가 정해짐
        d = deals.get(c.get("deal"))
        if d is None or x.id not in (d["seller"], d["buyer"]) or x.id in d["decided"]:
            return
        dst = (float(c["lat"]), float(c["lon"])) if c.get("lat") is not None and c.get("lon") is not None else None
        d["decided"][x.id] = dict(with_dest=bool(c.get("with")), dest=dst, pos=x.pos)
        maybe_place(d)
    elif t == "cancel_deal":                          # M-15 → 상대에게 M-16
        d = deals.get(c.get("deal"))
        if d is None or x.id not in (d["seller"], d["buyer"]) or x.id in d["ended"]:
            return
        d["ended"].add(x.id)
        for p in parties(d):
            car(p).push(dict(t="peer_cancel", deal=d["id"]))
            d["ended"].add(p)
        if is_virt(d["seller"]):
            d["ended"].add(d["seller"])
        finish_item(d, "sale")                         # 취소하면 다시 판매 중
    elif t == "deal_end":                             # M-10, M-11: 내 쪽만 끝나요 (M-17)
        d = deals.get(c.get("deal"))
        if d is not None and x.id in (d["seller"], d["buyer"]):
            # 거래했어요 = done:true, 안 했어요 = done:false (없으면 false). stars 0~5 또는 없음 (9절, 옛 앱은 없음)
            end_side(d, x.id, c.get("done") is True, c.get("stars"))
    elif t == "got_out":                              # 9절: 거래 중 P에서 운전석 문이 열렸다 닫힘 → 이어진 폰에 물음
        for d in deals.values():
            if x.id in (d["seller"], d["buyer"]) and x.id not in d["ended"]:
                d.setdefault("ask", {})[x.id] = True
    elif t in NEW_CMDS:
        new_cmd(x, c, now)


# ───── 타이머 (서버에서만 세요) ─────
def tick():
    now = time.time()
    for r in list(reqs.values()):           # 요청 하나의 오류가 나머지를 멈추지 않게 해요
        try:
            tick_req(r, now)
        except Exception as e:
            log("tick 요청 오류", repr(e))
    for L in list(lists.values()):          # 목록 하나(차 하나)의 오류도 마찬가지
        try:
            tick_list(L, now)
        except Exception as e:
            log("tick 목록 오류", repr(e))
    try:
        cleanup(now)
    except Exception as e:
        log("정리 오류", repr(e))
    try:
        tick_urgents(now)
    except Exception as e:
        log("급구 오류", repr(e))
    save_owners()


def cleanup(now):
    """11: 10분 넘게 조용한 차, 둘 다 끝낸 지 10분 지난 거래를 지워요."""
    for d in list(deals.values()):
        if parties(d):
            continue
        if d.get("ended_at") is None:
            d["ended_at"] = now
        elif now - d["ended_at"] >= IDLE_SEC:
            finish_item(d, "sale")                     # 끝을 모르고 지워지는 거래의 물건은 다시 판매 중
            deals.pop(d["id"], None)
    def idle(c):                    # 받지 않은 폰 일이 남은 차는 하루까지 기다려요
        keep = PHONE_KEEP_SEC if any(str(e.get("t", "")).startswith("phone_") for e in c.events) else IDLE_SEC
        return now - c.last_seen > keep
    for cid in [cid for cid, c in cars.items() if idle(c)]:
        end_requests_of(cid)
        for r in [r for r in reqs.values() if r["seller"] == cid]:     # 가지 않은 요청도 끝내요
            end_req(r, "no_answer", None)
        for d in deals.values():                                       # 남은 거래에서는 이 차가 끝낸 것으로
            if cid in (d["seller"], d["buyer"]):
                d["ended"].add(cid)
        forget_keys(cid)
        cars.pop(cid, None)
        log("정리: 차", short(cid))


def tick_req(r, now):
    if r["id"] not in reqs:
        return
    if r["state"] in ("sent", "delivered") and now - r["t_sent"] >= (
            HOME_ANSWER_SEC if r["seller"].startswith("home:") else ANSWER_SEC):      # S-24, B-46
        end_req(r, "no_answer", "timeout")
    elif r["reply_at"] and r["state"] == "delivered" and now >= r["reply_at"] and is_fake(r["seller"]):
        r["reply_at"] = None
        if r["script"] == "accept":
            handle_cmd(FakeCar(r["seller"]), dict(t="accept", req=r["id"]), now)
        else:
            end_req(r, "reply_no", None)                                              # B-45
    elif r["pick_at"] and r["state"] == "accepted" and now >= r["pick_at"] and is_fake(r["buyer"]):
        if r["script"] == "me":
            make_deal(r, now)                                                         # S-25
        elif r["script"] == "other":
            end_req(r, None, "picked_other")                                          # S-26
        else:
            end_req(r, None, "no_pick")                                               # S-27


def tick_list(L, now):
    if (L["buyer"], L["id"]) not in lists:
        return
    if L["first_accept"] is not None and now - L["first_accept"] >= PICK_SEC:         # B-51
        car(L["buyer"]).push(dict(t="not_picked", list=L["id"]))
        drop_list(L, "no_pick")
        return
    x = cars.get(L["buyer"])
    if x is None:
        return
    near = near_for(x, now, with_busy=True)
    for no, row in L["rows"].items():                                                 # B-53
        if not row["gone"] and row["key"] not in near:
            row["gone"] = True
            r = reqs.get(row["req"])
            if r:
                end_req(r, None, "withdrew")
            x.push(dict(t="gone", list=L["id"], no=no))
        elif (not row["gone"] and not row.get("busy") and near[row["key"]]["busy"]
              and not (row["req"] and row["req"] in reqs)):                          # B-56: 되살아나지 않아요
            row["busy"] = True
            x.push(dict(t="busy", list=L["id"], no=no))


class FakeCar:
    """가짜 차(또는 집 물건 주인의 폰)가 앱처럼 명령을 넣을 때 쓰는 껍데기."""
    def __init__(self, cid):
        self.id = cid
        self.busy = False


def ticker():
    while True:
        time.sleep(0.2)
        with LOCK:
            try:
                tick()
            except Exception as e:
                log("tick 오류", repr(e))


# ───── 주인별 목록 (약속 0·1절): 물건과 찾는 물건의 원본은 서버가 가져요 ─────
owners = {}              # 주인 열쇠(계정 열쇠 또는 "c:<차>") -> {sells, wants, wants_alert, home, nick, deals, rating, ever, legacy}
owners_dirty = [False]
urgents = {}             # 급구 id -> {id, owner, car, name, desc, nick, sent:set(차), phones:set(주인), offers:[...], t}
HOME_PLACE_NOTE = "판매자 집 근처의 트이고 사람 다니는 곳이에요"
NEW_CMDS = {"sell_add", "sell_update", "sell_remove", "sell_hide", "sell_status", "want_add", "want_remove", "wants_alert",
            "home_here", "browse", "urgent_post", "urgent_cancel", "urgent_reply", "urgent_pick", "route_answer", "course_ask",
            "restore"}

# 시연용 길 목록 (약속 5절). 실제 주행 기록이 없어 손으로 적은 시연용 자료예요.
# 에뮬레이터 기본 위치(37.4127, 127.0947) 근처의 경유 지점이고, 이름과 좌표는 대략 맞춘 것이에요.
DEMO_ROUTES = [
    dict(label="카시트", keys=["카시트", "유아", "아기", "유모차"], via=("시연용 · 수정로 상가 길", 37.4380, 127.1370)),
    # 기본 위치 -> demo.sh dest(37.4854, 127.0364) 시연용 경유지: 카카오 길찾기로 6분쯤 더, 직선 추정으로는 1분쯤 더.
    # (위 수정로 길은 같은 목적지에서 길찾기로 19분 더라 15분 규칙에 걸려요.) 이름은 대략이에요
    dict(label="카시트", keys=["카시트", "유아", "아기", "유모차"], via=("시연용 · 내곡동 길", 37.4600, 127.0700)),
    dict(label="골프용품", keys=["골프", "드라이버", "퍼터", "거리측정기"], via=("시연용 · 판교 IC 옆 길", 37.4010, 127.1050)),
    dict(label="캠핑용품", keys=["캠핑", "텐트", "의자", "랜턴"], via=("시연용 · 세곡동 길", 37.4650, 127.1040)),
    dict(label="차량용품", keys=["거치대", "블랙박스", "타이어", "차량용"], via=("시연용 · 고등동 길", 37.4260, 127.0940)),
]
# 시연용 드라이브 코스 (약속 5절)
DEMO_COURSES = [
    dict(title="남한산성", via=("시연용 · 남한산성 입구", 37.4794, 127.1817)),
    dict(title="청계산 둘레", via=("시연용 · 청계산 입구", 37.4466, 127.0560)),
    dict(title="율동공원 호숫가", via=("시연용 · 율동공원", 37.3807, 127.1458)),
]
COURSE_MAX_KM = 30       # 이보다 먼 코스는 추천하지 않아요 (임의)
FAKE_URGENT_SEC = 300    # 데모 급구가 사라지는 시간 (임의)


def mark():
    owners_dirty[0] = True


def owner(ok):
    o = owners.get(ok)
    if o is None:
        o = owners[ok] = dict(sells=[], wants=[], wants_alert=True, home=None,
                              nick="이용자 %04d" % (1000 + secrets.randbelow(9000)), deals=0, rating=None, ever=False, legacy=None,
                              stars_sum=0, stars_n=0)
        mark()
    return o


def owner_key(cid):
    return car_owner.get(cid) or ("c:" + cid)


def owner_car(ok):
    """그 주인의 차 id 또는 None"""
    if ok.startswith("c:"):
        return ok[2:]
    a = accounts.get(ok)
    return a.get("car") if a else None


def owner_deals(ok):
    o = owner(ok)
    x = cars.get(owner_car(ok) or "")
    return max(o["deals"], x.deals if x is not None else 0)


def new_sell(name, price, desc, loc, photo="", src=None, sid=None):
    s = dict(id=sid if sid is not None else "s" + uuid.uuid4().hex[:10], name=name, price=price, desc=desc, photo=photo,
             loc=loc, status="sale", hidden=False)
    if src:
        s["src"] = src
    return s


def car_items(o):
    """남에게 보이는 차 물건: 판매 중이고 숨기지 않은 것."""
    return [s for s in o["sells"] if s["loc"] == "car" and s["status"] == "sale" and not s["hidden"]]


def find_sell(o, sid):
    return next((s for s in o["sells"] if str(s["id"]) == str(sid)), None)


def set_status(ok, sid, status):
    if not ok:
        return
    s = find_sell(owner(ok), sid)
    if s is not None:
        s["status"] = status
        mark()


def finish_item(d, status):
    """거래가 끝나거나 취소되면 물건 상태를 한 번만 정해요."""
    if d.get("item_done") or not d.get("owner"):
        return
    o = owner(d["owner"])
    s = find_sell(o, d["item"]["id"])
    if s is not None and s["status"] == "deal":
        s["status"] = status
        mark()
    d["item_done"] = True


def is_virt(cid):
    """가짜 차 또는 집 물건 주인(폰). 이 둘에는 차 일을 보내지 않아요."""
    return cid.startswith("fake") or cid.startswith("home:")


def virt_info(cid):
    ok = cid[5:]
    h = owner(ok).get("home") or {}
    return ("집 물건 · " + (h.get("gu") or ""), owner_deals(ok))


def person(cid):
    """{nick, deals, rating} (약속 2절 seller, 8절 buyer)"""
    if cid == "fake:buyer":
        return dict(nick="이용자 3307", deals=FAKE_BUYER_DEALS, rating=None, plate4="3307")
    if cid.startswith("fake"):
        n = fake_seller_no(cid)
        return dict(nick="이용자 %04d" % (2000 + (n or 0)), deals=FAKE_SELLERS[n - 1][3] if n else 0, rating=None, plate4="")
    ok = cid[5:] if cid.startswith("home:") else owner_key(cid)
    o = owner(ok)
    return dict(nick=o["nick"], deals=owner_deals(ok), rating=o["rating"], plate4=plate4_of(ok))


def add_stars(ok, n):
    """9절: 받은 별점을 쌓아요. rating = 평균(소수 한 자리)."""
    o = owner(ok)
    o["stars_sum"] = o.get("stars_sum", 0) + n
    o["stars_n"] = o.get("stars_n", 0) + 1
    o["rating"] = round(o["stars_sum"] / o["stars_n"], 1)
    mark()


def end_side(d, side, done, stars):
    """9절: 한 사람 쪽에서 거래를 끝내요(차의 deal_end, 폰의 deal/review. 같은 쪽은 먼저 온 것만).
    별점은 상대의 주인에게. 한쪽이라도 거래했어요면 sold, 답한 쪽이 모두 못 했어요면 sale."""
    if side in d["ended"]:
        return False
    d["ended"].add(side)
    peer = d["buyer"] if side == d["seller"] else d["seller"]
    if side.startswith("home:"):
        so = owner(side[5:])
        if done and not d.get("seller_counted") and so.get("legacy") is None:
            d["seller_counted"] = True
            so["deals"] += 1
            mark()
    elif not is_fake(side):
        o = owner(owner_key(side))
        if done and o.get("legacy") != side:          # 옛 앱은 자기가 세요
            o["deals"] += 1
            if side == d["buyer"]:                    # M-10: 산 사람은 맞는 찾는 물건을 지워요
                o["wants"] = [w for w in o["wants"] if norm(w) not in norm(d["item"]["name"])]
            mark()
    if is_int(stars, 0, 5) and not is_fake(peer):
        add_stars(peer[5:] if peer.startswith("home:") else owner_key(peer), stars)
    d.setdefault("answers", {})[side] = done
    if is_fake(peer):                                 # 가짜 상대는 답하지 않아요
        d["ended"].add(peer)
    elif peer.startswith("home:") and peer not in d["ended"]:     # 집 판매자: 폰에 "오늘 거래 어땠어요?"
        d.setdefault("ask", {})[peer] = True
        po = owner(peer[5:])
        if done and not d.get("seller_counted") and po.get("legacy") is None:   # 예전처럼 사는 차의 답으로도 세요
            d["seller_counted"] = True
            po["deals"] += 1
            mark()
    if done:
        finish_item(d, "sold")
    elif all(p in d["answers"] for p in (d["seller"], d["buyer"]) if not is_fake(p)):
        finish_item(d, "sale")
    return True


def eta_min(a, b):
    """9절: a에서 b까지 몇 분. 길찾기 기억(1분)을 쓰고 뒤에서 채워요. 모르거나 실패하면 직선 추정."""
    if a is None or b is None:
        return None
    m = minutes(a, b)
    if not KAKAO_KEY:
        return m
    fresh, v, ever = route_seen(a, b)
    if not fresh:
        route_later(a, b)
    if not ever or v is None:
        return m
    return int(math.ceil(v[0] / 60 - 1e-9))


def side_pos(d, p):
    """거래 중인 상대의 지금 위치. 끝낸 쪽, 집 판매자의 집은 주지 않아요."""
    if p in d["ended"] or p.startswith("home:"):
        return None
    if is_fake(p):
        return (d["decided"].get(p) or {}).get("pos")
    c = cars.get(p)
    return c.pos if c is not None else None


def meet_of(x):
    """9절: 거래가 정해지고 만날 곳이 있는 동안의 meet. 없으면 None."""
    for d in deals.values():
        if x.id in (d["seller"], d["buyer"]) and x.id not in d["ended"] and d["place"] is not None:
            name, lat, lon = d["place"]
            pl = (lat, lon)
            peer = d["buyer"] if x.id == d["seller"] else d["seller"]
            if peer.startswith("home:"):
                pr = dict(nick=person(peer)["nick"], eta_min=None, lat=lat, lon=lon, home=True)
            else:
                pp = side_pos(d, peer)
                pr = dict(nick=person(peer)["nick"], eta_min=eta_min(pp, pl), lat=pp[0] if pp else None,
                          lon=pp[1] if pp else None, home=False)
            return dict(place=dict(name=name, lat=lat, lon=lon), my_eta_min=eta_min(x.pos, pl), peer=pr)
    return None


def item_queue_busy(seller, item_id, rid):
    return any(q["id"] != rid and q["seller"] == seller and str(q["item"]["id"]) == str(item_id)
               and q["state"] in ("delivered", "accepted", "queued") for q in reqs.values())


def next_in_queue(seller, item_id):
    """8절: 거절·무응답이면 같은 물건의 다음 요청을 보내요."""
    now = time.time()
    if any(q["seller"] == seller and str(q["item"]["id"]) == str(item_id) and q["state"] in ("delivered", "accepted")
           for q in reqs.values()):
        return
    qs = sorted([q for q in reqs.values() if q["seller"] == seller and str(q["item"]["id"]) == str(item_id)
                 and q["state"] == "queued"], key=lambda q: q["t_sent"])
    if not qs:
        return
    r = qs[0]
    o = owner(r["owner"]) if r.get("owner") else None
    s = find_sell(o, item_id) if o else None
    if s is None or s["status"] != "sale":
        return
    if seller.startswith("home:"):
        r["state"], r["t_sent"] = "delivered", now
    elif deliverable(seller, item_id, now):
        r["state"], r["t_sent"] = "delivered", now
        car(seller).push(dict(t="request", req=r["id"], sell_id=item_id, minutes=r["fake_min"], deals=car(r["buyer"]).deals,
                              photo=r["item"].get("photo") or "", buyer=person(r["buyer"])))
    log("QUEUE 다음 요청", short(seller), r["item"]["name"], r["state"])


def home_pick(home, binfo, cands):
    """집에서 150m~600m 사이 후보 중 사는 차가 덜 가는 곳. 후보가 없으면 적어 둔 목록 중 집에 가장 가까운 곳."""
    if home is None:
        return PLACES[1]
    cs = [c for c in (cands or []) if 0.15 <= km(home, (c[1], c[2])) <= 0.6]
    if not cs:
        return min(PLACES, key=lambda c: km(home, (c[1], c[2])))
    if binfo.get("pos") is None:
        return min(cs, key=lambda c: km(home, (c[1], c[2])))
    return min(cs, key=lambda c: extra(binfo, (c[1], c[2])))


home_cands = {}          # 반올림한 집 위치 -> (물은 때, 카카오 후보). 수락 때 잠금 안에서 바로 고르려고 미리 받아 둬요
HOME_CANDS_SEC = 3600    # 이만큼 지나면 다시 받아요 (임의)


def home_cands_fetch(home):
    """집 600m 안 편의점·주유소·주차장 (카카오). 잠금 밖에서만 불러요."""
    cands = []
    for cat in KAKAO_CATS:
        try:
            q = urllib.parse.urlencode(dict(category_group_code=cat, x="%.6f" % home[1], y="%.6f" % home[0],
                                            radius=600, size=KAKAO_SIZE, sort="distance"))
            req = urllib.request.Request("https://dapi.kakao.com/v2/local/search/category.json?" + q,
                                         headers={"Authorization": "KakaoAK " + KAKAO_KEY})
            with urllib.request.urlopen(req, timeout=KAKAO_TIMEOUT) as res:
                for doc in json.loads(res.read(1024 * 1024).decode("utf-8")).get("documents", []):
                    cands.append((str(doc["place_name"]), float(doc["y"]), float(doc["x"])))
        except Exception as e:
            log("KAKAO 집 근처 실패:", cat, type(e).__name__, getattr(e, "code", ""))
    with LOCK:
        if len(home_cands) >= 200:
            home_cands.clear()
        home_cands[rkey(home, home)] = (time.time(), cands)
    return cands


def home_cands_cached(home):
    v = home_cands.get(rkey(home, home)) if home else None
    return v[1] if v and time.time() - v[0] < HOME_CANDS_SEC else None


def home_cands_warm(seller):
    """집 물건에 요청이 오면(사람이 폰으로 답하기 전) 후보를 미리 받아 둬요."""
    if not KAKAO_KEY or not seller.startswith("home:"):
        return
    h = owner(seller[5:]).get("home")
    if h and home_cands_cached((h["lat"], h["lon"])) is None:
        threading.Thread(target=home_cands_fetch, args=((h["lat"], h["lon"]),), daemon=True).start()


def home_place_async(did, seller, buyer, decided):
    pick = None
    try:
        home = decided[seller]["pos"]
        cands = home_cands_fetch(home) if home else []
        pick = home_pick(home, decided[buyer], cands)
    except Exception as e:
        log("PLACE 집 오류", repr(e))
    finally:
        with LOCK:
            d = deals.get(did)
            if d is not None:
                d["computing"] = False
                if d["place"] is None:
                    apply_place(d, pick or PLACES[1])


def me_of(ok):
    o = owner(ok)
    a = accounts.get(ok)
    u = next((u for u in urgents.values() if u["owner"] == ok), None)
    return dict(sells=[dict(id=s["id"], name=s["name"], price=s["price"], desc=s.get("desc", ""), photo=live_photo(s.get("photo")),
                            loc=s["loc"], status=s["status"], hidden=s["hidden"]) for s in o["sells"]],
                wants=list(o["wants"]), wants_alert=o["wants_alert"], nick=o["nick"], deals=owner_deals(ok), rating=o["rating"],
                home=dict(gu=o["home"].get("gu", "")) if o.get("home") else None, linked=bool(a and a.get("car")),
                urgent=dict(id=u["id"], name=u["name"], desc=u["desc"], offers=[offer_out(f) for f in u["offers"]]) if u else None,
                restore_ok=not o["sells"] and not o["wants"] and not o["ever"], plate4=plate4_of(ok))


def offer_out(f):
    return dict(no=f["no"], name=f["name"], desc=f["desc"], price=f["price"], min=f["min"], seller=person(f["seller"]))


def row_out(o):
    r = dict(key=o["key"], item=o["item"], name=o["item"], price=o["price"], minutes=o["minutes"], min=o["minutes"],
             deals=o["deals"], photo=o["photo"], desc=o.get("desc", ""), loc=o.get("loc", "car"),
             seller=person(o["seller"]))
    if o.get("loc") == "home":
        r["gu"] = o.get("gu", "")
    return r


def matches(o_wants, row):
    text = norm(row["item"]) + " " + norm(row.get("desc", ""))
    return any(norm(w) and norm(w) in text for w in o_wants)


def save_owners():
    if not owners_dirty[0]:
        return
    owners_dirty[0] = False
    try:
        ensure_data()
        tmp = OWNERS_FILE + ".tmp"
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(dict(v=1, owners=owners), f, ensure_ascii=False)
        os.replace(tmp, OWNERS_FILE)
    except OSError as e:
        log("주인 목록 저장 실패", type(e).__name__)


def load_owners():
    try:
        with open(OWNERS_FILE, encoding="utf-8") as f:
            j = json.load(f)
    except FileNotFoundError:
        return
    except (OSError, ValueError) as e:
        log("주인 목록 읽기 실패", type(e).__name__)
        return
    for k, o in (j.get("owners") or {}).items():
        if isinstance(o, dict) and isinstance(o.get("sells"), list):
            base = owner(k)
            base.update({kk: o[kk] for kk in base if kk in o})
            for s in base["sells"]:
                if s.get("status") == "deal":              # 다시 켜면 거래는 사라져요. 물건은 다시 판매 중
                    s["status"] = "sale"
    owners_dirty[0] = False
    log("주인 목록 %d개를 읽었어요" % len(owners))


def clean_str(v, mx, field):
    need(isinstance(v, str) and len(v.strip()) <= mx, field + " 형식이 틀렸어요")
    return v.strip()


def edit_sell(o, c, src=None):
    """sell_add / update / remove / hide / status. 차 명령과 폰 주소가 같이 써요. (ok, why)"""
    t = c["t"]
    if t == "sell_add":
        name = clean_str(c.get("name"), MAX_NAME, "이름")
        price = c.get("price")
        if not name or not is_int(price, 0, MAX_PRICE):
            return False, "empty"
        desc = clean_str(c.get("desc", "") or "", MAX_DESC, "설명")
        loc = c.get("loc") or "car"
        need(loc in ("car", "home"), "loc 이 틀렸어요")
        photo = c.get("photo") or ""
        need(photo == "" or (isinstance(photo, str) and photo_get(photo) is not None), "사진이 없어요")
        if len(o["sells"]) >= MAX_ITEMS:
            return False, "full"
        s = new_sell(name, price, desc, loc, photo, src=src)
        o["sells"].insert(0, s)
        o["ever"] = True
        mark()
        return True, s
    s = find_sell(o, c.get("id"))
    if s is None:
        return False, "item"
    if t == "sell_update":
        if c.get("name") is not None:
            n = clean_str(c["name"], MAX_NAME, "이름")
            if n:
                s["name"] = n
        if c.get("price") is not None:
            need(is_int(c["price"], 0, MAX_PRICE), "가격이 틀렸어요")
            s["price"] = c["price"]
        if c.get("desc") is not None:
            s["desc"] = clean_str(c["desc"], MAX_DESC, "설명")
        if c.get("loc") is not None:
            need(c["loc"] in ("car", "home"), "loc 이 틀렸어요")
            s["loc"] = c["loc"]
    elif t == "sell_remove":
        o["sells"].remove(s)
    elif t == "sell_hide":
        s["hidden"] = c.get("hidden") is True
    elif t == "sell_status":
        need(c.get("status") in ("sale", "sold"), "status 가 틀렸어요")
        s["status"] = c["status"]
    mark()
    return True, s


def edit_want(o, t, name):
    name = clean_str(name, MAX_NAME, "이름")
    if not name:
        return False, "empty"
    have = [norm(w) for w in o["wants"]]
    if t == "want_add":
        if norm(name) in have:
            return False, "dup"
        if len(o["wants"]) >= MAX_ITEMS:
            return False, "full"
        o["wants"].insert(0, name)
        o["ever"] = True
    else:
        o["wants"] = [w for w in o["wants"] if norm(w) != norm(name)]
    mark()
    return True, None


def guess_gu(home):
    """카카오 좌표→행정구역으로 구 이름을 채워요. 잠금 밖(스레드)에서 불러요."""
    gu = region_of(home["lat"], home["lon"])
    with LOCK:
        if gu:
            home["gu"] = gu
            mark()


def region_of(lat, lon):
    if not KAKAO_KEY:
        return ""
    try:
        q = urllib.parse.urlencode(dict(x="%.6f" % lon, y="%.6f" % lat))
        req = urllib.request.Request("https://dapi.kakao.com/v2/local/geo/coord2regioncode.json?" + q,
                                     headers={"Authorization": "KakaoAK " + KAKAO_KEY})
        with urllib.request.urlopen(req, timeout=KAKAO_TIMEOUT) as res:
            docs = json.loads(res.read(256 * 1024).decode("utf-8")).get("documents", [])
        return gu_name(docs[0].get("region_2depth_name", "")) if docs else ""
    except Exception as e:
        log("KAKAO 구 이름 실패:", type(e).__name__, getattr(e, "code", ""))
        return ""


def gu_name(r2):
    """'성남시 분당구' -> '분당구'"""
    parts = str(r2).split()
    return parts[-1] if parts else ""


def new_cmd(x, c, now):
    """약속 2절의 새 명령."""
    t = c["t"]
    ok = owner_key(x.id)
    o = owner(ok)
    if o.get("legacy") == x.id:          # 새 명령을 보내면 새 앱이에요. 더는 state 목록을 받지 않아요
        o["legacy"] = None
    x.new_app = True
    if t.startswith("sell_"):
        edit_sell(o, c)
    elif t in ("want_add", "want_remove"):
        edit_want(o, t, c.get("name"))
    elif t == "wants_alert":
        o["wants_alert"] = c.get("on") is not False
        mark()
    elif t == "home_here":
        if x.pos is not None:
            o["home"] = dict(lat=x.pos[0], lon=x.pos[1], gu="집 근처")      # 구 이름은 카카오로 뒤에서 채워요
            mark()
            if KAKAO_KEY:
                threading.Thread(target=guess_gu, args=(o["home"],), daemon=True).start()
    elif t == "browse":
        x.browse = c.get("on") is True
    elif t == "restore":
        if o["sells"] or o["wants"] or o["ever"]:
            return
        for i in (c.get("sells") or [])[:MAX_ITEMS]:
            if isinstance(i, dict):
                try:
                    edit_sell(o, dict(t="sell_add", name=i.get("name"), price=i.get("price"), desc=i.get("desc", ""),
                                      loc=i.get("loc") or "car"))
                except Bad:
                    pass
        o["sells"].reverse()                       # 앱이 준 순서대로
        for w in reversed((c.get("wants") or [])[:MAX_ITEMS]):
            if isinstance(w, str):
                try:
                    edit_want(o, "want_add", w)
                except Bad:
                    pass
        o["ever"] = True
        mark()
    elif t == "urgent_post":
        if not x.trade_on:
            return
        name = clean_str(c.get("name"), MAX_NAME, "이름")
        if not name:
            return
        for u in [u for u in urgents.values() if u["owner"] == ok]:   # 한 주인에 하나
            end_urgent(u)
        uid = "u" + uuid.uuid4().hex[:10]
        urgents[uid] = dict(id=uid, owner=ok, car=x.id, name=name, desc=clean_str(c.get("desc", "") or "", MAX_DESC, "설명"),
                            nick=o["nick"], sent=set(), phones=set(), offers=[], t=now, pos=None)
        log("URGENT 올림", short(x.id), name)
    elif t == "urgent_cancel":
        for u in [u for u in urgents.values() if u["owner"] == ok]:
            end_urgent(u)
    elif t == "urgent_reply":
        u = urgents.get(c.get("id"))
        if u is not None and x.id in u["sent"]:
            urgent_offer(u, x.id, ok, "car", c, minutes(x.pos, urgent_pos(u)) if x.pos and urgent_pos(u) else 0)
    elif t == "urgent_pick":
        u = next((u for u in urgents.values() if u["owner"] == ok), None)
        if u is not None:
            urgent_pick(u, x, c.get("no"), now)
    elif t == "route_answer":
        log("ROUTE 답", short(x.id), c.get("id"), "수락" if c.get("accept") else "괜찮아요")
    elif t == "course_ask":
        course_for(x)


# ───── 급구 (약속 4절, 8절) ─────
def urgent_pos(u):
    if u["car"].startswith("fake"):
        return u["pos"]
    p = cars.get(u["car"])
    return p.pos if p is not None else None


def end_urgent(u, why=None):
    urgents.pop(u["id"], None)
    for cid in u["sent"]:
        if cid in cars:
            ev = dict(t="urgent_end", id=u["id"])
            if why:
                ev["why"] = why
            cars[cid].push(ev)
    log("URGENT 끝", u["name"], why or "")


def urgent_offer(u, seller_id, ok, loc, c, mins):
    """8절: 답한 자리에서 그 주인의 물건을 만들고, 올린 쪽에 urgent_offer 를 보내요."""
    o = owner(ok)
    good, s = edit_sell(o, dict(t="sell_add", name=c.get("name"), price=c.get("price"), desc=c.get("desc", "") or "", loc=loc))
    if not good:
        return False, s
    no = len(u["offers"]) + 1
    f = dict(no=no, name=s["name"], desc=s["desc"], price=s["price"], min=mins, seller=seller_id, owner=ok, item_id=s["id"])
    u["offers"].append(f)
    p = cars.get(u["car"])
    if p is not None:
        p.push(dict(t="urgent_offer", **offer_out(f)))
    log("URGENT 답", u["name"], "%d번" % no)
    return True, None


def urgent_pick(u, x, no, now):
    f = next((f for f in u["offers"] if f["no"] == no), None)
    if f is None:
        return
    o = owner(f["owner"])
    s = find_sell(o, f["item_id"])
    if s is None or s["status"] != "sale":
        return
    rid = uuid.uuid4().hex[:12]
    r = dict(id=rid, buyer=x.id, list=-1, no=no, seller=f["seller"], item=dict(id=s["id"], name=s["name"], price=s["price"], photo=""),
             state="accepted", t_sent=now, t_acc=now, reply_at=None, pick_at=None, script=None, fake_min=f["min"], owner=f["owner"])
    reqs[rid] = r
    make_deal(r, now)
    urgents.pop(u["id"], None)
    for g in u["offers"]:                      # 나머지 답한 사람: "다른 분과 거래가 정해졌어요."
        if g is not f and g["seller"] in cars:
            cars[g["seller"]].push(dict(t="urgent_end", id=u["id"], why="picked_other"))
    for cid in u["sent"]:
        if cid in cars and not any(g["seller"] == cid for g in u["offers"]):
            cars[cid].push(dict(t="urgent_end", id=u["id"]))


def tick_urgents(now):
    for u in list(urgents.values()):
        if u["car"].startswith("fake"):
            if now - u["t"] > FAKE_URGENT_SEC:
                end_urgent(u)
            continue
        p = cars.get(u["car"])
        if p is None or not p.trade_on or not p.online(now) or active_role(p.id):
            end_urgent(u)                          # 급구 끝: 알림 꺼짐, 10초 넘게 안 닿음, 거래가 정해짐
            continue
        if p.pos is None:
            continue
        for o in cars.values():
            if o.id == p.id or o.id in u["sent"] or not o.trade_on or not o.online(now) or o.pos is None:
                continue
            if owner_key(o.id) == u["owner"]:
                continue
            m = near_minutes(p.pos, o.pos)
            if m <= URGENT_MIN:
                u["sent"].add(o.id)
                o.push(dict(t="urgent", id=u["id"], name=u["name"], desc=u["desc"], min=m, nick=u["nick"]))
        for ok2, ow in owners.items():
            h = ow.get("home")
            if ok2 == u["owner"] or ok2 in u["phones"] or not h or not ok2 in accounts:
                continue
            if any(s["loc"] == "home" and s["status"] == "sale" for s in ow["sells"]) and \
                    near_minutes(p.pos, (h["lat"], h["lon"])) <= URGENT_MIN:
                u["phones"].add(ok2)


# ───── 경로 추천과 드라이브 코스 (약속 5절) ─────
def detour(x, via):
    if x.pos is None:
        return None
    if x.dest:
        return max(0, near_minutes(x.pos, via) + near_minutes(via, x.dest) - near_minutes(x.pos, x.dest))
    return 2 * near_minutes(x.pos, via)


def route_event(x, rt, side="buy"):
    """side: "buy" = 내 찾는 물건과 맞음(판매자가 많은 길), "sell" = 내 파는 물건과만 맞음(찾는 분이 많은 길)."""
    ex = detour(x, (rt["via"][1], rt["via"][2]))
    x.push(dict(t="route", id="r" + uuid.uuid4().hex[:10], label=rt["label"], extra_min=ex if ex is not None else 5,
                side=side, via=dict(name=rt["via"][0], lat=rt["via"][1], lon=rt["via"][2])))


def route_hit(rt, words):
    return any(any(norm(k) in w or (w and w in norm(k)) for k in rt["keys"]) for w in words if w)


def maybe_route(x):
    """목적지가 새로 생기거나 바뀌면, 내 찾는 물건·파는 물건과 맞는 시연용 길이 있을 때 한 번 보내요.
    요청을 주고받는 중이거나 거래 중이면 보내지 않아요(앱이 넣은 만날 곳은 운전자 목적지가 아니에요).
    맞는 길이 여럿이면 덜 돌아가는 길 하나만 보내요."""
    if not x.dest or x.dest == x.route_dest or not x.trade_on or x.pos is None:
        return
    ok = owner_key(x.id)
    if active_role(x.id) or active_role("home:" + ok):
        return                                   # route_dest 는 그대로: 거래가 끝난 뒤 다시 봐요
    x.route_dest = x.dest
    if any(km(x.dest, p) <= 0.1 for p in x.deal_places):
        return                                   # 끝난 거래의 만날 곳(100m 안)은 새 목적지가 아니에요
    o = owner(ok)
    buy = [norm(w) for w in o["wants"]]
    sell = [norm(s["name"]) for s in o["sells"] if s["status"] == "sale"]
    best = None
    for rt in DEMO_ROUTES:
        side = "buy" if route_hit(rt, buy) else "sell" if route_hit(rt, sell) else None
        if side is None:
            continue
        ex = detour(x, (rt["via"][1], rt["via"][2]))
        if ex is not None and ex <= ROUTE_EXTRA_MAX and (best is None or ex < best[0]):
            best = (ex, rt, side)
    if best:
        route_event(x, best[1], best[2])


def course_for(x):
    if x.pos is None:
        x.push(dict(t="course_none"))
        return
    cs = [c for c in DEMO_COURSES if km(x.pos, (c["via"][1], c["via"][2])) <= COURSE_MAX_KM]
    if not cs:
        x.push(dict(t="course_none"))
        return
    c = min(cs, key=lambda c: km(x.pos, (c["via"][1], c["via"][2])))
    x.push(dict(t="course", id="c" + uuid.uuid4().hex[:10], title=c["title"], extra_min=detour(x, (c["via"][1], c["via"][2])),
                via=dict(name=c["via"][0], lat=c["via"][1], lon=c["via"][2])))


def mirror_legacy(o, x, st):
    """옛 앱 호환: state.sells/wants 를 그 주인의 목록으로 받아요. 서버가 붙인 값(설명·상태·숨김)은 id 로 이어 둬요."""
    before = json.dumps([o["sells"], o["wants"]], ensure_ascii=False, default=str)
    byid = {str(s["id"]): s for s in o["sells"] if s.get("src") == "car"}
    new = []
    for i in st.get("sells", []):
        s = byid.get(str(i["id"]))
        if s is None:
            s = new_sell(i["name"], i["price"], "", "car", i.get("photo") or "", src="car", sid=i["id"])
        else:
            s["name"], s["price"] = i["name"], i["price"]
            if i.get("photo"):
                s["photo"] = i["photo"]
        new.append(s)
    rest = [s for s in o["sells"] if s.get("src") != "car" and not any(same_item(s, n) for n in new)]
    o["sells"] = new + rest
    wants = list(st.get("wants", []))
    wn = [norm(w) for w in wants]
    for e in phone_pending(x, "phone_add_want"):
        if norm(e["name"]) not in wn:
            wants.append(e["name"])
    o["wants"] = wants
    if o["sells"] or o["wants"]:
        o["ever"] = True
    if json.dumps([o["sells"], o["wants"]], ensure_ascii=False, default=str) != before:
        mark()


# ───── /sync ─────
class Bad(Exception):
    """이 요청만 400으로 거절해요."""


def need(ok, why):
    if not ok:
        raise Bad(why)


def is_num(v):
    return isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v)


def is_int(v, lo, hi):
    return isinstance(v, int) and not isinstance(v, bool) and lo <= v <= hi


def check_latlon(lat, lon):
    need(is_num(lat) and is_num(lon), "좌표가 숫자가 아니에요")
    need(-90 <= lat <= 90 and -180 <= lon <= 180, "좌표 범위를 벗어났어요")


def check_id(v):
    need((isinstance(v, str) and 0 < len(v) <= MAX_ID) or is_int(v, -2**53, 2**53), "id 형식이 틀렸어요")


def check_sync(body):
    """2, 3, 5: 저장하기 전에 형식과 상한을 봐요. 틀리면 Bad."""
    need(isinstance(body, dict), "본문이 객체가 아니에요")
    cid = body.get("car")
    need(isinstance(cid, str) and 0 < len(cid) <= MAX_ID, "차 식별자가 틀렸어요")
    need(not cid.startswith("fake"), "쓸 수 없는 차 식별자예요")
    s = body.get("session")
    need(s is None or (isinstance(s, str) and len(s) <= MAX_ID) or is_int(s, -2**63, 2**63), "session 형식이 틀렸어요")
    need(is_int(body.get("ack", 0), 0, 2**53), "ack 형식이 틀렸어요")
    st = body.get("state", {})
    need(isinstance(st, dict), "state 형식이 틀렸어요")
    sells = st.get("sells", [])
    need(isinstance(sells, list) and len(sells) <= MAX_ITEMS, "물건 목록이 틀렸거나 너무 많아요")
    for i in sells:
        need(isinstance(i, dict), "물건 형식이 틀렸어요")
        check_id(i.get("id"))
        need(isinstance(i.get("name"), str) and len(i["name"]) <= MAX_NAME, "물건 이름이 틀렸거나 너무 길어요")
        need(is_int(i.get("price"), 0, MAX_PRICE), "가격이 틀렸어요")
        ph = i.get("photo")
        need(ph is None or (isinstance(ph, str) and len(ph) <= MAX_ID), "사진 id가 틀렸어요")
    wants = st.get("wants", [])
    need(isinstance(wants, list) and len(wants) <= MAX_ITEMS, "찾는 물건 목록이 틀렸거나 너무 많아요")
    for w in wants:
        need(isinstance(w, str) and len(w) <= MAX_NAME, "찾는 물건 이름이 틀렸거나 너무 길어요")
    p = st.get("pos")
    if p is not None:
        need(isinstance(p, list) and len(p) == 2, "pos 형식이 틀렸어요")
        check_latlon(p[0], p[1])
    dst = st.get("dest")
    if dst is not None:
        need(isinstance(dst, list) and len(dst) == 2, "dest 형식이 틀렸어요")
        check_latlon(dst[0], dst[1])
    ci = st.get("car_info", "")
    need(ci is None or (isinstance(ci, str) and len(ci) <= MAX_CARINFO), "차 설명이 틀렸거나 너무 길어요")
    need(is_int(st.get("deals", 0), 0, MAX_DEALS), "거래 횟수가 틀렸어요")
    cmds = body.get("cmds", [])
    need(isinstance(cmds, list) and len(cmds) <= MAX_CMDS, "명령 목록이 틀렸거나 너무 많아요")
    for c in cmds:
        need(isinstance(c, dict) and is_int(c.get("cid", 0), 0, 2**53), "명령 형식이 틀렸어요")
        if c.get("t") == "dest" and c.get("lat") is not None:
            check_latlon(c.get("lat"), c.get("lon"))


def sync(body):
    now = time.time()
    check_sync(body)
    x = car(body["car"])
    if body.get("session") != x.session:
        if x.session is not None:
            log("RESTART", short(x.id))
        x.session = body.get("session")
        x.last_cid = 0
        end_requests_of(x.id)                       # C-15: 주고받던 요청은 사라져요
        x.events = [e for e in x.events if e["t"] not in REQ_EVENTS]
        x.fake_near = False
        x.fake_gone.clear()
    x.last_seen = now
    ack = int(body.get("ack", 0))
    x.events = [e for e in x.events if e["seq"] > ack]
    st = body.get("state", {})
    was_on = x.trade_on                             # 12: 상태를 먼저 고치고 명령은 나중에
    x.trade_on = bool(st.get("trade_on"))
    x.busy = bool(st.get("busy"))
    x.wants = st.get("wants", [])
    p = st.get("pos")                               # 형식은 check_sync 에서 봤어요
    x.pos = (float(p[0]), float(p[1])) if p else None
    dd = st.get("dest")
    x.dest = (float(dd[0]), float(dd[1])) if dd else None
    x.driving = bool(st.get("driving"))
    x.legacy_app = "driving" not in st and not x.new_app
    ok = owner_key(x.id)
    o = owner(ok)
    if x.legacy_app and ("sells" in st or "wants" in st):     # 옛 앱 호환: 서버 목록이 비어 있는 동안 받아들여요
        if o.get("legacy") == x.id or (not o["sells"] and not o["wants"] and (st.get("sells") or st.get("wants"))):
            o["legacy"] = x.id
            mirror_legacy(o, x, st)
    x.car_info = st.get("car_info", "") or ""
    x.deals = int(st.get("deals", 0))
    if was_on and not x.trade_on:                   # D-03: 끄면 가짜 차는 다시 없어져요
        end_requests_of(x.id)
        x.fake_near = False
        x.fake_gone.clear()
    for c in sorted(body.get("cmds", []), key=lambda c: c.get("cid", 0)):   # 7: 하나씩, 성공한 뒤에 last_cid
        cid = c.get("cid", 0)
        if cid <= x.last_cid:
            continue
        try:
            handle_cmd(x, c, now)
        except Exception as e:
            x.fail_n = x.fail_n + 1 if x.fail_cid == cid else 1
            x.fail_cid = cid
            log("명령 오류", short(x.id), cid, c.get("t"), repr(e), "%d번째" % x.fail_n)
            if x.fail_n < CMD_RETRY:
                break                               # 다음 /sync 에 다시 해요 (순서를 지키려고 뒤 명령도 미뤄요)
        x.last_cid = cid
    o = owner(owner_key(x.id))
    x.sells = car_items(o)
    maybe_route(x)
    near = near_for(x, now, with_busy=True)
    rows = {}
    peer_deciding = False
    for L in lists.values():
        if L["buyer"] == x.id:
            for no, row in L["rows"].items():
                if row["key"] in near:
                    rows[str(no)] = near[row["key"]]["minutes"]
    for d in deals.values():
        if x.id in (d["seller"], d["buyer"]) and x.id not in d["ended"] and x.id in d["decided"] and d["place"] is None:
            peer = d["buyer"] if x.id == d["seller"] else d["seller"]
            if peer not in d["decided"]:            # M-21: 상대가 아직 정하지 않았을 때만
                peer_deciding = True
    free = sorted([r for r in near.values() if not r["busy"]], key=lambda r: r["minutes"])    # B-20: 다른 거래 중인 차는 빼요
    out = dict(boot=BOOT, events=x.events, rows=rows, peer_deciding=peer_deciding, pair=pair_code(x.id),
               near=[row_out(r) for r in free if o["wants_alert"] and matches(o["wants"], r)],
               me=me_of(owner_key(x.id)))
    if x.browse:
        out["browse"] = [row_out(r) for r in free[:30]]
    mt = meet_of(x)
    if mt is not None:
        out["meet"] = mt
    return out


# ───── 데모 명령 (demo.sh) ─────
def demo(cmd, q):
    now = time.time()
    cid = q.get("car", "")
    x = cars.get(cid)
    if x is None:
        return False, "그 차를 몰라요 (앱이 아직 서버에 닿지 않았어요)"
    if cmd == "near":                                       # D-03
        if not x.trade_on:
            return False, "거래 알림이 꺼져 있어요"
        x.fake_near = True
        x.fake_gone.clear()
        return True, "가짜 파는 차 %d대가 주변에 나타났어요" % len(FAKE_SELLERS)
    if cmd == "seed":                                       # 시연용: 이 차의 「파는 물건」 채우기 (사진 있는 것·없는 것 섞어서)
        o = owner(owner_key(x.id))
        have = {norm(s["name"]) for s in o["sells"]}
        added = 0
        for name, price, pic in DEMO_SEED_SELLS:
            if norm(name) in have:                          # 같은 이름이 이미 있으면 또 넣지 않아요
                continue
            good, res = edit_sell(o, dict(t="sell_add", name=name, price=price, loc="car", photo=seed_photo(pic)))
            if not good:
                break                                       # full: 물건 수 상한
            have.add(norm(name))
            added += 1
            if o.get("legacy") == x.id:                     # 옛 차 앱은 폰에서 넣은 것처럼 일로도 보내요
                phone_event(x, "phone_add_sell", name=res["name"], price=res["price"], photo=res["photo"])
        x.sells = car_items(o)
        return True, "파는 물건 %d개를 넣었어요" % added
    if cmd in ("request", "request-other", "request-silent"):   # D-04~D-06
        x.sells = car_items(owner(owner_key(x.id)))
        if not x.trade_on or not x.sells:
            return False, "거래 알림이 꺼져 있거나 파는 물건이 없어요"
        if not deliverable(x.id, x.sells[0]["id"], now):
            return False, "진행 중이라 요청이 가지 않아요 (S-30)"
        s = x.sells[0]
        rid = uuid.uuid4().hex[:12]
        script = {"request": "me", "request-other": "other", "request-silent": "silent"}[cmd]
        reqs[rid] = dict(id=rid, buyer="fake:buyer", list=0, no=0, seller=x.id,
                         item=dict(id=s["id"], name=s["name"], price=s["price"], photo=s.get("photo") or ""),
                         state="delivered", t_sent=now, t_acc=None, reply_at=None, pick_at=None, script=script, fake_min=FAKE_BUYER_MIN,
                         owner=owner_key(x.id))
        x.push(dict(t="request", req=rid, sell_id=s["id"], minutes=FAKE_BUYER_MIN, deals=FAKE_BUYER_DEALS,
                    photo=s.get("photo") or "", buyer=person("fake:buyer")))
        return True, "가짜 사는 차가 '%s'에 요청을 보냈어요" % s["name"]
    if cmd == "withdraw":                                   # D-07
        for r in list(reqs.values()):
            if r["seller"] == x.id and r["buyer"] == "fake:buyer" and r["state"] in ("delivered", "accepted"):
                end_req(r, None, "withdrew")
                return True, "가짜 사는 차가 요청을 거뒀어요"
        return False, "받은 요청이 없어요"
    if cmd in ("gone", "seller-cancel"):                    # D-08, D-09
        no = int(q.get("no", "0"))
        L = next((L for L in lists.values() if L["buyer"] == x.id), None)
        row = L["rows"].get(no) if L else None
        if row is None:
            return False, "그 번호가 목록에 없어요"
        n = fake_seller_no(row["key"])
        if n is None:
            return False, "그 번호는 가짜 차가 아니에요"
        if cmd == "gone":
            x.fake_gone.add(n)          # 다음 순간(0.2초 안) 타이머가 B-53으로 처리해요
            return True, "%d번 차가 주변에서 사라졌어요" % no
        r = reqs.get(row["req"])
        if r is None or r["state"] != "accepted":
            return False, "%d번이 수락한 상태가 아니에요" % no
        end_req(r, "seller_cancel", None)
        return True, "%d번이 수락을 취소했어요" % no
    if cmd == "peer-cancel":                                # D-10
        for d in deals.values():
            if x.id in (d["seller"], d["buyer"]) and x.id not in d["ended"]:
                peer = d["buyer"] if x.id == d["seller"] else d["seller"]
                if not is_fake(peer):
                    return False, "상대가 가짜 차가 아니에요"
                d["ended"].add(x.id)
                x.push(dict(t="peer_cancel", deal=d["id"]))
                return True, "가짜 상대가 거래를 취소했어요"
        return False, "정해진 거래가 없어요"
    if cmd == "route":                                      # 약속 5절: 조건과 상관없이 route 일
        route_event(x, DEMO_ROUTES[0])
        return True, "경로 추천을 보냈어요"
    if cmd == "urgent":                                     # 약속 5절: 가짜 차가 근처에서 급구
        uid = "u" + uuid.uuid4().hex[:10]
        pos = offset_north(x.pos, 3) if x.pos else None
        urgents[uid] = dict(id=uid, owner="fake:urgent", car="fake:urgent", name="유아용 카시트", desc="3~7세용이면 좋아요",
                            nick="이용자 5150", sent={x.id}, phones=set(), offers=[], t=now, pos=pos)
        x.push(dict(t="urgent", id=uid, name="유아용 카시트", desc="3~7세용이면 좋아요", min=3, nick="이용자 5150"))
        return True, "가짜 차가 근처에서 급구를 올렸어요"
    return False, "모르는 명령이에요"


def demo_allowed(headers):
    """4: Railway 에서 열쇠가 없으면 데모 명령을 막아요. 맥 개발에서는 열쇠가 없으면 열어 둬요."""
    if not DEMO_TOKEN:
        return not ON_RAILWAY
    got = headers.get("X-Demo-Token", "") or ""
    return hmac.compare_digest(got.encode("utf-8"), DEMO_TOKEN.encode("utf-8"))


# ───── 차 화면 만날 곳 지도 (카카오 지도 JavaScript SDK) ─────
CARMAP_HTML = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no">
<title>loading</title>
<style>html,body,#map{margin:0;padding:0;width:100%;height:100%;overflow:hidden;background:#e9e9e9}
.tag{background:#fff;color:#000;font:bold 20px/1 sans-serif;padding:8px 14px;border-radius:999px;border:2px solid #000;white-space:nowrap;box-shadow:0 2px 6px rgba(0,0,0,.3)}
.dot{width:16px;height:16px;border-radius:50%;background:#000;border:4px solid #fff;box-shadow:0 1px 4px rgba(0,0,0,.4)}</style>
</head><body><div id="map"></div><script>
var KEY = __KEY__, map = null, done = false, tags = [], dots = [], want = null;
function fail(){ if(!done){ done = true; document.title = "fail"; } }
function ready(){ if(!done){ done = true; document.title = "ready"; } }
setTimeout(fail, 4000);
function num(v){ return (v === null || v === undefined || v === "" || isNaN(+v)) ? null : +v; }
function pair(s){ if(!s) return [null, null]; var a = s.split(","); return [num(a[0]), num(a[1])]; }
function far(a, b){ var r = Math.PI/180, x = (b[1]-a[1])*r*Math.cos((a[0]+b[0])/2*r), y = (b[0]-a[0])*r; return Math.sqrt(x*x+y*y)*6371000; }
function draw(){
  if(!map || !want) return;
  var names = ["만날 곳", "나", "상대"], ax = [[0.5, 1.6], [0.5, -0.6], [-0.2, 0.5]], pts = [];
  for(var i = 0; i < 3; i++){
    var la = want[i*2], lo = want[i*2+1];
    if(tags[i]){ tags[i].setMap(null); dots[i].setMap(null); tags[i] = dots[i] = null; }
    if(la === null || lo === null) continue;
    var pos = new kakao.maps.LatLng(la, lo), t = document.createElement("div"), d = document.createElement("div");
    t.className = "tag"; t.textContent = names[i]; d.className = "dot";
    dots[i] = new kakao.maps.CustomOverlay({position: pos, content: d, xAnchor: 0.5, yAnchor: 0.5, zIndex: 1});
    tags[i] = new kakao.maps.CustomOverlay({position: pos, content: t, xAnchor: ax[i][0], yAnchor: ax[i][1], zIndex: 2});
    dots[i].setMap(map); tags[i].setMap(map); pts.push([la, lo]);
  }
  if(!pts.length) return;
  var m = 0, b = new kakao.maps.LatLngBounds(), cy = 0, cx = 0;
  for(var j = 0; j < pts.length; j++){ b.extend(new kakao.maps.LatLng(pts[j][0], pts[j][1])); cy += pts[j][0]; cx += pts[j][1];
    for(var k = 0; k < pts.length; k++) m = Math.max(m, far(pts[j], pts[k])); }
  if(m < 50){ map.setLevel(3); map.setCenter(new kakao.maps.LatLng(cy/pts.length, cx/pts.length)); }
  else map.setBounds(b, 60, 60, 60, 60);
}
window.upd = function(pa, po, ma, mo, oa, oo){ want = [num(pa), num(po), num(ma), num(mo), num(oa), num(oo)]; draw(); };
(function(){
  var q = new URLSearchParams(location.search), p = pair(q.get("p")), m = pair(q.get("m")), o = pair(q.get("o"));
  want = [p[0], p[1], m[0], m[1], o[0], o[1]];
  if(!KEY){ fail(); return; }
  var s = document.createElement("script");
  s.src = "https://dapi.kakao.com/v2/maps/sdk.js?appkey=" + encodeURIComponent(KEY) + "&autoload=false";
  s.onerror = fail;
  s.onload = function(){
    if(!window.kakao || !kakao.maps || !kakao.maps.load){ fail(); return; }
    kakao.maps.load(function(){
      try {
        var c = (want[0] !== null) ? new kakao.maps.LatLng(want[0], want[1]) : new kakao.maps.LatLng(37.5665, 126.978);
        map = new kakao.maps.Map(document.getElementById("map"), {center: c, level: 4, draggable: false, scrollwheel: false, disableDoubleClickZoom: true});
        map.setDraggable(false); map.setZoomable(false);
        kakao.maps.event.addListener(map, "tilesloaded", ready);
        draw();
        window.addEventListener("resize", function(){ if(map){ map.relayout(); draw(); } });   // WebView 크기가 나중에 정해져도 세 점이 다 보이게
      } catch(e){ fail(); }
    });
  };
  document.head.appendChild(s);
})();
</script></body></html>"""


def carmap_html():
    """키는 공개용 JavaScript 키예요. 없으면 쪽이 바로 fail 이 돼요."""
    k = json.dumps(KAKAO_JS or "").replace("<", "\\u003c")
    return CARMAP_HTML.replace("__KEY__", k).encode("utf-8")


# ───── 폰 (P-02~P-06) ─────
def phone_event(x, kind, **kw):
    """C-24: 차의 events 로 보내요. 앱은 t 를 읽어요. 약속의 type 도 같은 값으로 실어요."""
    x.push(dict(t=kind, type=kind, **kw))


def test_allowed():
    """시험용 로그인: ALLOW_TEST_LOGIN=1 일 때만. 값이 없으면 맥(Railway 밖)에서만 켜요."""
    v = os.environ.get("ALLOW_TEST_LOGIN")
    if v is None:
        return not ON_RAILWAY
    return v.strip() == "1"


def pair_check(ip, now):
    """연결 번호 제한 (지난번 그대로). 막혀야 하면 True."""
    pair_fails_all[:] = [t for t in pair_fails_all if now - t < PAIR_FAIL_SEC]
    if len(pair_fails_all) >= PAIR_FAILS_ALL:               # 서버 전체로 너무 많이 틀렸으면 모두 잠깐 막아요
        return True
    fl = [t for t in pair_fails.pop(ip, []) if now - t < PAIR_FAIL_SEC]
    if fl:
        pair_fails[ip] = fl                                 # 맨 뒤로 옮겨 최근 것으로 둬요
    return len(fl) >= PAIR_FAILS


def pair_fail(ip, now):
    fl = pair_fails.get(ip, [])
    fl.append(now)
    pair_fails[ip] = fl
    pair_fails_all.append(now)
    if len(pair_fails) > MAX_PAIRS:                         # 기한 지난 것부터, 그래도 많으면 가장 오래된 곳부터 지워요
        for k in [k for k, v in pair_fails.items() if now - v[-1] >= PAIR_FAIL_SEC]:
            pair_fails.pop(k, None)
        while len(pair_fails) > MAX_PAIRS:
            pair_fails.pop(next(iter(pair_fails)))


def phone_api(path, body, token, ip):
    """(상태 코드, 답). LOCK 안에서 불러요."""
    now = time.time()
    if path == "/phone/login/test":                             # 시험용 로그인: 이름별로 같은 계정
        if not test_allowed():
            return 403, dict(ok=False, why="off")
        name = body.get("name") if isinstance(body, dict) else None
        if not isinstance(name, str):
            return 400, dict(ok=False, why="name")
        name = " ".join(name.split())
        if not 0 < len(name) <= TEST_NAME_MAX or not all(ch.isalnum() or ch == " " for ch in name):
            return 400, dict(ok=False, why="name")
        key = account_key("t", "test:" + name)
        if not make_account(key, "test"):
            return 503, dict(ok=False, why="full")
        t = new_token(key)
        save_accounts()
        log("PHONE 시험 로그인", key[:7])
        return 200, dict(ok=True, token=t)
    key, acc = account_of(token)
    if acc is None:
        return 401, dict(ok=False, why="token")
    if path == "/phone/state":
        return 200, phone_view(key)
    if path == "/phone/logout":
        phone_tokens.pop(tok_hash(token), None)
        save_accounts()
        return 200, dict(ok=True)
    if path == "/phone/unpair":
        if acc["car"]:
            if car_owner.get(acc["car"]) == key:
                car_owner.pop(acc["car"], None)
            acc["car"] = None
            save_accounts()
            log("PHONE 차 풀림", key[:7])
        return 200, dict(ok=True)
    need(isinstance(body, dict), "본문이 객체가 아니에요")
    if path == "/phone/pair":                                   # P-02: 로그인한 뒤 차를 이어요
        if pair_check(ip, now):
            return 429, dict(ok=False, why="wait")
        plate = body.get("plate")
        if plate not in (None, "") and not plate_ok(plate):
            return 400, dict(ok=False, why="plate")
        code = body.get("code")
        cid = pair_cars.get(code) if isinstance(code, str) else None
        if cid is None:
            pair_fail(ip, now)
            return 403, dict(ok=False, why="code")
        n = pair_account(key, cid)
        if plate:
            acc["plate"] = plate.strip()
        save_accounts()
        log("PHONE 차 이음", key[:7], short(cid), "보낸 일 %d" % n)
        return 200, dict(ok=True)
    if path == "/phone/plate":                                  # 10절: 번호판 넣기·고치기
        if not plate_ok(body.get("plate")):
            return 400, dict(ok=False, why="plate")
        acc["plate"] = body["plate"].strip()
        save_accounts()
        log("PHONE 번호판", key[:7])
        return 200, dict(ok=True, plate4=plate4_of(key))
    x = linked_car(acc)
    if x is not None and len([e for e in x.events if str(e.get("t", "")).startswith("phone_")]) >= MAX_PHONE_PENDING:
        return 429, dict(ok=False, why="pending")
    o = owner(key)
    legacy = x is not None and o.get("legacy") == x.id          # 옛 차 앱: 예전처럼 차에 일로도 보내요
    if path in ("/phone/sell", "/phone/sell/update", "/phone/sell/remove", "/phone/sell/hide", "/phone/sell/status"):
        t = "sell_add" if path == "/phone/sell" else "sell_" + path.rsplit("/", 1)[1]
        c = dict(body, t=t)
        if t == "sell_add" and (not isinstance(body.get("name"), str) or not body["name"].strip()
                                or not is_int(body.get("price"), 1, MAX_PRICE)):
            return 400, dict(ok=False, why="empty")
        good, res = edit_sell(o, c, src="phone" if legacy else None)
        if not good:
            return (409 if res == "full" else 404 if res == "item" else 400), dict(ok=False, why=res)
        if t == "sell_add" and legacy and res["loc"] == "car":
            phone_event(x, "phone_add_sell", name=res["name"], price=res["price"], photo=res["photo"])
        if x is not None:
            x.sells = car_items(o)
        log("PHONE 물건", key[:7], t)
        return 200, dict(ok=True, id=res["id"])
    if path in ("/phone/want", "/phone/want/remove"):           # P-06
        name = body.get("name")
        if not isinstance(name, str) or not name.strip():
            return 400, dict(ok=False, why="empty")
        need(len(name.strip()) <= MAX_NAME, "이름이 너무 길어요")
        good, why = edit_want(o, "want_add" if path == "/phone/want" else "want_remove", name)
        if not good:
            return 409, dict(ok=False, why=why)
        if legacy and path == "/phone/want":
            phone_event(x, "phone_add_want", name=name.strip())
        return 200, dict(ok=True)
    if path == "/phone/wants_alert":
        o["wants_alert"] = body.get("on") is not False
        mark()
        return 200, dict(ok=True)
    if path == "/phone/nick":
        n = body.get("nick")
        if not isinstance(n, str) or not 0 < len(n.strip()) <= MAX_NICK:
            return 400, dict(ok=False, why="nick")
        o["nick"] = " ".join(n.split())
        mark()
        return 200, dict(ok=True)
    hid = "home:" + key
    if path == "/phone/request/answer":
        r = reqs.get(body.get("id"))
        if r is None or r["seller"] != hid or r["state"] != "delivered":
            return 404, dict(ok=False, why="req")
        handle_cmd(FakeCar(hid), dict(t="accept" if body.get("accept") is True else "reject", req=r["id"]), now)
        return 200, dict(ok=True)
    if path == "/phone/deal/cancel":
        d = next((d for d in deals.values() if d["seller"] == hid and hid not in d["ended"]), None)
        if d is None:
            return 404, dict(ok=False, why="deal")
        handle_cmd(FakeCar(hid), dict(t="cancel_deal", deal=d["id"]), now)
        return 200, dict(ok=True)
    if path == "/phone/deal/review":                 # 9절: 이 사람 쪽에서 거래를 끝내요 (차의 deal_end와 같은 효과)
        side = hid
        d = next((d for d in deals.values() if d["seller"] == hid and hid not in d["ended"]), None)
        lc = accounts[key].get("car")
        if d is None and lc:
            side = lc
            d = next((d for d in deals.values() if lc in (d["seller"], d["buyer"]) and lc not in d["ended"]), None)
        if d is None:
            return 404, dict(ok=False, why="deal")
        st = body.get("stars")
        if st is not None and not is_int(st, 0, 5):
            return 400, dict(ok=False, why="stars")
        end_side(d, side, body.get("done") is True, st)
        return 200, dict(ok=True)
    if path == "/phone/urgent/reply":
        u = urgents.get(body.get("id"))
        if u is None or key not in u["phones"]:
            return 404, dict(ok=False, why="urgent")
        h = o.get("home")
        up = urgent_pos(u)
        good, why = urgent_offer(u, hid, key, "home", body, minutes((h["lat"], h["lon"]), up) if h and up else 0)
        if not good:
            return 400, dict(ok=False, why=why)
        return 200, dict(ok=True)
    return 404, dict(ok=False)


def phone_home(body, token):
    """POST /phone/home {query}: 카카오 주소 검색으로 좌표와 구 이름만 저장해요. 네트워크는 잠금 밖."""
    with LOCK:
        key, acc = account_of(token)
    if acc is None:
        return 401, dict(ok=False, why="token")
    q = body.get("query") if isinstance(body, dict) else None
    if not isinstance(q, str) or not 0 < len(q.strip()) <= 100 or not KAKAO_KEY:
        return 200, dict(ok=False)
    try:
        u = "https://dapi.kakao.com/v2/local/search/address.json?" + urllib.parse.urlencode(dict(query=q.strip()))
        req = urllib.request.Request(u, headers={"Authorization": "KakaoAK " + KAKAO_KEY})
        with urllib.request.urlopen(req, timeout=KAKAO_TIMEOUT) as res:
            docs = json.loads(res.read(256 * 1024).decode("utf-8")).get("documents", [])
        d = docs[0] if docs else None
        if d is None:
            return 200, dict(ok=False)
        lat, lon = float(d["y"]), float(d["x"])
        ad = d.get("address") or d.get("road_address") or {}
        gu = gu_name(ad.get("region_2depth_name", "")) or region_of(lat, lon)
    except Exception as e:
        log("KAKAO 주소 실패:", type(e).__name__, getattr(e, "code", ""))
        return 200, dict(ok=False)
    if not (math.isfinite(lat) and math.isfinite(lon)):
        return 200, dict(ok=False)
    with LOCK:
        owner(key)["home"] = dict(lat=lat, lon=lon, gu=gu)          # 주소 글자는 저장하지 않아요
        mark()
    log("PHONE 집 위치", key[:7])
    return 200, dict(ok=True, gu=gu)


def phone_photo(raw, item, token):
    """P-03~P-05: JPEG 를 맡아 둬요. item(차에 들어간 물건 id)이 있으면 그 물건에 붙이라고 차에 보내요 (phone_set_photo)."""
    with LOCK:
        key, acc = account_of(token)
        if acc is None:
            return 401, dict(ok=False, why="token")
        target = None
        if item:
            target = find_sell(owner(key), item)
            if target is None:
                return 404, dict(ok=False, why="item")
            target = dict(id=target["id"], name=target["name"], price=target["price"])
    if len(raw) < 4 or raw[:3] != b"\xff\xd8\xff":
        return 400, dict(ok=False, why="jpeg")
    pid = photo_put(raw, "image/jpeg")
    if target is not None:
        with LOCK:
            x = linked_car(acc)
            if x is not None and owner(key).get("legacy") == x.id:     # 옛 앱이면 차에도 알려요
                phone_event(x, "phone_set_photo", id=target["id"], photo=pid)
            s = find_sell(owner(key), target["id"])
            if s is not None:
                s["photo"] = pid
                mark()
    log("PHONE 사진 %dKB" % (len(raw) // 1024), key[:7])
    return 200, dict(ok=True, photo=pid)


# ───── 카카오 로그인 (REST, 인가 코드 방식) ─────
def redirect_uri():
    pub = os.environ.get("PUBLIC_URL", "").strip().rstrip("/")
    if pub:
        return pub + "/phone/kakao"
    return "http://localhost:%d/phone/kakao" % int(os.environ.get("PORT", "8099"))


def kakao_start():
    """카카오 로그인 쪽 주소. scope 는 보내지 않아요(닉네임·이메일·전화번호를 묻지 않아요)."""
    if not KAKAO_KEY:
        return "/phone#e=1"
    now = time.time()
    with LOCK:
        for s in [s for s, t in kakao_states.items() if t < now]:
            kakao_states.pop(s, None)
        while len(kakao_states) >= MAX_STATES:
            kakao_states.pop(next(iter(kakao_states)))
        st = secrets.token_urlsafe(24)
        kakao_states[st] = now + STATE_SEC
    q = urllib.parse.urlencode(dict(client_id=KAKAO_KEY, redirect_uri=redirect_uri(), response_type="code", state=st))
    return "https://kauth.kakao.com/oauth/authorize?" + q


def kakao_user_id(code):
    """인가 코드 -> 카카오 id 글자 또는 None. 카카오가 준 토큰은 이 함수 안에서만 쓰고 버려요. 잠금 밖에서 불러요."""
    try:
        f = dict(grant_type="authorization_code", client_id=KAKAO_KEY, redirect_uri=redirect_uri(), code=code)
        if KAKAO_SECRET:
            f["client_secret"] = KAKAO_SECRET
        req = urllib.request.Request("https://kauth.kakao.com/oauth/token", data=urllib.parse.urlencode(f).encode("utf-8"),
                                     headers={"Content-Type": "application/x-www-form-urlencoded;charset=utf-8"})
        with urllib.request.urlopen(req, timeout=KAKAO_LOGIN_TIMEOUT) as res:
            at = json.loads(res.read(64 * 1024).decode("utf-8")).get("access_token")
        if not isinstance(at, str) or not at:
            return None
        req = urllib.request.Request("https://kapi.kakao.com/v2/user/me", headers={"Authorization": "Bearer " + at})
        at = None
        with urllib.request.urlopen(req, timeout=KAKAO_LOGIN_TIMEOUT) as res:
            kid = json.loads(res.read(256 * 1024).decode("utf-8")).get("id")     # id 만 써요
        req = None
        return str(kid) if is_int(kid, 1, 2**63) else None
    except Exception as e:             # 코드·토큰·키는 오류 글에 싣지 않아요
        log("KAKAO 로그인 실패:", type(e).__name__, getattr(e, "code", ""))
        return None


def kakao_back(q):
    """돌아온 곳. 성공하면 /phone#t=<폰 토큰>, 실패하면 /phone#e=1"""
    st, code = q.get("state", ""), q.get("code", "")
    with LOCK:
        exp = kakao_states.pop(st, None)           # 한 번만 쓰여요
    if exp is None or exp < time.time() or not code or q.get("error") or not KAKAO_KEY or len(code) > 1024:
        return "/phone#e=1"
    kid = kakao_user_id(code)
    if kid is None:
        return "/phone#e=1"
    key = account_key("k", kid)
    kid = None
    with LOCK:
        if not make_account(key, "kakao"):
            return "/phone#e=1"
        t = new_token(key)
        save_accounts()
    log("PHONE 카카오 로그인", key[:7])
    return "/phone#t=" + t


# ───── 링크로 채우기 ─────
class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None                     # 직접 따라가며 호스트를 다시 봐요


LINK_OPENER = urllib.request.build_opener(_NoRedirect)


def link_ok(url, hosts):
    try:
        p = urllib.parse.urlsplit(url)
        return (p.scheme == "https" and (p.hostname or "") in hosts and p.username is None and p.password is None
                and p.port in (None, 443))
    except ValueError:
        return False


def link_fetch(url, hosts, cap):
    """(최종 주소, 형식, 바이트, 넘쳤나). 리다이렉트는 같은 허용 호스트 안에서 3번까지, 전체 5초."""
    end = time.time() + LINK_TIMEOUT
    for _ in range(LINK_REDIRECTS + 1):
        if not link_ok(url, hosts):
            raise ValueError("host")
        left = end - time.time()
        if left <= 0:
            raise TimeoutError()
        req = urllib.request.Request(url, headers={"User-Agent": LINK_UA, "Accept-Language": "ko-KR,ko;q=0.9"})
        try:
            with LINK_OPENER.open(req, timeout=left) as res:
                buf = bytearray()
                while len(buf) <= cap:
                    if time.time() > end:
                        raise TimeoutError()
                    b = res.read(min(65536, cap + 1 - len(buf)))
                    if not b:
                        break
                    buf += b
                over = len(buf) > cap
                return url, res.headers.get("Content-Type", ""), bytes(buf[:cap]), over
        except urllib.error.HTTPError as e:
            loc = e.headers.get("Location") if e.code in (301, 302, 303, 307, 308) else None
            e.close()
            if not loc:
                raise
            url = urllib.parse.urljoin(url, loc)
    raise ValueError("redirects")


class _Meta(html.parser.HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.meta, self.ld, self._ld, self._buf = {}, [], False, []

    def handle_starttag(self, tag, attrs):
        a = {k.lower(): (v or "") for k, v in attrs}
        if tag == "meta":
            k = (a.get("property") or a.get("name") or a.get("itemprop") or "").strip().lower()
            if k and k not in self.meta and "content" in a:
                self.meta[k] = a["content"]
        elif tag == "script" and a.get("type", "").lower() == "application/ld+json":
            self._ld, self._buf = True, []

    def handle_endtag(self, tag):
        if tag == "script" and self._ld:
            self.ld.append("".join(self._buf))
            self._ld = False

    def handle_data(self, d):
        if self._ld:
            self._buf.append(d)


def to_price(v):
    try:
        n = int(float(str(v).replace(",", "").strip()))
    except (ValueError, OverflowError):
        return None
    return n if 0 <= n <= MAX_PRICE else None


def ld_price(o, depth=0):
    if depth > 8:
        return None
    if isinstance(o, dict):
        if "price" in o and to_price(o["price"]) is not None:
            return to_price(o["price"])
        o = list(o.values())
    if isinstance(o, list):
        for v in o:
            p = ld_price(v, depth + 1)
            if p is not None:
                return p
    return None


def link_parse(raw, ctype):
    m = re.search(r"charset=([\w-]+)", ctype or "", re.I)
    try:
        text = raw.decode(m.group(1) if m else "utf-8", "replace")
    except LookupError:
        text = raw.decode("utf-8", "replace")
    p = _Meta()
    try:
        p.feed(text)
    except Exception:
        pass
    name = " ".join((p.meta.get("og:title") or "").split())
    name = name.split(" | ")[0].strip()[:MAX_NAME]      # 당근은 "이름 | 70,000원 | 당근 중고거래" 처럼 뒤에 붙여요 (임의로 앞만)
    price = to_price(p.meta["product:price:amount"]) if p.meta.get("product:price:amount") else None
    if price is None:
        for s in p.ld:
            try:
                price = ld_price(json.loads(s))
            except ValueError:
                continue
            if price is not None:
                break
    if price is None:
        m = re.search(r'"price"\s*:\s*"?(\d[\d,]*(?:\.\d+)?)', text)
        price = to_price(m.group(1)) if m else None
    return name, price, (p.meta.get("og:image") or "").strip()


def phone_link(body, token):
    """POST /phone/link {"url"} -> {"name","price","image"}. 네트워크는 잠금 밖에서 해요."""
    with LOCK:
        key, acc = account_of(token)
    if acc is None:
        return 401, dict(ok=False, why="token")
    url = body.get("url") if isinstance(body, dict) else None
    if not isinstance(url, str) or len(url) > 2048 or not link_ok(url.strip(), LINK_HOSTS):
        return 400, dict(ok=False, why="host")
    try:
        final, ctype, raw, _over = link_fetch(url.strip(), LINK_HOSTS, LINK_MAX)
    except Exception as e:
        log("LINK 쪽 실패:", type(e).__name__, getattr(e, "code", ""))
        return 502, dict(ok=False, why="fetch")
    name, price, img = link_parse(raw, ctype)
    image = ""
    if img:
        img = urllib.parse.urljoin(final, img)
        hosts = LINK_HOSTS | LINK_IMG_HOSTS
        if link_ok(img, hosts):
            try:
                _f, it, b, over = link_fetch(img, hosts, PHOTO_MAX)
                it = it.split(";")[0].strip().lower()
                magic = {"image/jpeg": b[:3] == b"\xff\xd8\xff", "image/png": b[:8] == b"\x89PNG\r\n\x1a\n",
                         "image/webp": b[:4] == b"RIFF" and b[8:12] == b"WEBP"}
                if not over and it in LINK_IMG_TYPES and magic.get(it):
                    image = photo_put(b, it)
                else:
                    log("LINK 그림 거절:", it, "%dKB" % (len(b) // 1024), "넘침" if over else "")
            except Exception as e:
                log("LINK 그림 실패:", type(e).__name__, getattr(e, "code", ""))
        else:
            log("LINK 그림 호스트 거절:", urllib.parse.urlsplit(img).hostname)
    log("LINK", urllib.parse.urlsplit(final).hostname, "이름" if name else "-", "가격" if price is not None else "-",
        "그림" if image else "-")
    return 200, dict(ok=True, name=name, price=price, image=image)



class H(BaseHTTPRequestHandler):
    timeout = SOCK_TIMEOUT       # 3: 느린 연결이 스레드를 붙잡지 못하게

    def _send(self, code, obj):
        b = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self._raw(code, "application/json; charset=utf-8", b)

    def _raw(self, code, ctype, b, cache="no-store"):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(b)))
        self.send_header("Cache-Control", cache)
        self.end_headers()
        self.wfile.write(b)

    def _redirect(self, loc):
        self.send_response(302)
        self.send_header("Location", loc)
        self.send_header("Content-Length", "0")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Referrer-Policy", "no-referrer")
        self.end_headers()

    def _ip(self):
        if ON_RAILWAY:                    # 프록시 뒤에서는 마지막 주소(프록시가 붙인 값)를 봐요. 첫 주소는 보낸 사람이 꾸밀 수 있어요
            f = (self.headers.get("X-Forwarded-For") or "").split(",")[-1].strip()
            if f:
                return f[:64]
        return self.client_address[0]

    def do_GET(self):
        u = urllib.parse.urlparse(self.path)
        try:
            if u.path in ("/", "/health"):
                self._raw(200, "text/plain; charset=utf-8", b"ok")
            elif u.path == "/phone":                                   # P-01
                with open(os.path.join(HERE, "phone.html"), "rb") as f:
                    self._raw(200, "text/html; charset=utf-8", f.read())
            elif u.path == "/carmap":                                  # 차 화면 「만날 곳」 카카오 지도 (앱 WebView가 열어요)
                self._raw(200, "text/html; charset=utf-8", carmap_html())
            elif u.path.startswith("/photo/"):                         # B-30, P-04
                pid = u.path[len("/photo/"):]
                b = photo_get(pid) if PHOTO_ID.match(pid) else None
                if b is None:
                    self._send(404, dict(ok=False))
                else:
                    self._raw(200, b[1], b[0], cache="private, max-age=86400")    # 받은 형식 그대로
            elif u.path == "/phone/login":                             # 로그인 화면이 쓸 수 있는 길
                lg = dict(ok=True, test=test_allowed(), kakao=bool(KAKAO_KEY))
                if KAKAO_JS:
                    lg["kakao_js"] = KAKAO_JS
                self._send(200, lg)
            elif u.path == "/phone/login/kakao":
                self._redirect(kakao_start())
            elif u.path == "/phone/kakao":
                q = {k: v[0] for k, v in urllib.parse.parse_qs(u.query).items()}
                self._redirect(kakao_back(q))
            elif u.path == "/phone/state":
                with LOCK:
                    code, obj = phone_api(u.path, None, self.headers.get("X-Phone-Token"), self._ip())
                self._send(code, obj)
            else:
                self._send(404, dict(ok=False))
        except Exception as e:
            log("요청 오류", repr(e))
            self._send(400, dict(ok=False, msg="잘못된 요청"))

    def do_POST(self):
        u = urllib.parse.urlparse(self.path)
        try:
            n = int(self.headers.get("Content-Length") or 0)
            cap = PHOTO_MAX if u.path == "/phone/photo" else MAX_BODY     # 사진 올리기만 2MB
            if n < 0 or n > cap:
                self.close_connection = True
                self._send(413 if u.path == "/phone/photo" else 400, dict(ok=False, msg="잘못된 요청"))
                return
            if u.path == "/phone/photo":                               # 본문 읽기와 사진 저장은 잠금 밖
                raw = self.rfile.read(n) if n else b""
                q = {k: v[0] for k, v in urllib.parse.parse_qs(u.query).items()}
                code, obj = phone_photo(raw, q.get("item", "")[:MAX_ID], self.headers.get("X-Phone-Token"))
                self._send(code, obj)
                return
            body = json.loads(self.rfile.read(n).decode("utf-8") or "{}") if n else {}
            if u.path in ("/phone/link", "/phone/home"):               # 남의 쪽을 받는 동안 잠그지 않아요
                code, obj = (phone_link if u.path == "/phone/link" else phone_home)(body, self.headers.get("X-Phone-Token"))
                self._send(code, obj)
                return
            with LOCK:
                if u.path == "/sync":
                    self._send(200, sync(body))
                elif u.path.startswith("/phone/"):
                    code, obj = phone_api(u.path, body, self.headers.get("X-Phone-Token"), self._ip())
                    self._send(code, obj)
                elif u.path.startswith("/demo/"):
                    if not demo_allowed(self.headers):
                        self._send(403, dict(ok=False, msg="데모 명령 열쇠가 맞지 않아요"))
                        return
                    q = {k: v[0] for k, v in urllib.parse.parse_qs(u.query).items()}
                    ok, msg = demo(u.path[len("/demo/"):], q)
                    log("DEMO", u.path[6:], short(q.get("car", "?")), "->", msg)
                    self._send(200, dict(ok=ok, msg=msg))
                else:
                    self._send(404, dict(ok=False))
        except Exception as e:
            log("요청 오류", repr(e))
            self._send(400, dict(ok=False, msg="잘못된 요청"))

    def log_message(self, *a):
        pass


def main():
    global SALT
    port = int(os.environ.get("PORT", "8099"))
    SALT = load_salt()
    load_seed_photos()
    with LOCK:
        load_owners()
        load_accounts()
    threading.Thread(target=ticker, daemon=True).start()
    for _ in range(ROUTE_WORKERS):
        threading.Thread(target=route_worker, daemon=True).start()
    log("로드마켓 서버 0.0.0.0:%d 카카오 %s 데모 열쇠 %s 시험 로그인 %s" % (port, "있음" if KAKAO_KEY else "없음",
        "있음" if DEMO_TOKEN else "없음", "켜짐" if test_allowed() else "꺼짐"))
    log("카카오 로그인 Client Secret %s · 계정 비밀 값 %s · 돌아올 주소 %s" % (
        "있음" if KAKAO_SECRET else "없음", "설정값" if env_value("ACCOUNT_SALT") else "data/salt", redirect_uri()))
    log("지도 키 %s" % ("있음" if KAKAO_JS else "없음"))
    ThreadingHTTPServer(("0.0.0.0", port), H).serve_forever()


if __name__ == "__main__":
    main()
