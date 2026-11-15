package ai.roadmarket.app

import android.content.Context
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject

/**
 * 「거래 내역」 탭 (요건표 H-01~H-08).
 * 진행 중인 거래(받은 거래 요청, 정해진 거래)는 Market의 상태를 그대로 보여 주고, 줄을 누르면 거래 진행 화면(REQUEST·MEET·ASK)이 열려요.
 * 지난 거래는 서버가 차에 주지 않아서 앱이 정해진 거래가 끝나는 순간 "store"에 한 줄씩 쌓아요 (H-07).
 */
object Deals {
    /** 지난 거래 한 줄. result는 아래 네 값 중 하나. stars는 내가 준 별점(안 줬으면 null). */
    class Past(val at: Long, val selling: Boolean, val item: String, val price: Long, val nick: String, val result: String, val stars: Int?)

    const val DONE = "done"                       // 거래했어요 (M-10)
    const val NOT_DONE = "not_done"               // 안 했어요 (M-11)
    const val CANCELLED = "cancelled"             // 내가 취소 (M-15, 거래 알림 끄기 C-16)
    const val PEER_CANCELLED = "peer_cancelled"   // 상대가 취소 (M-16)
    private const val MAX = 30                    // 의뢰 값: 최근 30개까지

    val past = ArrayList<Past>()                  // 맨 앞이 가장 최근
    /** 「지난 거래」 상세에 연 줄 */
    var opened: Past? = null
    private lateinit var app: Context

    fun init(c: Context) {
        app = c
        past.clear()
        val a = JSONArray(c.getSharedPreferences("store", Context.MODE_PRIVATE).getString("past_deals", "[]"))
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            past.add(Past(o.optLong("at"), o.optBoolean("selling"), o.optString("item"), o.optLong("price"), o.optString("nick", ""),
                o.optString("result"), if (o.has("stars")) o.optInt("stars") else null))
        }
    }

    /** 정해진 거래가 끝나는 순간 한 번 불려요 (Market.endDeal). */
    fun record(d: Market.Deal, result: String) {
        past.add(0, Past(System.currentTimeMillis(), d.selling, d.itemName, d.price, d.peerNick, result, if (result == DONE || result == NOT_DONE) d.stars else null))
        save()
    }

    /** D-33: 데모 명령 history. 시연용 지난 거래 8건을 넣어요. 같은 줄(물건·상대·결과)이 이미 있으면 건너뛰어요. */
    fun demoHistory() {
        val now = System.currentTimeMillis()
        val h = 3_600_000L
        val rows = listOf(
            Past(now - 2 * h, false, "주니어 카시트", 30_000, "이용자 2002", DONE, 5),
            Past(now - 26 * h, true, "보온 텀블러 세트", 12_000, "이용자 4815", DONE, 4),
            Past(now - 30 * h, false, "차량용 거치대", 8_000, "이용자 2004", NOT_DONE, null),
            Past(now - 3 * 24 * h, true, "캠핑 의자 2개", 25_000, "이용자 3307", DONE, null),
            Past(now - 4 * 24 * h, false, "2채널 블랙박스", 70_000, "이용자 2008", PEER_CANCELLED, null),
            Past(now - 6 * 24 * h, false, "차량용 무선 청소기", 35_000, "이용자 2006", DONE, 5),
            Past(now - 9 * 24 * h, true, "휴대용 타이어 공기주입기", 28_000, "이용자 5172", CANCELLED, null),
            Past(now - 13 * 24 * h, false, "접이식 유모차", 90_000, "이용자 2009", DONE, 4),
        )
        val have = past.map { Triple(it.item, it.nick, it.result) }.toSet()
        val add = rows.filter { Triple(it.item, it.nick, it.result) !in have }
        if (add.isEmpty()) return
        past.addAll(add)
        past.sortByDescending { it.at }
        save()
    }

    private fun save() {
        while (past.size > MAX) past.removeAt(past.size - 1)
        val a = JSONArray()
        past.forEach { x ->
            a.put(JSONObject().put("at", x.at).put("selling", x.selling).put("item", x.item).put("price", x.price).put("nick", x.nick).put("result", x.result)
                .also { o -> x.stars?.let { o.put("stars", it) } })
        }
        app.getSharedPreferences("store", Context.MODE_PRIVATE).edit().putString("past_deals", a.toString()).apply()
    }

    /** 진행 중인 거래가 지금 어느 단계인지 (H-02). 진행 중이 아니면 null. */
    fun stage(): String? {
        val d = Market.deal
        val r = Market.incoming
        return when {
            d != null -> when (d.stage) {
                Market.Stage.WAIT_DEST, Market.Stage.WAIT_PEER -> Texts.STAGE_PLACE
                Market.Stage.MEETING -> if (d.deferred) Texts.STAGE_CHECK else Texts.STAGE_MEETING
                Market.Stage.ASK -> Texts.STAGE_ASK
            }
            r != null -> if (r.accepted) Texts.ACCEPTED else Texts.STAGE_REQUEST
            else -> null
        }
    }

    /** 진행 중 줄을 누르면 열 화면 (그 거래의 상세 = 거래 진행 화면). */
    fun liveScreen(): Screen? {
        val d = Market.deal
        return when {
            d != null -> if (d.stage == Market.Stage.ASK) Screen.ASK else Screen.MEET
            Market.incoming != null -> Screen.REQUEST
            else -> null
        }
    }
}

