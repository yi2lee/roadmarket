package ai.roadmarket.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/** 글레오가 보낸 일을 받아 바로 답해요. 답은 글레오가 말해요. (요건표 6절) */
class GleoReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val fn = (i.action ?: return).substringAfterLast('.')
        val p = try { JSONObject(i.getStringExtra("ACTION_PARAM") ?: "{}") } catch (e: Exception) { JSONObject() }
        val r = when (fn) {
            "ADD_ITEM" -> Gleo.addItem(p)
            "TRADE_AND_NEARBY" -> Gleo.tradeAndNearby(p)
            "DEAL_REQUEST" -> Gleo.dealRequest(p)
            else -> return
        }
        val res = JSONObject().put("functionName", fn).put("status", r.status).put("message", r.message)
            .put("mode", "voice").put("data", JSONArray().toString())
        c.sendBroadcast(Intent("ai.pleos.playground.intent.action.APP_ACTION_RESPONSE").putExtra("packageName", c.packageName).putExtra("response", res.toString()))
        Log.i("ROADMARKET_GLEO", "${r.status} ${r.message}")
    }
}

object Gleo {
    class Reply(val status: String, val message: String)

    private fun ok(m: String) = Reply("success", m)
    private fun ask(m: String) = Reply("asking", m)
    /** 요건표에 답 문장이 없는 경우: 아무 일도 하지 않고 문장 없이 실패로 답해요. */
    private fun none() = Reply("fail", "")

    // 되물은 뒤 이어지는 말을 잇기 위한 기억
    private var itemKind = ""
    private var itemName = ""
    private var itemPrice = 0L
    private var itemDesc = ""
    private var itemLoc = ""
    private var urgentAsk = ""         // 되물은 급구 일: "post"(이름), "sell"(값)
    private var urgentName = ""
    private var urgentDesc = ""
    private var askedFor = ""          // 번호를 되물은 일: "send"(B-42), "choose"(B-50), "check"(B-52)

    private fun str(p: JSONObject, k: String) = p.optString(k, "").trim()

    // ① 물건 올리기·찾기 (S-09, S-10, B-08, B-09)
    fun addItem(p: JSONObject): Reply {
        askedFor = ""
        val kind = str(p, "kind").ifEmpty { itemKind }
        var name = Market.cutName(str(p, "name"))
        var price = parsePrice(str(p, "price")) ?: 0L
        var desc = str(p, "desc").take(Config.DESC_MAX)
        var loc = str(p, "loc")
        if (kind == itemKind) {
            if (name.isEmpty()) name = itemName
            if (price <= 0) price = itemPrice
            if (desc.isEmpty()) desc = itemDesc
            if (loc.isEmpty()) loc = itemLoc
        }
        itemKind = ""; itemName = ""; itemPrice = 0; itemDesc = ""; itemLoc = ""
        when (kind) {
            "sell" -> {
                if (name.isEmpty() || price <= 0) {                                             // S-10: 둘 다 없으면 이름부터
                    itemKind = kind; itemName = name; itemPrice = price; itemDesc = desc; itemLoc = loc
                    return ask(if (name.isEmpty()) Texts.G_ASK_SELL_NAME else Texts.G_ASK_PRICE)
                }
                if (price > Config.PRICE_MAX) return ok(Texts.PRICE_TOO_HIGH)                   // 10억 원을 넘으면 올리지 않아요 (서버가 거절해요)
                if (Market.sellsFull()) return ok(Texts.ITEMS_FULL)
                Market.addSell(name, price, desc = desc, loc = if (loc == "home") "home" else "car")
                return ok(Texts.gSold(name, price))
            }
            "want" -> {
                if (name.isEmpty()) { itemKind = kind; return ask(Texts.G_ASK_WANT_NAME) }
                if (Market.hasWant(name)) return ok(Texts.WANT_DUP)
                if (Market.wantsFull()) return ok(Texts.ITEMS_FULL)
                Market.addWant(name, byVoice = true)
                return ok(Texts.gWanted(name))
            }
        }
        return none()
    }

