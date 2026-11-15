package ai.roadmarket.app

import java.text.NumberFormat
import java.util.Locale

/** 화면 글, 앱 목소리, 글레오 답, 알림 글. 모두 요건표의 글자 그대로예요. */
object Texts {
    /** 가격을 글자로 만드는 단 한 곳. 0원은 어디서든 "나눔"이에요. */
    fun won(p: Long): String = if (p == 0L) SHARE else NumberFormat.getNumberInstance(Locale.KOREA).format(p) + "원"

    // 0-6: 조사는 이름의 끝소리 받침에 맞춰요. 한글, 숫자, 영문으로 끝나는 이름을 다 봐요.
    fun hasBatchim(word: String): Boolean {
        val c = word.lastOrNull { it.isLetterOrDigit() } ?: return false
        if (c in '가'..'힣') return (c - '가') % 28 != 0
        if (c in '0'..'9') return c in "013678"              // 영·일·삼·육·칠·팔, 10·100·1000·10000(십·백·천·만)도 받침
        if (c.lowercaseChar() !in 'a'..'z') return false
        val tail = word.substring(0, word.lastIndexOf(c) + 1).takeLastWhile { it in 'a'..'z' || it in 'A'..'Z' }
        if (tail.length == 1 || (tail.length <= 4 && tail.all { it.isUpperCase() }))
            return tail.last().uppercaseChar() in "LMNR"                // 글자 이름으로 읽음: 엘·엠·엔·알
        val t = tail.lowercase()
        return t.endsWith("ng") || t.last() in "lmn" || t.last() in "bckp"   // 단어로 읽음: 탭·맥·북·컵·폰
    }
    fun eulReul(w: String) = w + if (hasBatchim(w)) "을" else "를"
    fun ieyo(w: String) = w + if (hasBatchim(w)) "이에요" else "예요"
    private fun quotedEulReul(w: String) = "‘$w’" + if (hasBatchim(w)) "을" else "를"

    // 메뉴
    const val MENU_HOME = "홈"
    const val MENU_SELLS = "파는 물건"
    const val MENU_WANTS = "찾는 물건"

    // 홈 (C-01, C-03, C-04, C-08, C-11, C-12)
    const val TRADE = "거래 알림"
    const val SWITCH_ON = "켜짐"
    const val SWITCH_OFF = "꺼짐"
    const val STATUS_OFF = "꺼져 있어요. 켜면 달리는 동안 거래를 알려 드려요."
    const val STATUS_ON = "켜져 있어요. 주변 차를 살피는 중이에요."
    const val STATUS_BUSY = "켜져 있어요. 진행 중인 거래가 있어요."
    const val SERVER_DOWN = "서버에 닿지 않아요."                         // 7-0
    const val TRADE_ON_NOTE = "로드마켓 거래 알림 켜짐"
    const val OFF_ASK = "진행 중인 거래가 취소돼요. 끌까요?"
    const val OFF_YES = "끄기"
    fun countSells(n: Int) = "파는 물건 ${n}개"
    fun countWants(n: Int) = "찾는 물건 ${n}개"
    fun countDeals(n: Int) = "내 거래 ${n}번"
    /** C-23: "폰 연결 번호 482 913" (세 자리씩 띄워요) */
    fun pairLine(code: String) = "폰 연결 번호 " + (if (code.length == 6) code.substring(0, 3) + " " + code.substring(3) else code)
    fun cardNearby(n: Int) = "주변 물건 ${n}개 · 보기"
    const val HOME_EMPTY = "먼저 파는 물건이나 찾는 물건을 넣어 주세요."      // C-21
    fun just(reason: String) = "방금: $reason"                              // C-22
    const val JUST_NO_ANSWER = "답하지 않아 요청이 지나갔어요."             // S-24
    const val JUST_NOT_DONE = "거래를 하지 않았어요."                       // M-11
    const val JUST_CANCELLED = "거래를 취소했어요."                         // S-28, B-54, M-15
    const val CARD_REQUEST = "거래 요청이 왔어요 · 보기"
    const val CARD_MEET = "만날 곳 · 보기"
    const val CARD_ASK = "거래 어땠어요? · 답하기"

