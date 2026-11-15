로드마켓 서버: `python3 server.py` (포트는 PORT, 없으면 8099 · 카카오 키는 KAKAO_REST_KEY 또는 .env · Railway는 Procfile로 같은 명령을 써요)

폰 화면: 서버 주소 뒤에 `/phone` (같은 폴더의 `phone.html`). 처음 열면 로그인이에요. 차 잇기는 로그인한 뒤 한 번만 해요.

계정은 `data/accounts.json`에 저장해요(서버를 다시 켜도 남아요. Railway에서 다시 올리면 사라져요). 사진은 메모리에만 있어서 서버를 다시 켜면 사라져요.

환경 변수 (`KAKAO_REST_KEY`, `KAKAO_CLIENT_SECRET`, `ACCOUNT_SALT`, `KAKAO_JS_KEY`는 환경 변수가 없으면 같은 폴더의 `.env`에서 읽어요)

- `KAKAO_REST_KEY` 카카오 REST 키(로그인의 client_id로도 써요)
- `KAKAO_JS_KEY` 카카오 지도 JavaScript 키(공개용). 있으면 `GET /phone/login`에 `kakao_js`로 실어요. 켤 때 기록에는 있음/없음만 찍어요
- `KAKAO_CLIENT_SECRET` 카카오 로그인 Client Secret을 켰을 때만
- `PUBLIC_URL` 밖에서 보이는 서버 주소(예: `https://relay-production-68643d09.up.railway.app`). 카카오가 돌아올 주소는 `PUBLIC_URL/phone/kakao`, 없으면 `http://localhost:<PORT>/phone/kakao`
- `ACCOUNT_SALT` 계정 열쇠를 만드는 비밀 값. 없으면 `data/salt`에 만들어요. 바꾸면 기존 카카오 계정과 이어지지 않아요
- `ALLOW_TEST_LOGIN` `1`이면 시험용 로그인을 켜요. 없으면 Railway 밖에서만 켜져요

로그인

- `GET /phone/login` → `{"test":true|false,"kakao":true|false,"kakao_js":"<키>"}` (`kakao_js`는 키가 있을 때만)
- `GET /phone/login/kakao` → 카카오 로그인으로 보내요. 돌아오면(`GET /phone/kakao`) `/phone#t=<토큰>`, 실패하면 `/phone#e=1`
- `POST /phone/login/test` `{"name":"시험 1"}` → `{"token"}` (이름 20자, 글자·숫자·띄어쓰기. 같은 이름은 같은 계정)

아래는 머리글 `X-Phone-Token: <token>` 이 있어야 해요 (틀리면 401)

- `GET /phone/state` → `{"sells":[{id,name,price,photo}],"wants":[...],"car":true|false}` (id는 차에 들어간 물건에만)
- `POST /phone/sell` `{"name","price","photo"}` · `POST /phone/want` `{"name"}` (이미 있으면 409 `dup`)
- `POST /phone/photo[?item=<차 물건 id>]` 본문 JPEG(2MB 이하) → `{"photo":id}`
- `POST /phone/link` `{"url"}` → `{"name","price","image"}` (당근·번개장터 https 주소만, 아니면 400 `host`, 못 받으면 502 `fetch`)
- `POST /phone/pair` `{"code":"482913","plate":"12가 3456"}` → 차를 이어요 (틀리면 403, 1분에 10번 틀리면 429). `plate`는 선택
- `POST /phone/plate` `{"plate":"12가 3456"}` → 번호판 넣기·고치기, 답 `{"plate4":"3456"}`. 번호판은 12자까지, 숫자 네 자리로 끝나야 해요(아니면 400 `why:"plate"`). 전체는 계정(`data/accounts.json`)에 저장하고 남에게는 뒤 네 자리만 줘요: `me.plate4`, 상대 정보(`seller`·`buyer`)의 `plate4`, 상대 차 글자(`deal` 일의 `peer_car`)는 번호판이 있으면 "번호 끝 3456", 없으면 차가 올린 `car_info`
- `POST /phone/unpair` 차 연결을 풀어요 · `POST /phone/logout` 이 토큰을 지워요
- `GET /photo/<id>` → 받은 형식 그대로(`image/jpeg|png|webp`, 머리글 필요 없음)

등록·발견 단계 (약속: `기능정의/약속_서버_차앱_폰.md`)

- 물건·찾는 물건의 원본은 서버가 주인별로 `data/owners.json`에 둬요. 주인은 이어진 계정, 안 이어졌으면 `c:<차>`.
- `/sync`: `state.dest`, `state.driving`, 새 `cmds`(sell_*/want_*/wants_alert/home_here/browse/restore/urgent_*/route_answer/course_ask), 응답 `me`, `browse`. 옛 앱(`driving` 없음)이 올리는 `state.sells`/`wants`는 서버 목록이 비어 있을 때부터 그대로 받아들여요.
- 폰: `/phone/sell/update|remove|hide|status`, `/phone/want/remove`, `/phone/wants_alert`, `/phone/home {query}`(카카오 주소 검색, 좌표와 구 이름만 저장), `/phone/nick`, `/phone/request/answer`, `/phone/deal/cancel`, `/phone/urgent/reply`.
- 데모: `POST /demo/route?car=…`, `POST /demo/urgent?car=…`.
- 시험: `ROADMARKET_DATA_DIR=<임시 폴더> PORT=8098 python3 server.py` 로 데이터 폴더를 따로 써요.

만남·완료 단계 (약속 9절)

- `/sync` 응답 `meet` `{place{name,lat,lon}, my_eta_min, peer{nick,eta_min,lat,lon,home}}`: 거래가 정해지고 만날 곳이 있는 동안만. 분은 길찾기 기억(1분)을 쓰고 뒤에서 채워요(처음·실패는 직선 추정). 집 판매자면 `peer.home:true`, 좌표는 만날 곳. 상대가 먼저 끝내면 `peer` 좌표·분은 null.
- 명령 `deal_end {deal, done, stars?}`(stars 0~5, 없어도 돼요), `got_out`(거래 중 이어진 폰에 물음).
- 폰 `deal`에 `place.lat/lon`, `buyer_pos`, `buyer_eta_min`, `ask`. `POST /phone/deal/review {done, stars?}` → 이 사람 쪽에서 거래를 끝내요(차의 deal_end와 같은 쪽이면 먼저 온 것만). 없으면 404 `deal`, stars가 틀리면 400 `stars`.
- 별점은 상대 주인에게 쌓여 `rating`(평균, 소수 한 자리)이 돼요. `data/owners.json`의 `stars_sum`, `stars_n`.