    // ② 거래 알림·주변 물건 (C-05, C-07, C-09, B-26, B-27, B-52)
    fun tradeAndNearby(p: JSONObject): Reply {
        itemKind = ""
        var action = str(p, "action")
        val raw = str(p, "number")
        if (action.isEmpty() && askedFor == "check" && numbers(raw).isNotEmpty()) action = "check"
        if (action.isEmpty() && urgentAsk == "post") action = "urgent"
        if (action.isEmpty() && urgentAsk == "sell") action = "urgent_sell"
        askedFor = ""
        val ua = urgentAsk; urgentAsk = ""
        when (action) {
            "on" -> {
                if (Market.tradeOn) return ok(Texts.G_ALREADY_ON)
                if (!Ui.front) return ok(Texts.G_OPEN_FIRST)                                    // C-20: 화면이 보일 때만 켜요 (나눠 쓰기 포함)
                Market.turnOn(); return ok(Texts.G_ON)                                          // C-05
            }
            "off" -> {
                if (!Market.tradeOn) return ok(Texts.G_ALREADY_OFF)
                val busy = Market.inProgress
                Market.turnOff()
                return ok(if (busy) Texts.G_OFF_CANCELLED else Texts.G_OFF)                    // C-09, C-07
            }
            "list" -> {
                val list = Market.nearby
                if (list != null) {                                                             // B-26: 사라진 줄은 빼고
                    val rows = list.rows.filter { !it.gone }
                    return ok(if (rows.isEmpty()) Texts.G_NOTHING_NEAR else Market.nearbySentence(rows))
                }
                if (!Market.tradeOn) return ok(Texts.G_TRADE_IS_OFF)                            // B-27
                if (Market.wants.isEmpty()) return ok(Texts.G_NO_WANTS)
                return ok(Texts.G_NOTHING_NEAR)
            }
            "browse", "ask" -> {                                                               // 약속 7절 ②
                if (!Market.serverMode) return ok(Texts.G_NEED_SERVER)
                if (!Market.tradeOn) return ok(Texts.G_TRADE_IS_OFF)
                Market.askBrowse(if (action == "ask") str(p, "keyword") else "")
                return ok(Texts.G_LOOKING)
            }
            "course" -> {
                if (!Market.serverMode) return ok(Texts.G_NEED_SERVER)
                Market.askCourse(); return ok(Texts.G_COURSE_LOOK)
            }
            "urgent" -> {
                if (!Market.serverMode) return ok(Texts.G_NEED_SERVER)
                if (!Market.tradeOn) return ok(Texts.G_TRADE_IS_OFF)
                var name = Market.cutName(str(p, "name")); var desc = str(p, "desc")
                if (ua == "post") { if (desc.isEmpty()) desc = urgentDesc }
                if (name.isEmpty()) { urgentAsk = "post"; urgentDesc = desc; return ask(Texts.G_URGENT_ASK_NAME) }
                Market.postUrgent(name, desc)
                return ok(Texts.gUrgentPosted(name))
            }
            "urgent_cancel" -> {
                if (Market.myUrgent == null) return ok(Texts.G_NO_URGENT)
                Market.cancelUrgent(); return ok(Texts.G_URGENT_CANCELLED)
            }
            "urgent_sell" -> {                                                                  // 약속 8절: 받은 급구에 「판매하기」
                val u = Market.urgentIn ?: return ok(Texts.G_NO_URGENT_IN)
                var name = Market.cutName(str(p, "name")); var desc = str(p, "desc")
                val pr = str(p, "price")
                var price = if (pr.contains("나눔") || pr.contains("무료")) 0L else parsePrice(pr) ?: -1L
                if (ua == "sell") { if (name.isEmpty()) name = urgentName; if (desc.isEmpty()) desc = urgentDesc }
                if (name.isEmpty()) name = u.name
                if (price < 0) { urgentAsk = "sell"; urgentName = name; urgentDesc = desc; return ask(Texts.G_ASK_URGENT_PRICE) }
                if (price > Config.PRICE_MAX) return ok(Texts.PRICE_TOO_HIGH)
                Market.replyUrgent(name, desc, price)
                return ok(Texts.gUrgentSold(name, price))
            }
            "check" -> {                                                                        // B-52
                if (Market.nearby == null) return ok(Texts.G_NO_LIST)
                val no = numbers(raw).firstOrNull() ?: run { askedFor = "check"; return ask(Texts.G_ASK_CHECK_NUMBER) }
                val r = Market.row(no) ?: return ok(Texts.gNoSuch(no))
                return ok(if (r.gone) Texts.gNotNear(no) else Texts.gStillNear(no, r.car.minutes))
            }
        }
        return none()
    }

