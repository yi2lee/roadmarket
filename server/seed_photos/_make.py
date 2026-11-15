#!/usr/bin/env python3
"""시연용 씨앗 사진을 만드는 일회용 스크립트. 맥에서만 돌려요 (Pillow 필요, 서버는 쓰지 않아요).

    python3 _make.py        # 이 폴더에 그림 파일을 다시 만들어요 (같은 이름은 덮어써요)

진짜 사진으로 바꾸려면 같은 파일 이름으로 갈아 끼우면 돼요. server.py 가 이름으로 물건에 이어 둬요.
글자는 넣지 않아요. 정사각 640px, 옅은 그라데이션 배경에 단순한 도형.
"""
import os
from PIL import Image, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
S = 640
K = 2                      # 두 배로 그린 뒤 줄여서 테두리를 부드럽게 해요
W = S * K


def p(*v):
    return tuple(int(a * K) for a in v)


def canvas(top, bottom):
    img = Image.new("RGB", (W, W), top)
    d = ImageDraw.Draw(img)
    for y in range(W):
        t = y / (W - 1)
        d.line([(0, y), (W, y)], fill=tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)))
    return img


def shadow(img, box, alpha=70):
    sh = Image.new("L", img.size, 0)
    ImageDraw.Draw(sh).ellipse(p(*box), fill=alpha)
    sh = sh.filter(ImageFilter.GaussianBlur(14 * K))
    img.paste((40, 40, 40), (0, 0, W, W), sh)


def save(img, name):
    img = img.resize((S, S), Image.LANCZOS)
    img.save(os.path.join(HERE, name), "JPEG", quality=88)
    print("만들었어요", name)


def junior_carseat():
    img = canvas((226, 232, 238), (198, 206, 216))
    shadow(img, (170, 520, 470, 570))
    d = ImageDraw.Draw(img)
    gray, dark = (96, 104, 116), (64, 70, 80)
    d.rounded_rectangle(p(190, 400, 450, 540), 36 * K, fill=gray)          # 앉는 곳
    d.rounded_rectangle(p(220, 110, 420, 440), 60 * K, fill=gray)          # 등받이
    d.rounded_rectangle(p(205, 120, 435, 210), 40 * K, fill=dark)          # 머리 받침
    d.rounded_rectangle(p(160, 360, 220, 520), 24 * K, fill=dark)          # 팔걸이
    d.rounded_rectangle(p(420, 360, 480, 520), 24 * K, fill=dark)
    d.rounded_rectangle(p(250, 230, 390, 400), 30 * K, fill=(120, 128, 140))  # 등 쿠션
    save(img, "fake_02_junior_carseat.jpg")


def phone_mount():
    img = canvas((236, 230, 222), (214, 204, 192))
    shadow(img, (220, 520, 420, 560))
    d = ImageDraw.Draw(img)
    blk = (48, 50, 56)
    d.ellipse(p(250, 470, 390, 540), fill=blk)                            # 흡착판
    d.rounded_rectangle(p(305, 330, 335, 500), 14 * K, fill=blk)          # 팔
    d.ellipse(p(290, 300, 350, 360), fill=(80, 84, 92))                    # 관절
    d.rounded_rectangle(p(200, 120, 440, 330), 26 * K, fill=blk)          # 잡이틀
    d.rounded_rectangle(p(230, 140, 410, 310), 18 * K, fill=(140, 170, 196))  # 휴대폰 화면
    d.rounded_rectangle(p(180, 200, 210, 260), 10 * K, fill=(80, 84, 92))  # 옆 집게
    d.rounded_rectangle(p(430, 200, 460, 260), 10 * K, fill=(80, 84, 92))
    save(img, "fake_04_phone_mount.jpg")


def car_vacuum():
    img = canvas((222, 234, 230), (196, 214, 208))
    shadow(img, (130, 470, 520, 530))
    d = ImageDraw.Draw(img)
    body, accent = (70, 92, 120), (220, 120, 70)
    d.rounded_rectangle(p(200, 260, 470, 380), 56 * K, fill=body)         # 몸통
    d.ellipse(p(400, 250, 500, 390), fill=(90, 112, 140))                  # 먼지통 끝
    d.polygon(p(210, 300, 210, 340, 120, 470, 90, 455), fill=(110, 116, 126))  # 흡입관
    d.rounded_rectangle(p(70, 440, 150, 490), 12 * K, fill=(60, 64, 72))  # 흡입구
    d.rounded_rectangle(p(330, 380, 390, 480), 20 * K, fill=body)         # 손잡이
    d.ellipse(p(300, 290, 340, 330), fill=accent)                          # 버튼
    save(img, "fake_06_car_vacuum.jpg")


def dashcam():
    img = canvas((228, 228, 234), (200, 202, 212))
    shadow(img, (190, 450, 450, 500))
    d = ImageDraw.Draw(img)
    blk = (36, 38, 44)
    d.rounded_rectangle(p(280, 130, 360, 210), 16 * K, fill=(70, 72, 80))  # 거치 받침
    d.rectangle(p(305, 200, 335, 250), fill=(70, 72, 80))
    d.rounded_rectangle(p(170, 240, 470, 440), 40 * K, fill=blk)          # 몸통
    d.ellipse(p(250, 270, 390, 410), fill=(20, 22, 26))                    # 렌즈 테
    d.ellipse(p(280, 300, 360, 380), fill=(56, 80, 120))                   # 렌즈
    d.ellipse(p(300, 315, 325, 340), fill=(170, 196, 230))                 # 빛
    d.ellipse(p(420, 270, 440, 290), fill=(200, 60, 60))                   # 녹화 불
    save(img, "fake_08_dashcam.jpg")