    // 파는 물건 (S-01~S-12)
    const val SELLS_EMPTY_1 = "올린 물건이 없어요."
    const val SELLS_EMPTY_2 = "차에 있는 물건을 올려 두고 평소처럼 달리면 돼요."
    const val SELL_ADD = "물건 올리기"
    const val SELL_SUBMIT = "올리기"
    const val WON_UNIT = "원"                                                // S-03 가격 칸 옆
    const val CLOSE = "닫기"
    const val SELL_MISSING = "이름과 가격을 넣어 주세요."
    const val PRICE_TOO_HIGH = "가격은 10억 원까지 넣을 수 있어요."
    const val ITEMS_FULL = "50개까지 넣을 수 있어요."              // 파는 물건·찾는 물건 각각 (서버 한도)
    const val SELLS_DRIVING_1 = "달리는 중에는 말로 올려요."
    const val SELLS_DRIVING_2 = "“글레오, 로드마켓 앱으로 방향제 만 원에 올려 줘”"
    const val SELL_REMOVE = "내리기"
    fun sellRemoveAsk(name: String) = quotedEulReul(name) + " 내릴까요?"
    const val KEEP = "그대로 두기"
    const val IN_DEAL = "거래 중"
    const val FIELD_NAME = "이름"
    const val FIELD_PRICE = "가격"

    // 찾는 물건 (B-01~B-10)
    const val WANTS_EMPTY_1 = "찾는 물건이 없어요."
    const val WANTS_EMPTY_2 = "넣어 두면 달리다가 주변 차에 있을 때 알려 드려요."
    const val WANT_ADD = "찾는 물건 넣기"
    const val WANT_SUBMIT = "넣기"
    const val WANT_MISSING = "찾는 물건 이름을 넣어 주세요."
    const val WANT_DUP = "이미 넣은 물건이에요."
    const val WANTS_DRIVING_1 = "달리는 중에는 말로 넣어요."
    const val WANTS_DRIVING_2 = "“글레오, 로드마켓 앱으로 주니어 카시트 찾아 줘”"
    const val WANT_REMOVE = "지우기"
    fun wantRemoveAsk(name: String) = quotedEulReul(name) + " 지울까요?"

    // 주변 물건 (B-20~B-29, B-40~B-54)
    const val NEARBY_TITLE = "주변 물건"
    fun no(n: Int) = "${n}번"
    fun minutes(m: Int) = "${m}분 거리"
    const val NOT_NEAR = "주변에 없음"
    const val SEND = "거래 요청"                                       // 약속 8절
    const val SEND_ALL = "모두 보내기"
    const val WAITING = "답 기다리는 중"
    const val ACCEPTED = "수락함"
    const val REJECTED = "거절함"
    const val NO_ANSWER = "응답 없음"                                   // 약속 8절
    const val CANCELLED = "취소함"
    const val BUSY_OTHER = "다른 거래 중"                                   // B-56
    fun sayCancelled(n: Int) = "${n}번이 수락을 취소했어요."
    fun pick(n: Int) = "${n}번으로 하기"
    const val WITHDRAW = "요청 거두기"
    fun nearbyHead(n: Int) = "찾으시는 물건이 주변에 ${n}개 있어요."
    fun nearbyItem(n: Int, name: String, price: Long, m: Int) = "${n}번, $name, ${won(price)}, ${m}분 거리."
    fun nearbyNoteTitle(n: Int) = "주변에 찾던 물건 ${n}개"
    fun nearbyNoteItem(n: Int, name: String, price: Long) = "${n}번 $name ${won(price)}"
    fun sayAccepted(n: Int) = "${n}번이 수락했어요."
    fun sayRejected(n: Int) = "${n}번은 거절했어요."
    fun sayNoAnswer(n: Int) = "${n}번은 응답이 없어요."
    fun sayGone(n: Int) = "${n}번이 주변에서 사라졌어요."
    const val SAY_NOT_PICKED = "고르지 않아 요청이 끝났어요."

    // 거래 요청 (S-20~S-29)
    const val REQUEST_TITLE = "거래 요청"
    fun peerDeals(n: Int) = "상대 거래 ${n}번"
    const val ACCEPT = "수락"
    const val REJECT = "거절"
    const val ACCEPTED_WAIT = "수락했어요. 상대가 고르는 중이에요."
    const val ACCEPT_CANCEL = "수락 취소"
    fun sayRequest(name: String, m: Int) = "${eulReul(name)} 사겠다는 차가 ${m}분 거리에 있어요. 글레오에게 수락 또는 거절이라고 말해 주세요."
    fun requestNoteTitle(name: String, price: Long) = "거래 요청 · $name ${won(price)}"
    fun requestNoteText(m: Int, deals: Int) = "${m}분 거리 · 상대 거래 ${deals}번"
    const val SAY_PEER_PICKED_OTHER = "다른 분과 거래가 정해졌어요."      // 약속 8절
    const val SAY_PEER_NO_PICK = "상대가 고르지 않아 요청이 끝났어요."
    const val SAY_PEER_WITHDREW = "상대가 요청을 거뒀어요."