// ───────── 화면 (MainActivity의 공통 부품을 그대로 써요) ─────────

/** H-03: 누를 수 있는 목록 줄. 바탕은 다른 목록 줄과 같은 공식 dim/dark/primary, 눌림은 공식 button/basic/pressed. */
private fun MainActivity.dealRow(name: String, price: Long, selling: Boolean, state: String, sub: String?, tap: () -> Unit) = column().apply {
    background = pressable(p.surface, p.lightPressed, Theme.ROW_RADIUS)
    setPadding(Theme.CARD_PAD, Theme.CARD_PAD, Theme.CARD_PAD, Theme.CARD_PAD)
    minimumHeight = Theme.ROW_MIN_HEIGHT
    isSoundEffectsEnabled = false
    setOnClickListener { tap() }
    addView(nameAndPrice(name, price), wide())
    addView(small(Theme.GAP / 2))
    addView(rowOf().apply {
        addView(badge(if (selling) Texts.SIDE_SELL else Texts.SIDE_BUY), wrap())
        addView(gap(Theme.GAP / 2))
        addView(badge(state), wrap())
    }, wide())
    if (!sub.isNullOrEmpty()) { addView(small()); addView(text(sub, Theme.BODY_SMALL, p.textSub), wide()) }
}

/** H-01, H-02, H-03: 「거래 내역」 목록. 위에 진행 중인 거래(있을 때만), 아래에 지난 거래. */
internal fun MainActivity.dealsScreen(): LinearLayout {
    val c = column()
    val stage = Deals.stage()
    val target = Deals.liveScreen()
    if (stage == null && Deals.past.isEmpty()) c.addView(text(Texts.DEALS_EMPTY, Theme.TITLE, p.text), wide())
    if (stage != null && target != null) {
        val d = Market.deal
        val r = Market.incoming
        val row = if (d != null) dealRow(d.itemName, d.price, d.selling, stage, d.peerNick) { Ui.screen = target; Ui.overlay = null; render() }
            else dealRow(r!!.sell.name, r.sell.price, true, stage, r.nick) { Ui.screen = target; Ui.overlay = null; render() }
        c.addView(text(Texts.DEALS_LIVE, Theme.BODY_SMALL, p.textSub), wide())
        c.addView(small(Theme.GAP / 2))
        c.addView(row, wide())
        c.addView(gap())
    }
    if (Deals.past.isNotEmpty()) {
        c.addView(text(Texts.DEALS_PAST, Theme.BODY_SMALL, p.textSub), wide())
        for (x in Deals.past) {
            c.addView(small(Theme.GAP / 2))
            c.addView(dealRow(x.item, x.price, x.selling, Texts.pastResult(x.result), Texts.pastLine(x.nick, x.at)) {
                Deals.opened = x; Ui.screen = Screen.DEAL_PAST; Ui.overlay = null; render()
            }, wide())
        }
    }
    return page(listOf(title(Texts.MENU_DEALS)), c)
}

/** H-05: 지난 거래 상세. 읽기만 해요. */
internal fun MainActivity.pastScreen(): LinearLayout {
    val x = Deals.opened ?: return column()
    val c = column()
    val info = card()
    info.addView(text(if (x.selling) Texts.SIDE_SELL else Texts.SIDE_BUY, Theme.BODY_SMALL, p.textSub))
    info.addView(gap())
    info.addView(nameAndPrice(x.item, x.price, Theme.TITLE), wide())
    if (x.nick.isNotEmpty()) { info.addView(gap()); info.addView(text(x.nick, Theme.TITLE_BOLD, p.text)) }
    info.addView(gap())
    info.addView(text(Texts.addedAgo(x.at), Theme.BODY_SMALL, p.textSub))
    c.addView(info, wide())
    c.addView(gap())
    c.addView(text(Texts.pastResult(x.result), Theme.TITLE_BOLD, p.text), wide())
    if (x.result == Deals.DONE || x.result == Deals.NOT_DONE) {
        c.addView(small())
        c.addView(text(Texts.myStars(x.stars), Theme.BODY_LARGE, p.text), wide())
    }
    return page(withListButton(listOf(title(Texts.DEALS_PAST))), c)
}

/** H-06: 거래 상세(진행 화면 포함) 맨 위 오른쪽에 「목록」. 누르면 「거래 내역」 목록으로 가요. 거래는 그대로예요. */
internal fun MainActivity.withListButton(head: List<View>): List<View> {
    val left = column()
    head.forEach { left.addView(it, wide()) }
    return listOf(rowOf().apply {
        addView(left, grow())
        addView(gap())
        addView(button(Texts.TO_LIST) { Ui.screen = Screen.DEALS; Ui.overlay = null; render() }, LinearLayout.LayoutParams(WRAP_CONTENT, Theme.BUTTON_LARGE.height))
    })
}