def stroller():
    img = canvas((238, 232, 228), (220, 210, 204))
    shadow(img, (130, 520, 500, 570))
    d = ImageDraw.Draw(img)
    frame, cloth = (70, 70, 76), (176, 150, 128)
    d.line(p(190, 500, 330, 300), fill=frame, width=14 * K)                # 앞다리
    d.line(p(450, 500, 330, 300), fill=frame, width=14 * K)                # 뒷다리
    d.line(p(330, 300, 470, 140), fill=frame, width=14 * K)                # 손잡이 대
    d.rounded_rectangle(p(440, 120, 520, 150), 14 * K, fill=frame)         # 손잡이
    d.pieslice(p(170, 160, 390, 380), 180, 270, fill=cloth)                # 차양
    d.rounded_rectangle(p(200, 260, 420, 330), 24 * K, fill=(150, 126, 106))  # 앉는 곳
    for cx in (190, 450):
        d.ellipse(p(cx - 44, 456, cx + 44, 544), fill=(40, 40, 44))
        d.ellipse(p(cx - 18, 482, cx + 18, 518), fill=(150, 150, 156))
    save(img, "fake_09_stroller.jpg")


def tire_inflator():
    img = canvas((232, 230, 220), (210, 206, 190))
    shadow(img, (170, 480, 470, 530))
    d = ImageDraw.Draw(img)
    body = (60, 66, 72)
    d.rounded_rectangle(p(190, 220, 450, 500), 40 * K, fill=body)         # 몸통
    d.rounded_rectangle(p(230, 260, 410, 360), 18 * K, fill=(150, 190, 170))  # 계기판
    d.ellipse(p(240, 390, 300, 450), fill=(232, 176, 60))                  # 버튼
    d.ellipse(p(340, 390, 400, 450), fill=(90, 96, 104))
    d.arc(p(380, 100, 560, 300), 180, 360, fill=(30, 32, 36), width=14 * K)  # 호스
    d.line(p(560, 200, 560, 320), fill=(30, 32, 36), width=14 * K)
    d.rounded_rectangle(p(540, 310, 580, 360), 8 * K, fill=(180, 180, 186))  # 밸브 꼭지
    d.line(p(400, 220, 390, 200), fill=(30, 32, 36), width=14 * K)
    save(img, "fake_11_tire_inflator.jpg")


def trunk_organizer():
    img = canvas((224, 230, 222), (200, 210, 198))
    shadow(img, (100, 470, 540, 530))
    d = ImageDraw.Draw(img)
    box, edge = (66, 78, 70), (46, 56, 50)
    d.polygon(p(120, 240, 520, 240, 500, 490, 140, 490), fill=box)        # 몸통
    d.polygon(p(120, 240, 520, 240, 540, 210, 100, 210), fill=edge)       # 위 테
    d.line(p(253, 245, 260, 485), fill=edge, width=8 * K)                  # 칸막이
    d.line(p(386, 245, 380, 485), fill=edge, width=8 * K)
    d.rounded_rectangle(p(270, 160, 370, 200), 16 * K, outline=edge, width=12 * K)  # 손잡이
    d.rectangle(p(170, 330, 230, 400), fill=(92, 104, 96))                 # 옆 주머니
    save(img, "seed_trunk_organizer.jpg")


def neck_pillow():
    img = canvas((236, 228, 236), (214, 202, 216))
    shadow(img, (120, 450, 360, 500))
    shadow(img, (300, 470, 540, 520))
    d = ImageDraw.Draw(img)
    for (x, y, c) in ((120, 180, (140, 120, 150)), (300, 220, (170, 150, 176))):
        d.rounded_rectangle(p(x, y, x + 230, y + 270), 110 * K, fill=c)    # 뼈다귀 모양 쿠션
        d.ellipse(p(x + 70, y + 90, x + 160, y + 180), fill=tuple(v - 24 for v in c))
    save(img, "seed_neck_pillow.jpg")


def cooler_box():
    img = canvas((220, 232, 240), (194, 212, 226))
    shadow(img, (140, 480, 500, 540))
    d = ImageDraw.Draw(img)
    blue, white = (60, 120, 170), (236, 240, 244)
    d.rounded_rectangle(p(150, 260, 490, 500), 28 * K, fill=blue)         # 몸통
    d.rounded_rectangle(p(140, 220, 500, 280), 22 * K, fill=white)        # 뚜껑
    d.rounded_rectangle(p(260, 160, 380, 200), 16 * K, outline=(80, 84, 92), width=12 * K)  # 손잡이
    d.line(p(200, 220, 260, 180), fill=(80, 84, 92), width=12 * K)
    d.line(p(440, 220, 380, 180), fill=(80, 84, 92), width=12 * K)
    d.rounded_rectangle(p(270, 330, 370, 380), 12 * K, fill=(90, 150, 196))  # 앞 손잡이 홈
    save(img, "seed_cooler_box.jpg")


if __name__ == "__main__":
    for f in (junior_carseat, phone_mount, car_vacuum, dashcam, stroller, tire_inflator,
              trunk_organizer, neck_pillow, cooler_box):
        f()