    // 만날 곳과 거래 끝 (M-01~M-16)
    const val MEET_TITLE = "만날 곳"
    const val SAY_NEED_DEST = "거래가 정해졌어요. 만날 곳을 정하려면 내비에 목적지를 입력해 주세요."
    const val NEAR_PLACE_GO = "가까운 곳으로 정하기"
    fun destTimeout(sec: Long) = "${sec}초 안에 넣지 않으면 가까운 곳으로 정해요."
    const val DEAL_CANCEL = "거래 취소"
    const val DEAL_FIXED = "거래가 정해졌어요."
    fun placeIs(name: String) = "만날 곳은 ${ieyo(name)}."
    const val DESC_WITH_DEST = "두 분 가는 길 사이의 트이고 사람 다니는 곳이에요"
    const val DESC_NO_DEST = "지금 위치에서 가까운 트이고 사람 다니는 곳이에요"
    const val NEED_DEST_NOTE_TITLE = "거래가 정해졌어요"
    const val NEED_DEST_NOTE_TEXT = "내비에 목적지를 입력해 주세요"
    const val HOME_DECIDING = "만날 곳을 정하는 중이에요."                  // 집 물건 거래: 목적지를 묻지 않아요
    const val PHONE_LINKED = "폰과 이어져 있어요."                          // me.linked
    const val PEER_DECIDING = "상대가 목적지를 넣는 중이에요."                // M-21
    fun peerCar(car: String) = "상대 차: $car"
    const val NAVI_OK = "내비에 넣었어요"
    const val NAVI_FAIL = "내비에 넣지 못했어요. 직접 찾아가 주세요."
    fun meetNoteTitle(name: String) = "만날 곳 · $name"
    const val SIDE_SELL = "파는 거래"
    const val SIDE_BUY = "사는 거래"
    const val PAY_DIRECT = "돈은 만나서 직접 주고받아요."
    const val MET = "만났어요"
    const val CANCEL_ASK = "거래를 취소할까요?"
    const val DEAL_KEEP = "거래 유지"
    const val SAY_PEER_CANCELLED = "상대가 거래를 취소했어요. 내비에서 만날 곳을 지웠어요."
    const val SAY_PEER_CANCELLED_SHORT = "상대가 거래를 취소했어요."          // M-16: 내비에 넣은 것이 없었을 때
    const val ASK_TITLE = "거래 어땠어요?"
    const val DID = "거래했어요"
    const val DID_NOT = "안 했어요"
    const val NOT_YET = "아직이에요"

