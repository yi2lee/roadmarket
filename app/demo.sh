#!/bin/bash
# 로드마켓 데모용 장치 (요건표 7절, D-03~D-24). 맥의 터미널에서 실행해요.
#   ./demo.sh [A|B] <명령> [값]     (A·B를 안 적으면 A예요)
export ANDROID_HOME="$HOME/Library/Android/sdk"
PKG=ai.roadmarket.app
HERE="$(cd "$(dirname "$0")" && pwd)"
SRV_DIR="$(cd "$HERE/../server" && pwd)"
APK="$HERE/app/build/outputs/apk/debug/app-debug.apk"
RAILWAY_URL=https://relay-production-68643d09.up.railway.app
LOCAL_URL=http://127.0.0.1:8099          # 맥에서 서버를 부를 때
LOCAL_APP_URL=http://10.0.2.2:8099       # 에뮬레이터에서 맥을 부를 때
USE_FILE="$HERE/.server"                 # use local | use railway 로 고른 서버
BUILT_FILE="$HERE/.server-built"         # install이 마지막으로 앱에 넣은 서버 주소

# D-22: 어느 차에 넣을지
CAR=A
case "$1" in A|B) CAR="$1"; shift ;; esac
if [ "$CAR" = B ]; then DEV=emulator-5556; CPORT=8089; else DEV=emulator-5554; CPORT=8088; fi
CONSOLE=http://127.0.0.1:$CPORT

if [ "$(cat "$USE_FILE" 2>/dev/null)" = railway ]; then SERVER=$RAILWAY_URL; else SERVER=$LOCAL_URL; fi