    // ③ 거래 요청과 답 (B-40~B-42, B-49, B-50, S-22, S-23, S-28, S-32, S-34, S-35, B-54, M-10~M-12, M-15, M-18, M-19)
    fun dealRequest(p: JSONObject): Reply {
        itemKind = ""
        val raw = str(p, "numbers")
        var action = str(p, "action")
        if (action.isEmpty() && (askedFor == "send" || askedFor == "choose") && (numbers(raw).isNotEmpty() || (askedFor == "send" && isAll(raw)))) action = askedFor
        askedFor = ""
        when (action) {
            "send" -> {                                                                         // B-40, B-41, B-42
                val list = Market.nearby ?: return ok(Texts.G_NO_LIST)
                val nos = numbers(raw)
                if (nos.isEmpty()) {
                    if (!isAll(raw)) { askedFor = "send"; return ask(Texts.G_ASK_NUMBER) }
                    val sent = Market.sendAll()
                    return ok(if (sent.isEmpty()) Texts.G_NOWHERE_TO_SEND else Texts.gSent(sent))
                }
                // 하나라도 안 맞으면 아무 데도 보내지 않고 안 맞는 번호의 문장만 말해요
                val wrong = nos.mapNotNull { no ->
                    val r = list.rows.firstOrNull { it.no == no }
                    when {
                        r == null -> Texts.gNoSuch(no)
                        r.gone -> Texts.gNotNear(no)
                        r.sent -> Texts.gAlreadySent(no)
                        r.busy -> Texts.gBusy(no)                                               // B-56: 보내지 않아요
                        else -> null
                    }
                }
                if (wrong.isNotEmpty()) return ok(wrong.joinToString(" "))
                nos.forEach { Market.send(it) }
                return ok(Texts.gSent(nos))
            }
            "choose" -> {                                                                       // B-49, B-50
                if (Market.nearby == null) return ok(Texts.G_NO_LIST)
                val no = numbers(raw).firstOrNull() ?: run { askedFor = "choose"; return ask(Texts.G_ASK_PICK_NUMBER) }
                val r = Market.row(no) ?: return ok(Texts.gNoSuch(no))
                if (r.gone || r.state != Market.RowState.ACCEPTED) return ok(Texts.gNotAccepted(no))
                Market.pick(no)
                return ok(Texts.gPicked(no))
            }
            "accept", "decline" -> {
                val r = Market.incoming ?: return ok(Texts.G_NO_REQUEST)                         // S-32
                if (r.accepted) {                                                               // S-34
                    if (action == "accept") return ok(Texts.G_ALREADY_ACCEPTED)
                    Market.cancelAccept(); return ok(Texts.G_CANCELLED)
                }
                if (action == "accept") { Market.acceptRequest(); return ok(Texts.G_ACCEPTED) }  // S-22
                Market.rejectRequest(); return ok(Texts.G_REJECTED)                              // S-23
            }
            "cancel" -> {
                if (!Market.inProgress) return ok(Texts.G_NOTHING_TO_CANCEL)                    // M-18
                val d = Market.deal
                if (d != null) { Market.cancelDeal(); return ok(Texts.G_CANCELLED) }           // M-15
                val r = Market.incoming
                if (r != null) {
                    if (!r.accepted) { Market.rejectRequest(); return ok(Texts.G_REJECTED) }    // S-35
                    Market.cancelAccept(); return ok(Texts.G_CANCELLED)                         // S-28
                }
                Market.withdrawRequests(); return ok(Texts.G_CANCELLED)                         // B-54
            }
            "route_yes", "route_no" -> {                                                        // 약속 7절 ③
                if (Market.routeOffer == null) return ok(Texts.G_NO_ROUTE)
                Market.answerRoute(action == "route_yes")
                return ok(if (action == "route_yes") Texts.G_ROUTE_YES else Texts.G_ROUTE_NO)
            }
            "done", "not_done", "not_yet" -> {
                if (Market.deal?.stage != Market.Stage.ASK) return ok(Texts.G_NOTHING_TO_ANSWER) // M-19
                if (action == "not_yet") { Market.notYet(); return ok(Texts.G_NOT_YET) }        // M-12
                val did = action == "done"
                Market.answerDeal(did)
                return ok(if (did) Texts.G_DONE else Texts.G_NOT_DONE)
            }
        }
        return none()
    }

    private fun isAll(s: String) = s.contains("all", true) || s.contains("다") || s.contains("모두") || s.contains("전부")
    private fun numbers(s: String): List<Int> = Regex("\\d+").findAll(s).mapNotNull { it.value.toIntOrNull() }.distinct().toList()

    /** "10000", "10,000원", "만 원", "3만 5천", "삼만오천" 같은 값을 숫자로. */
    fun parsePrice(s: String): Long? {
        val t = s.replace(" ", "").replace(",", "").removeSuffix("원")
        if (t.isEmpty()) return null
        t.toLongOrNull()?.let { return it }
        val digit = mapOf('일' to 1L, '이' to 2L, '삼' to 3L, '사' to 4L, '오' to 5L, '육' to 6L, '칠' to 7L, '팔' to 8L, '구' to 9L)
        val small = mapOf('십' to 10L, '백' to 100L, '천' to 1000L)
        var total = 0L; var section = 0L; var num = 0L; var hasNum = false
        for (ch in t) {
            when {
                ch.isDigit() -> { num = num * 10 + (ch - '0'); hasNum = true }
                ch in digit -> { num = digit[ch]!!; hasNum = true }
                ch in small -> { section += (if (hasNum) num else 1L) * small[ch]!!; num = 0; hasNum = false }
                ch == '만' -> { val v = section + (if (hasNum) num else 0L); total += (if (v == 0L) 1L else v) * 10_000L; section = 0; num = 0; hasNum = false }
                else -> return null
            }
        }
        return total + section + (if (hasNum) num else 0L)
    }
}