    // 글레오 답
    const val G_ON = "거래 알림을 켰어요."
    const val G_ALREADY_ON = "이미 켜져 있어요."
    const val G_OPEN_FIRST = "로드마켓 화면을 연 뒤에 켜 주세요."      // C-20
    const val G_OFF = "거래 알림을 껐어요."
    const val G_OFF_CANCELLED = "거래 알림을 껐어요. 진행 중이던 거래는 취소했어요."
    const val G_ALREADY_OFF = "이미 꺼져 있어요."
    fun gSold(name: String, price: Long) = "${eulReul(name)} ${won(price)}에 올렸어요."
    const val G_ASK_PRICE = "얼마에 올릴까요?"
    const val G_ASK_SELL_NAME = "어떤 물건을 올릴까요?"
    fun gWanted(name: String) = "${eulReul(name)} 찾는 물건에 넣었어요."
    const val G_ASK_WANT_NAME = "어떤 물건을 찾을까요?"
    const val G_TRADE_IS_OFF = "거래 알림이 꺼져 있어요. 먼저 켜 주세요."
    const val G_NO_WANTS = "찾는 물건을 먼저 넣어 주세요."
    const val G_NOTHING_NEAR = "지금 주변에 찾으시는 물건이 없어요."
    fun gSent(nos: List<Int>) = nos.joinToString(", ") { "${it}번" } + "에 요청을 보냈어요."
    fun gNoSuch(n: Int) = "${n}번은 없어요."
    const val G_ASK_NUMBER = "몇 번에 보낼까요?"
    const val G_NO_LIST = "지금 주변 물건 목록이 없어요."
    fun gAlreadySent(n: Int) = "${n}번에는 이미 보냈어요."            // B-42
    const val G_NOWHERE_TO_SEND = "보낼 곳이 없어요."                  // B-42
    const val G_ASK_PICK_NUMBER = "몇 번으로 할까요?"                  // B-50
    const val G_ASK_CHECK_NUMBER = "몇 번을 물으시나요?"               // B-52
    fun gPicked(n: Int) = "${n}번으로 정했어요."
    fun gNotAccepted(n: Int) = "${n}번은 수락하지 않았어요."
    fun gStillNear(n: Int, m: Int) = "${n}번은 ${m}분 거리에 있어요."
    fun gNotNear(n: Int) = "${n}번은 이제 주변에 없어요."
    fun gBusy(n: Int) = "${n}번은 다른 거래 중이에요."               // B-56
    const val G_ACCEPTED = "수락했어요. 상대가 고르면 알려 드릴게요."
    const val G_REJECTED = "거절했어요."
    const val G_ALREADY_ACCEPTED = "이미 수락했어요."                  // S-34
    const val G_NO_REQUEST = "받은 거래 요청이 없어요."
    const val G_CANCELLED = "거래를 취소했어요."
    const val G_NOTHING_TO_CANCEL = "진행 중인 거래가 없어요."
    const val G_DONE = "거래를 마쳤어요."
    const val G_NOT_DONE = "알겠어요. 물건은 그대로 둘게요."
    const val G_NOT_YET = "알겠어요. 만날 곳은 그대로예요."
    const val G_NOTHING_TO_ANSWER = "지금은 답할 거래가 없어요."