a() { "$ANDROID_HOME/platform-tools/adb" -s "$DEV" "$@"; }
# 앱 안의 데모 수신기에 명령 보내기
demo() { a shell am broadcast -f 0x20 -n "$PKG/.DemoReceiver" --es cmd "$@" >/dev/null; }
# 이 차의 식별자 (앱이 설치마다 무작위로 만든 것)
carid() { a shell am broadcast -f 0x20 -n "$PKG/.DemoReceiver" --es cmd id 2>/dev/null | sed -n 's/.*data="\([^"]*\)".*/\1/p' | tr -d '\r'; }
# 서버에 가짜 차 명령 보내기. 서버에 DEMO_TOKEN이 있으면 같은 값을 ROADMARKET_DEMO_TOKEN에 넣어 두세요
server() {
  local id; id="$(carid)"
  [ -n "$id" ] || { echo "앱에서 차 식별자를 못 읽었어요. 앱을 먼저 여세요 (demo.sh $CAR open)"; exit 1; }
  curl -s -m 5 -X POST ${ROADMARKET_DEMO_TOKEN:+-H "X-Demo-Token: $ROADMARKET_DEMO_TOKEN"} \
    "$SERVER/demo/$1?car=$id${2:+&no=$2}"; echo
}
# 차 조작판: 먼저 켜 두는 신호를 보낸 뒤 값을 써요
console() {
  a forward tcp:$CPORT tcp:8088 >/dev/null
  curl -s -m 3 -X POST "$CONSOLE/browser/session?client=roadmarket-demo" >/dev/null
  curl -s -m 3 -X POST "$CONSOLE$1" >/dev/null
}
# 지금 차에 타고 있는 사용자 번호 (에뮬레이터는 10번). 앱 자료와 알림 권한은 이 사용자 몫이에요
user() { a shell am get-current-user | tr -d '\r'; }
grant() { a shell pm grant --user "$(user)" "$PKG" android.permission.POST_NOTIFICATIONS; }
usage() { sed -n '2,3p' "$0"; grep -E '^  [a-z-]+(\|[a-z-]+)*\)' "$0" | sed -E 's/\).*;; +# /  : /; s/\) +# /  : /'; exit 1; }
boot() {   # $1 = 기기 이름
  "$ANDROID_HOME/platform-tools/adb" -s "$1" wait-for-device
  until [ "$("$ANDROID_HOME/platform-tools/adb" -s "$1" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
}
EMU="$ANDROID_HOME/emulator/emulator -avd Pleos_Connect_v2_1 -no-snapshot -no-window -gpu host -no-audio"

case "$1" in
  near)            server near ;;                     # D-03 가짜 파는 차 다섯 대를 이 차 주변에 보내기
  request)         server request ;;                  # D-04 가짜 사는 차의 요청 (수락하면 5초 뒤 나를 고름)
  request-other)   server request-other ;;            # D-05 수락하면 5초 뒤 다른 물건을 고름
  request-silent)  server request-silent ;;           # D-06 수락해도 고르지 않음
  withdraw)        server withdraw ;;                 # D-07 가짜 사는 차가 요청을 거둠
  gone)            [ -n "$2" ] || usage; server gone "$2" ;;   # D-08 gone 2: 2번 차가 주변에서 사라짐
  seller-cancel)   [ -n "$2" ] || usage; server seller-cancel "$2" ;;   # D-09 seller-cancel 2: 2번이 수락을 취소
  peer-cancel)     server peer-cancel ;;              # D-10 가짜 상대가 거래를 취소
  dest)            demo dest ;;                       # D-11 내비에 데모 목적지 넣기
  dest-clear)      demo dest-clear ;;                 # D-11 내비 길 안내 끝내기
  gear)                                               # D-12 gear P | gear D: 기어 바꾸기
    case "$2" in
      P) console "/vehicle/gear?gear=Park" ;;
      D) console "/vehicle/gear?gear=Drive" ;;
      *) usage ;;
    esac ;;
  door)                                               # D-12 운전석 문을 한 번 열었다 닫기
    console "/vehicle/body?door_fl=true"; sleep 1; console "/vehicle/body?door_fl=false" ;;
  install)                                            # D-13 install | install railway: 서버 주소를 넣어 빌드하고 설치, 알림 허용
    if [ "$2" = railway ]; then URL=$RAILWAY_URL; echo railway > "$USE_FILE"; else URL=$LOCAL_APP_URL; echo local > "$USE_FILE"; fi
    # 지난번 빌드와 서버 주소가 다르거나 모르면 먼저 지워요. 안 지우면 옛 주소가 앱에 남을 때가 있었어요
    if [ "$(cat "$BUILT_FILE" 2>/dev/null)" != "$URL" ]; then
      rm -f "$BUILT_FILE"
      (cd "$HERE" && JAVA_HOME=/opt/homebrew/opt/openjdk@17 /opt/homebrew/opt/gradle@8/bin/gradle -q clean) || exit 1
    fi
    (cd "$HERE" && JAVA_HOME=/opt/homebrew/opt/openjdk@17 /opt/homebrew/opt/gradle@8/bin/gradle -q assembleDebug -PserverUrl="$URL") || exit 1
    echo "$URL" > "$BUILT_FILE"
    a install -r "$APK" && grant ;;
  use)                                                # use local | use railway: 가짜 차 명령을 보낼 서버 고르기
    case "$2" in local|railway) echo "$2" > "$USE_FILE"; echo "서버: $2" ;; *) usage ;; esac ;;
  pos)                                                # D-23 pos 37.4127 127.0947: 이 차의 시험용 위치 넣기
    [ -n "$3" ] || usage; demo pos --es lat "$2" --es lon "$3" ;;
  mycar)                                              # D-24 mycar "흰색 SUV" 1204: 내 차의 색·종류와 번호 끝 네 자리
    [ -n "$3" ] || usage; demo mycar --es kind "'$2'" --es plate "$3" ;;
  say)                                                # D-16 say "로드마켓 앱으로 거래 알림 켜 줘": 글레오에 글자로 말 넣기
    [ -n "$2" ] || usage
    a shell "am broadcast -a ai.umos.intent.action.ASR --es ASR '$2'" >/dev/null ;;
  reset)                                              # D-17 목록과 거래 횟수를 지우고 앱을 다시 열기
    demo dest-clear                                   # 내비의 목적지·경유지도 먼저 비워요 (dest-clear와 같은 일)
    a shell pm clear --user "$(user)" "$PKG" >/dev/null
    grant
    a shell am start -n "$PKG/.MainActivity" >/dev/null ;;
  spoken)                                             # D-18 앱 목소리(SAY)와 글레오 답 문장(GLEO)을 글자로 보기
    a logcat -d -v time -s ROADMARKET_SAY:I ROADMARKET_GLEO:I | grep ROADMARKET_ ;;
  night)                                              # D-19 night on | night off: 밤·낮 바꾸기
    case "$2" in
      on)  a shell cmd uimode night yes >/dev/null ;;
      off) a shell cmd uimode night no >/dev/null ;;
      *) usage ;;
    esac ;;
  server-start)                                       # D-21 맥에서 서버 켜기
    if lsof -ti tcp:8099 -sTCP:LISTEN >/dev/null; then echo "이미 켜져 있어요"; exit 0; fi
    (cd "$SRV_DIR" && nohup python3 server.py >> "$SRV_DIR/server.log" 2>&1 &)
    sleep 1; curl -s -m 2 "$LOCAL_URL/health"; echo ;;
  server-stop)                                        # D-21 맥의 서버 끄기 (받아 둔 것이 모두 사라져요)
    P="$(lsof -ti tcp:8099 -sTCP:LISTEN)"; [ -n "$P" ] && kill $P ;;
  emu-start)                                          # D-20, D-22 emu-start | emu-start 2: 에뮬레이터 켜기 (창 없이)
    if [ "$2" = 2 ]; then
      nohup $EMU -read-only -port 5554 >/dev/null 2>&1 &
      boot emulator-5554
      nohup $EMU -read-only -port 5556 -memory 2048 >/dev/null 2>&1 &
      boot emulator-5556
      "$ANDROID_HOME/platform-tools/adb" -s emulator-5554 forward tcp:8088 tcp:8088 >/dev/null
      "$ANDROID_HOME/platform-tools/adb" -s emulator-5556 forward tcp:8089 tcp:8088 >/dev/null
    else
      nohup $EMU -port 5554 >/dev/null 2>&1 &
      boot emulator-5554
      "$ANDROID_HOME/platform-tools/adb" -s emulator-5554 forward tcp:8088 tcp:8088 >/dev/null
    fi ;;
  emu-stop)        a emu kill ;;                      # D-20 에뮬레이터 끄기
  open)            a shell am start -n "$PKG/.MainActivity" >/dev/null ;;   # D-20 로드마켓 화면을 앞에 열기
  restart)                                            # D-20 앱을 껐다 켜기 (C-15)
    a shell am force-stop "$PKG"; a shell am start -n "$PKG/.MainActivity" >/dev/null ;;
  navi)            a shell am start -n ai.umos.maps.android.navigation.app/.MainActivity >/dev/null ;;   # D-20 내비 화면을 앞에 열기
  shot)                                               # D-20 shot 이름: 화면을 shots/이름.png로 찍기
    [ -n "$2" ] || usage
    mkdir -p "$HERE/shots" && a exec-out screencap -p > "$HERE/shots/$2.png" && echo "$HERE/shots/$2.png" ;;
  *) usage ;;
esac