    // ───── 등록·발견 단계 (약속 6절, 8절). "임의"는 약속에 없어 정한 문구예요 ─────
    fun eeGa(w: String) = w + if (hasBatchim(w)) "이" else "가"
    fun stars(r: Double?) = r?.let { String.format(Locale.KOREA, "%.1f", it) } ?: "아직 없음"
    fun sellerLine(nick: String, deals: Int, r: Double?) = "$nick · 거래 ${deals}번 · 별점 ${stars(r)}"
    fun buyerLine(nick: String, r: Double?, m: Int) = "$nick · 별점 ${stars(r)} · ${m}분 거리"
    fun sayRequestBy(nick: String, name: String, m: Int) = "${nick}님이 ${eulReul(name)} 사겠다고 해요. ${m}분 거리예요. 글레오에게 수락 또는 거절이라고 말해 주세요."
    const val LOC_CAR = "차에 있음"
    const val LOC_HOME = "집에 있음"
    fun locBadge(loc: String) = if (loc == "home") LOC_HOME else LOC_CAR
    fun statusBadge(status: String, hidden: Boolean) = when (status) { "deal" -> "거래 중"; "sold" -> "판매 완료"; else -> if (hidden) "숨김" else "판매 중" }   // 상태가 먼저, 숨김은 판매 중일 때만
    const val SELL_EDIT = "수정"
    const val SELL_EDIT_TITLE = "물건 수정"                                   // 임의
    const val SAVE = "저장"                                                   // 임의
    const val HIDE = "숨기기"
    const val UNHIDE = "다시 보이기"
    const val TO_SOLD = "판매 완료로"
    const val TO_SALE = "다시 판매 중으로"
    const val FIELD_DESC = "간단한 설명"
    const val HOME_HERE = "지금 있는 곳을 집으로 정하기"
    fun homeIs(gu: String) = "집: $gu"
    const val PHONE_ANSWERS = "판매자가 폰으로 답해요."
    const val ALERT_OFF = "알림 끄기"
    const val ALERT_ON = "알림 켜기"
    const val ALERT_IS_OFF = "키워드 알림이 꺼져 있어요."
    const val BROWSE = "주변 물건 보기"
    const val NEARBY_EMPTY = "지금 주변에 물건이 없어요."                     // 임의
    const val G_NOTHING_AROUND = "지금 주변에 물건이 없어요."                 // 임의
    fun noneAround(k: String) = "지금 주변에 ${eeGa(k)} 없어요."
    fun aroundItem(o: Market.Offer) = (if (o.home && o.gu.isNotEmpty()) "${o.gu}에" else "${o.minutes}분 거리에") + " ${o.item} ${priceOrShare(o.price)}" + (if (o.price == 0L) "이" else "이") + " 있어요."
    const val G_LOOKING = "주변 물건을 살펴볼게요."                            // 임의
    fun priceOrShare(p: Long) = won(p)
    const val SHARE = "나눔"
    // 급구
    const val URGENT_POST = "급구 올리기"
    const val FIELD_URGENT_DESC = "원하는 물건의 특징"
    fun urgentMine(name: String) = "급구 올리는 중 · $name"
    const val URGENT_CANCEL = "급구 내리기"
    fun urgentTitle(name: String) = "급구 · $name"
    fun urgentText(desc: String, m: Int, nick: String) = listOf(desc, "${m}분 거리", nick).filter { it.isNotEmpty() }.joinToString(" · ")
    const val URGENT_SELL = "판매하기"
    fun sayUrgent(name: String) = "주변에서 ${eulReul(name)} 급하게 찾는 분이 있어요."
    const val URGENT_REPLIED = "급구에 판매하겠다고 답했어요."                 // 임의
    fun sayUrgentOffer(nick: String, name: String, p: Long) = if (p == 0L) "${nick}님이 ${eulReul(name)} 나눠 주겠다고 해요." else "${nick}님이 ${eulReul(name)} ${won(p)}에 팔겠다고 해요."
    const val URGENT_OFFERS_TITLE = "급구에 온 답"
    fun cardUrgentOffers(n: Int) = "급구에 온 답 ${n}개 · 보기"               // 임의
    const val URGENT_DRIVING_1 = "달리는 중에는 말로 올려요."
    const val URGENT_DRIVING_2 = "“글레오, 로드마켓 앱으로 급구 올려 줘, 유아용 카시트”"
    const val URGENT_SELL_DRIVING = "달리는 중에는 말로 답해요. “글레오, 급구에 카시트 2만 원에 판매해 줘”"   // 임의
    const val URGENT_MISSING = "이름을 넣어 주세요."                            // 임의
    const val URGENT_SELL_MISSING = "상품명과 값을 넣어 주세요."                // 임의
    // 경로 추천·드라이브 코스
    fun routeText(label: String, m: Int, sell: Boolean = false) = "$label ${if (sell) "찾는 분이" else "판매자가"} 많은 길이 있어요. ${m}분 더 걸려요. 이 길로 갈까요?"
    fun courseText(title: String, m: Int) = "$title 코스가 있어요. ${m}분 더 걸려요. 이 길로 갈까요?"
    const val ROUTE_GO = "이 길로 가기"
    const val ROUTE_NO = "괜찮아요"
    const val NAVI_FAIL_SHORT = "내비에 넣지 못했어요."
    const val G_NO_COURSE = "지금은 추천할 코스가 없어요."
    const val G_COURSE_LOOK = "코스를 찾아볼게요."
    // 글레오 (임의)
    const val G_NEED_SERVER = "지금은 쓸 수 없어요."
    const val G_URGENT_ASK_NAME = "어떤 물건을 급하게 찾을까요?"
    fun gUrgentPosted(name: String) = "${name} 급구를 올렸어요."
    const val G_URGENT_CANCELLED = "급구를 내렸어요."
    const val G_NO_URGENT = "올린 급구가 없어요."
    const val G_NO_URGENT_IN = "받은 급구가 없어요."
    const val G_ASK_URGENT_PRICE = "얼마에 팔까요? 나눔이라고 해도 돼요."
    fun gUrgentSold(name: String, p: Long) = "급구에 ${eulReul(name)} ${priceOrShare(p)}" + (if (p == 0L) "으로" else "에") + " 판매하겠다고 답했어요."
    const val G_NO_ROUTE = "지금 답할 길 추천이 없어요."
    const val G_ROUTE_YES = "이 길로 갈게요."
    const val G_ROUTE_NO = "알겠어요."
    // 만남·완료 (약속 9절)
    fun myEta(place: String, m: Int) = "$place, ${m}분 뒤 도착"
    fun peerEta(m: Int) = "상대는 ${m}분 뒤 도착해요."
    const val PEER_FROM_HOME = "판매자는 집에서 나와요."
    const val MAP_PLACE = "만날 곳"
    const val MAP_ME = "나"
    const val MAP_PEER = "상대"
    const val GIVE_STARS = "상대에게 별점을 주세요"
}
