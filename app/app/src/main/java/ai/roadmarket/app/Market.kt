package ai.roadmarket.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

enum class Screen { HOME, SELLS, WANTS, SETTINGS, NEARBY, REQUEST, MEET, ASK, DEALS, DEAL_PAST }   // DEALS·DEAL_PAST: 거래 내역 탭 (Deals.kt)

/**
 * 상태 기계. 버튼, 글레오, 서버가 보낸 일이 모두 여기의 같은 함수를 불러요.
 * 화면은 이 상태를 그릴 뿐이에요. 모든 함수는 메인 스레드에서 불려요.
 * 상대 차는 서버를 거쳐 와요 (7-0). 20초·60초 타이머는 서버에서만 세요.
 */
object Market {
    /** 파는 물건 (약속 1절). id는 서버가 붙인 "s…"(서버가 목록을 가질 때) 또는 앱이 붙인 숫자 글자(옛 서버). */
    class Sell(val id: String, var name: String, var price: Long, var photo: String = "",      // photo: 폰에서 넣은 사진 id (C-24, 없으면 "")
               var desc: String = "", var loc: String = "car", var status: String = "sale", var hidden: Boolean = false)

    /** 서버가 알려 준 주변 물건 하나. key는 "차:물건" (가짜 차는 "fake:s2"). */
    class Offer(val key: String, val item: String, val price: Long, var minutes: Int, val deals: Int, val photo: String = "", val busy: Boolean = false,
                val desc: String = "", val loc: String = "car", val gu: String = "", val nick: String = "", val rating: Double? = null) {
        val home get() = loc == "home"
        fun copy() = Offer(key, item, price, minutes, deals, photo, false, desc, loc, gu, nick, rating)
    }

    enum class RowState { NONE, WAITING, ACCEPTED, REJECTED, NO_ANSWER, CANCELLED }
    class Row(val no: Int, val car: Offer) {
        var state = RowState.NONE
        var sent = false
        var gone = false
        var busy = false                // B-56: 내가 안 보낸 차가 다른 거래를 시작함. 이 목록에서는 되살아나지 않아요
    }
    /** browse = 「주변 물건 보기」로 직접 연 목록 (서버의 browse). 아니면 키워드 알림 목록(near). */
    class Nearby(val id: Long, val rows: List<Row>, val browse: Boolean = false)

    class Incoming(val req: String, val sell: Sell, val minutes: Int, val deals: Int, val photo: String = "", val nick: String = "", val rating: Double? = null) {
        var accepted = false
    }

    enum class Stage { WAIT_DEST, WAIT_PEER, MEETING, ASK }
    class Deal(val id: String, val selling: Boolean, val itemName: String, val price: Long, val peerDeals: Int, val peerCar: String, val sellId: String?) {
        var placeDesc = ""
        var home = false                // 서버가 deal에 "home": true를 줌 (집 물건). 목적지를 묻지 않아요
        var stars: Int? = null          // 약속 9절: 상대에게 주는 별점 0~5 (안 골라도 돼요)
        var gotOutSent = false          // 약속 9절: got_out은 한 번만              // 서버가 만날 곳 한 줄 설명을 주면 그 글 (집 물건: "판매자 집 근처의 …")
        var stage = Stage.WAIT_DEST
        var withDest = false            // 내 목적지가 있었는지 (M-21: 한 줄 설명은 이것으로 정해요)
        var askedDest = false           // M-02를 거쳤는지
        var place: Config.Place? = null
        var inserted: Navi.Inserted? = null
        var naviFailed = false
        var destTimer: Runnable? = null
        var doorOpen = false
        var doorCycles = 0
        var peerNick = ""              // H-07: 지난 거래에 남길 상대 닉네임 (요청·줄·meet에서 알게 된 것)
        var deferred = false            // H-04: 「아직이에요」로 미룸 → 거래 내역에 "확인 필요"
    }

    private val h = Handler(Looper.getMainLooper())
    private lateinit var app: Context

    // 앱을 다시 시작해도 남는 것 (C-15)
    val sells = ArrayList<Sell>()      // 맨 앞이 맨 위
    val wants = ArrayList<String>()
    /** 찾는 물건을 이 차에서 넣은 시각 (이름 → System.currentTimeMillis). 찾는 물건은 이름이 곧 구별 값이에요. 서버는 시각을 안 가져요. */
    private val wantTimes = HashMap<String, Long>()
    var dealCount = 0
        private set
    private var nextId = 1L
    var deal: Deal? = null             // 정해진 거래와 "거래 어땠어요?" 물음
        private set

    // 앱을 다시 시작하면 사라지는 것
    var tradeOn = false
        private set
    var nearby: Nearby? = null
        private set
    var incoming: Incoming? = null
        private set
    /** C-22: 방금 끝난 일의 이유. 다음 일이 시작되거나 거래 알림을 끄면 사라져요. */
    var justEnded: String? = null
        private set
    private val announced = HashSet<String>()
    private var nextListId = SystemClock.elapsedRealtime()
    private var holdNearbyUntil = 0L

    fun init(c: Context) {
        app = c
        Deals.init(c)
        val p = c.getSharedPreferences("store", Context.MODE_PRIVATE)
        JSONArray(p.getString("sells", "[]")).let { a ->
            for (i in 0 until a.length()) sells.add(sellFrom(a.getJSONObject(i)))
        }
        JSONArray(p.getString("wants", "[]")).let { a -> for (i in 0 until a.length()) wants.add(a.getString(i)) }
        JSONObject(p.getString("want_times", "{}")).let { o -> o.keys().forEach { k -> wantTimes[k] = o.optLong(k) } }
        dealCount = p.getInt("deals", 0)
        nextId = p.getLong("nextId", 1L)
        p.getString("deal", null)?.let { raw ->
            val o = JSONObject(raw)
            val d = Deal(o.getString("id"), o.getBoolean("selling"), o.getString("item"), o.getLong("price"), o.getInt("peerDeals"), o.getString("peerCar"),
                if (o.has("sellId")) o.getString("sellId") else null)
            d.placeDesc = o.optString("placeDesc", "")
            d.home = o.optBoolean("home", false)
            d.stage = Stage.valueOf(o.getString("stage"))
            d.withDest = o.getBoolean("withDest")
            d.askedDest = o.optBoolean("askedDest", false)
            if (o.has("placeName")) d.place = Config.Place(o.getString("placeName"), o.getDouble("placeLat"), o.getDouble("placeLon"))
            d.inserted = o.optString("inserted", "").let { s -> Navi.Inserted.values().firstOrNull { it.name == s } }
            d.naviFailed = o.getBoolean("naviFailed")
            d.peerNick = o.optString("peerNick", ""); d.deferred = o.optBoolean("deferred", false)
            deal = d
            tradeOn = true                                // C-15: 남아 있으면 켜진 채로 시작
            TradeService.start(app)
            if (d.stage == Stage.WAIT_DEST) waitForDestination(d)
            if (d.stage == Stage.WAIT_PEER) Link.cmd("dest", "deal" to d.id, "with" to d.withDest)   // 못 보냈을 수 있어 다시 보내요 (서버는 한 번만 받아요)
        }
        if (deal == null) Notes.clearLeftAlerts()
    }

    private fun save() {
        val s = JSONArray(); sells.forEach { s.put(sellJson(it)) }
        val w = JSONArray(); wants.forEach { w.put(it) }
        val e = app.getSharedPreferences("store", Context.MODE_PRIVATE).edit()
            .putString("sells", s.toString()).putString("wants", w.toString()).putInt("deals", dealCount).putLong("nextId", nextId)
        e.putString("want_times", JSONObject().also { o -> wantTimes.filterKeys { it in wants }.forEach { (k, v) -> o.put(k, v) } }.toString())   // 지운 물건의 시각은 버려요
        val d = deal
        if (d == null) e.remove("deal") else {
            val o = JSONObject().put("id", d.id).put("selling", d.selling).put("item", d.itemName).put("price", d.price).put("peerDeals", d.peerDeals)
                .put("peerCar", d.peerCar).put("stage", d.stage.name).put("withDest", d.withDest).put("askedDest", d.askedDest).put("naviFailed", d.naviFailed)
            d.sellId?.let { o.put("sellId", it) }
            d.inserted?.let { o.put("inserted", it.name) }
            d.place?.let { o.put("placeName", it.name).put("placeLat", it.lat).put("placeLon", it.lon) }
            o.put("placeDesc", d.placeDesc).put("home", d.home).put("peerNick", d.peerNick).put("deferred", d.deferred)
            e.putString("deal", o.toString())
        }
        e.apply()
    }

    /** 서버에 올리는 내 상태 (7-0). */
    private fun sellJson(it: Sell) = JSONObject().put("id", it.id).put("name", it.name).put("price", it.price).put("desc", it.desc).put("loc", it.loc)
        .put("status", it.status).put("hidden", it.hidden).also { o -> if (it.photo.isNotEmpty()) o.put("photo", it.photo) }

    private fun sellFrom(o: JSONObject) = Sell(o.optString("id"), o.optString("name"), o.optLong("price"), o.optString("photo", ""),
        o.optString("desc", ""), o.optString("loc", "car").ifEmpty { "car" }, o.optString("status", "sale").ifEmpty { "sale" }, o.optBoolean("hidden", false))

    fun snapshot(): JSONObject {
        val st = JSONObject().put("trade_on", tradeOn).put("busy", inProgress).put("deals", dealCount).put("driving", !Car.parked)
        Navi.destination?.let { st.put("dest", JSONArray().put(it.first).put(it.second)) }
        if (!serverMode) {                                // 옛 서버: 앱이 목록의 원본이라 올려요. 남에게 보일 것만 (약속 1절)
            val s = JSONArray(); sells.filter { it.status == "sale" && !it.hidden }.forEach { s.put(sellJson(it).apply { remove("status"); remove("hidden"); remove("desc"); remove("loc") }) }
            val w = JSONArray(); wants.forEach { w.put(it) }
            st.put("sells", s).put("wants", w)
        }
        return st
    }

    // ───── 진행 중 판정 (요건표 0-1) ─────
    val buyerWaiting: Boolean get() = nearby?.rows?.any { !it.gone && (it.state == RowState.WAITING || it.state == RowState.ACCEPTED) } == true
    val inProgress: Boolean get() = buyerWaiting || incoming != null || deal != null
    /** H-09: 받은 요청에 답하기 전이거나 거래가 정해진 뒤. 「내 주변」 목록은 보이지만 새 요청은 보내지 않아요. */
    val dealLive: Boolean get() = incoming != null || deal != null

    /** 그 물건으로 진행 중인 거래가 있는지 (S-11, S-12) */
    fun sellBusy(id: String) = incoming?.sell?.id == id || deal?.sellId == id
    /** 줄에 보일 상태: "sale" | "deal" | "sold" (숨김은 따로) */
    fun statusOf(s: Sell) = if (sellBusy(s.id)) "deal" else s.status

    // ───── 앱이 먼저 알리기 (요건표 0-4, 0-5) ─────
    private fun announce(sentence: String, screen: Screen, noteTitle: String = sentence, noteText: String? = null, buttons: Boolean = false, end: Boolean = false) {
        Voice.say(sentence)
        if (!Ui.front) Notes.alert(noteTitle, noteText, screen, buttons, end)
    }

    /** 일이 끝남: 그 일의 알림을 지우고(0-5), 홈에 이유를 남겨요(C-22). */
    private fun ended(reason: String?) {
        Notes.endTask()
        justEnded = reason
    }

    /** 새 일이 시작됨: 남은 알림과 "방금" 한 줄을 지워요 (0-5, C-22). */
    private fun started() {
        dropRoute()
        Notes.endTask()
        Notes.clearEndNotices()
        justEnded = null
    }

    // ───── 거래 알림 켜고 끄기 (C-04~C-09, C-16) ─────
    /** 처음에는 켜져 있어요. 사람이 끈 적이 있으면("store"의 trade_on=false) 꺼진 채로 둬요. 화면이 앞에 열린 뒤에 불러요(C-20). 말·소리 안내는 없어요. */
    fun autoOn() {
        if (!tradeOn && app.getSharedPreferences("store", Context.MODE_PRIVATE).getBoolean("trade_on", true)) turnOn()
    }

    private fun rememberTradeOn(on: Boolean) = app.getSharedPreferences("store", Context.MODE_PRIVATE).edit().putBoolean("trade_on", on).apply()

    fun turnOn() {
        if (tradeOn) return
        tradeOn = true
        rememberTradeOn(true)
        announced.clear()
        TradeService.start(app)
        Ui.refresh()
        Link.cmd("on")
    }

    /** C-06, C-16: 끄기. 진행 중이던 것은 모두 취소돼요. 상대에게 취소가 가요. "거래 어땠어요?" 물음만은 남아요. */
    fun turnOff() {
        if (!tradeOn) return
        closeNearby()
        incoming?.let { Link.cmd(if (it.accepted) "cancel_accept" else "reject", "req" to it.req) }
        clearIncoming()
        deal?.let { if (it.stage != Stage.ASK) { Link.cmd("cancel_deal", "deal" to it.id); endDeal(it, Deals.CANCELLED) } }
        if (deal == null) Notes.endTask()
        Notes.clearEndNotices()
        justEnded = null
        tradeOn = false
        rememberTradeOn(false)                            // 사람이 끈 것을 기억해요: 다시 열어도 꺼진 채예요
        TradeService.stop(app)
        Ui.refresh()
        Link.cmd("off")
    }

    // ───── 글레오 켜고 끄기 (설정 탭, C-26) ─────
    /** 꺼 두면 앱이 소리로 읽지 않고(Voice), 글레오가 부른 일도 하지 않아요(GleoReceiver). 기본 켜짐. 다른 값처럼 "store"에 남아요. */
    var gleoOn: Boolean
        get() = app.getSharedPreferences("store", Context.MODE_PRIVATE).getBoolean("gleo_on", true)
        set(v) { app.getSharedPreferences("store", Context.MODE_PRIVATE).edit().putBoolean("gleo_on", v).apply(); Ui.refresh() }

    // ───── 파는 물건, 찾는 물건 ─────
    fun sellsFull() = sells.size >= Config.ITEMS_MAX
    fun wantsFull() = wants.size >= Config.ITEMS_MAX
    fun cutName(name: String) = name.take(Config.NAME_MAX).trim()

    fun addSell(name: String, price: Long, photo: String = "", desc: String = "", loc: String = "car") {
        if (sellsFull()) return
        val n = cutName(name); val d = desc.take(Config.DESC_MAX).trim()
        sells.add(0, Sell((if (serverMode) "tmp" else "") + nextId++, n, price, photo, d, loc)); save(); Ui.refresh()
        if (serverMode) Link.cmd("sell_add", "name" to n, "price" to price, "desc" to d, "loc" to loc)
    }

    /** 수정 (약속 2절 sell_update). */
    fun updateSell(id: String, name: String, price: Long, desc: String, loc: String) {
        val s = sells.firstOrNull { it.id == id } ?: return
        s.name = cutName(name); s.price = price; s.desc = desc.take(Config.DESC_MAX).trim(); s.loc = loc
        save(); Ui.refresh()
        if (serverMode) Link.cmd("sell_update", "id" to id, "name" to s.name, "price" to s.price, "desc" to s.desc, "loc" to loc)
    }

    fun hideSell(id: String, hidden: Boolean) {
        val s = sells.firstOrNull { it.id == id } ?: return
        if (sellBusy(id)) return
        s.hidden = hidden; save(); Ui.refresh()
        if (serverMode) Link.cmd("sell_hide", "id" to id, "hidden" to hidden)
    }

    fun setSellStatus(id: String, status: String) {
        val s = sells.firstOrNull { it.id == id } ?: return
        if (sellBusy(id)) return
        s.status = status; save(); Ui.refresh()
        if (serverMode) Link.cmd("sell_status", "id" to id, "status" to status)
    }

    fun setWantsAlert(on: Boolean) {
        wantsAlert = on; Ui.refresh()
        Link.cmd("wants_alert", "on" to on)
    }

    fun homeHere() { Link.cmd("home_here") }

    /** C-24: 폰에서 넣은 사진을 그 물건에 달아요. */
    fun setSellPhoto(id: String, photo: String) {
        val s = sells.firstOrNull { it.id == id } ?: return
        if (s.photo == photo) return
        s.photo = photo; save(); Ui.refresh()
    }

    fun removeSell(id: String) {
        if (sellBusy(id)) return
        sells.removeAll { it.id == id }; save(); Ui.refresh()
        if (serverMode) Link.cmd("sell_remove", "id" to id)
    }

    fun hasWant(name: String) = wants.contains(name)
    /** 찾는 물건을 넣은 시각. 이 기능 전에 넣었거나 폰·서버에서 온 물건은 몰라서 null이에요. */
    fun wantAddedAt(name: String): Long? = wantTimes[name]

    /** B-04, B-08. 말로 넣었으면 맞는 물건 알림을 5초 늦춰 글레오의 답과 겹치지 않게 해요 (B-20). */
    fun addWant(name: String, byVoice: Boolean = false) {
        if (hasWant(name) || wantsFull()) return
        wantTimes[name] = System.currentTimeMillis()
        wants.add(0, name); save(); Ui.refresh()
        if (serverMode) Link.cmd("want_add", "name" to name)
        if (byVoice) {
            holdNearbyUntil = SystemClock.elapsedRealtime() + Config.VOICE_WANT_NOTICE_DELAY_MS
            h.postDelayed({ checkNearby() }, Config.VOICE_WANT_NOTICE_DELAY_MS)
        } else checkNearby()
    }

    fun removeWant(name: String) {
        wants.remove(name); save(); Ui.refresh()
        if (serverMode) Link.cmd("want_remove", "name" to name)
    }

    // ───── 사는 사람: 주변 물건 알림 (B-20~B-29) ─────
    // 서버가 목록을 가지면 맞추기도 서버가 해요 (이름·설명, 알림 끄기 포함. 약속 2절)
    private fun matches(item: String) = serverMode || wants.any { item.replace(" ", "").contains(it.replace(" ", "")) }

    /** 서버와 한 번 주고받을 때마다: 줄의 거리를 새로 쓰고, 알릴 물건이 있는지 봐요. */
    fun onPoll() {
        nearby?.rows?.forEach { r -> Link.rowMinutes[r.no]?.let { r.car.minutes = it } }
        nearby?.rows?.forEach { r -> if (!r.sent && !r.gone && !r.busy && (r.car.key in Link.busyKeys || r.no in Link.rowBusy)) r.busy = true }   // B-56: 말하지 않아요
        onBrowse()
        sayPending()
        checkNearby()
    }

    /** B-20: 조건이 맞으면 주변 물건을 알려요. (B-23, B-24, B-25, B-28도 여기서 지켜요) */
    private fun checkNearby() {
        if (!tradeOn || inProgress || nearby != null || !Link.reachable) return
        if (SystemClock.elapsedRealtime() < holdNearbyUntil) return
        val found = Link.near.filter { it.key !in announced && !it.busy && matches(it.item) }
            .sortedBy { it.minutes }.take(Config.NEARBY_MAX)
        if (found.isEmpty()) return
        found.forEach { announced.add(it.key) }
        started()
        val list = Nearby(nextListId++, found.mapIndexed { i, c -> Row(i + 1, c.copy()) })
        nearby = list
        Link.cmd("list", "list" to list.id, "rows" to JSONArray().also { a -> list.rows.forEach { a.put(JSONObject().put("no", it.no).put("key", it.car.key)) } })
        Ui.go(Screen.NEARBY)
        announce(
            nearbySentence(list.rows), Screen.NEARBY,
            Texts.nearbyNoteTitle(list.rows.size),
            list.rows.joinToString(" · ") { Texts.nearbyNoteItem(it.no, it.car.item, it.car.price) },
        )
    }

    fun nearbySentence(rows: List<Row>) =
        Texts.nearbyHead(rows.size) + " " + rows.joinToString(" ") { Texts.nearbyItem(it.no, it.car.item, it.car.price, it.car.minutes) }

    fun row(no: Int) = nearby?.rows?.firstOrNull { it.no == no }

    fun canSend(r: Row) = !r.sent && !r.gone && !r.busy

    /** 목록을 닫아요. 서버에도 알려요(남은 요청은 거둬져요). */
    private fun closeNearby() {
        val list = nearby ?: return
        if (list.rows.isNotEmpty()) Link.cmd(if (buyerWaiting) "withdraw" else "close_list", "list" to list.id)
        if (list.browse) Link.cmd("browse", "on" to false)
        nearby = null
    }

    /** B-29: 목록 닫기. */
    fun closeList() {
        if (nearby == null || buyerWaiting) return
        closeNearby()
        ended(null)
        Ui.go(Screen.HOME)
        checkNearby()
    }

    // ───── 사는 사람: 요청 보내고 고르기 (B-40~B-54) ─────

    /** B-40: 한 곳에 보내기. 답과 20초는 서버가 알려 줘요. */
    fun send(no: Int) {
        val list = nearby ?: return
        val r = row(no) ?: return
        if (!canSend(r) || dealLive) return                                    // H-09: 거래 중에는 보내지 않아요
        r.sent = true
        r.state = RowState.WAITING
        dropRoute()
        Link.cmd("send", "list" to list.id, "no" to no)
        Ui.refresh()
    }

    /** B-41: 아직 안 보낸 모든 줄에 보내기. 보낸 번호를 돌려줘요. */
    fun sendAll(): List<Int> {
        if (dealLive) return emptyList()                                       // H-09
        val nos = nearby?.rows?.filter { canSend(it) }?.map { it.no } ?: emptyList()
        nos.forEach { send(it) }
        return nos
    }

    /** B-49: 고르기. 거래가 정해졌다는 답은 서버에서 와요. */
    fun pick(no: Int) {
        val list = nearby ?: return
        val r = row(no) ?: return
        if (r.gone || r.state != RowState.ACCEPTED) return
        Link.cmd("pick", "list" to list.id, "no" to no)
    }

    /** B-54: 요청 거두기. */
    fun withdrawRequests() {
        if (!buyerWaiting) return
        closeNearby()
        ended(Texts.JUST_CANCELLED)
        Ui.go(Screen.HOME)
        checkNearby()
    }

    // ───── 파는 사람: 거래 요청 (S-20~S-33) ─────
    private fun clearIncoming() {
        incoming ?: return
        incoming = null
        Notes.clearAlert()
    }

    /** S-22: 수락. */
    fun acceptRequest() {
        val r = incoming ?: return
        if (r.accepted) return
        r.accepted = true
        Notes.clearAlert()
        Link.cmd("accept", "req" to r.req)
        Ui.refresh()
    }

    /** S-23: 거절. */
    fun rejectRequest() {
        val r = incoming ?: return
        if (r.accepted) return
        Link.cmd("reject", "req" to r.req)
        clearIncoming()
        ended(null)
        Ui.go(Screen.HOME)
        checkNearby()
    }

    /** S-28: 수락 취소. */
    fun cancelAccept() {
        val r = incoming ?: return
        if (!r.accepted) return
        Link.cmd("cancel_accept", "req" to r.req)
        clearIncoming()
        ended(Texts.JUST_CANCELLED)
        Ui.go(Screen.HOME)
        checkNearby()
    }

    /** S-26, S-27, S-29: 상대 쪽에서 요청이 끝남. 말하고 홈으로 가요. */
    private fun endIncoming(sentence: String) {
        clearIncoming()
        ended(sentence)
        Ui.go(Screen.HOME)
        announce(sentence, Screen.HOME, end = true)
        checkNearby()
    }

    // ───── 서버가 목록을 가질 때 (약속 0절, 2절 me) ─────
    /** 서버가 /sync 응답에 me를 준 적이 있음. 없으면(옛 서버) 앱이 목록을 갖는 지금 방식이에요. */
    var serverMode = false
        private set
    var wantsAlert = true
        private set
    var nick = ""
        private set
    var homeGu: String? = null
        private set
    /** 폰 계정과 이어진 차 (me.linked) */
    var linked = false
        private set
    class Urgent(val id: String, val name: String, val desc: String)
    var myUrgent: Urgent? = null
        private set
    class UrgentOffer(val no: Int, val name: String, val desc: String, val price: Long, val min: Int, val nick: String, val deals: Int, val rating: Double?)
    val urgentOffers = ArrayList<UrgentOffer>()
    private var pickedUrgent = false
    class UrgentIn(val id: String, val name: String, val desc: String, val min: Int, val nick: String)
    /** 주변에서 받은 급구 (카드 하나) */
    var urgentIn: UrgentIn? = null
        private set
    private val repliedUrgent = HashSet<String>()
    private val seenUrgent = HashSet<String>()
    class RouteOffer(val id: String, val text: String, val via: Config.Place)
    /** 받은 경로 추천·드라이브 코스 (카드 하나) */
    var routeOffer: RouteOffer? = null
        private set
    private var restoreSent = false
    private var meSig = ""

    fun rating(o: JSONObject): Double? = if (o.isNull("rating") || !o.has("rating")) null else o.optDouble("rating").takeIf { !it.isNaN() }

    /** 응답의 me. lists=false면 아직 서버에 닿지 않은 목록 명령이 있어 목록은 그대로 둬요(화면이 되돌아가 튀지 않게). */
    fun onMe(me: JSONObject, lists: Boolean) {
        serverMode = true
        wantsAlert = me.optBoolean("wants_alert", true)
        nick = me.optString("nick", "")
        linked = me.optBoolean("linked", false)
        homeGu = me.optJSONObject("home")?.optString("gu", "")
        if (me.has("deals")) dealCount = me.optInt("deals", dealCount)
        val u = me.optJSONObject("urgent")
        if (lists) {
            myUrgent = u?.let { Urgent(it.optString("id"), it.optString("name"), it.optString("desc")) }
            if (u == null) urgentOffers.clear()
            u?.optJSONArray("offers")?.let { a -> urgentOffers.clear(); for (i in 0 until a.length()) urgentOffers.add(offerFrom(a.getJSONObject(i))) }
        }
        if (me.optBoolean("restore_ok", false)) {
            // 서버가 방금 켜져 비었어요: 저장해 둔 목록을 한 번 올려요. 그때까지 목록은 저장한 것 그대로
            if (!restoreSent && (sells.isNotEmpty() || wants.isNotEmpty())) {
                restoreSent = true
                Link.cmd("restore", "sells" to JSONArray().also { a -> sells.forEach { a.put(JSONObject().put("name", it.name).put("price", it.price).put("desc", it.desc).put("loc", it.loc).put("photo", it.photo)) } },
                    "wants" to JSONArray().also { a -> wants.forEach { a.put(it) } })
            }
        } else if (lists) {
            val ns = ArrayList<Sell>(); me.optJSONArray("sells")?.let { a -> for (i in 0 until a.length()) ns.add(sellFrom(a.getJSONObject(i))) }
            val nw = ArrayList<String>(); me.optJSONArray("wants")?.let { a -> for (i in 0 until a.length()) nw.add(a.getString(i)) }
            sells.clear(); sells.addAll(ns); wants.clear(); wants.addAll(nw); save()
        }
        val sig = me.toString()
        if (sig != meSig) { meSig = sig; Ui.refresh() }
    }

    private fun offerFrom(o: JSONObject): UrgentOffer {
        val sl = o.optJSONObject("seller")
        return UrgentOffer(o.optInt("no"), o.optString("name"), o.optString("desc"), o.optLong("price"), o.optInt("min"),
            sl?.optString("nick", "") ?: "", sl?.optInt("deals") ?: 0, sl?.let { rating(it) })
    }

    // ───── 만남 (약속 9절 meet) ─────
    class Meet(val place: String, val lat: Double, val lon: Double, val myEta: Int?, val peerEta: Int?, val peerLat: Double?, val peerLon: Double?, val peerHome: Boolean)
    var meet: Meet? = null
        private set

    fun onMeet(m: JSONObject?) {
        if (m == null) { meet = null; return }
        val pl = m.optJSONObject("place") ?: run { meet = null; return }
        val pe = m.optJSONObject("peer")
        fun i(o: JSONObject?, k: String) = if (o == null || o.isNull(k) || !o.has(k)) null else o.optInt(k)
        fun d(o: JSONObject?, k: String) = if (o == null || o.isNull(k) || !o.has(k)) null else o.optDouble(k).takeIf { !it.isNaN() }
        deal?.let { d -> if (d.peerNick.isEmpty()) d.peerNick = pe?.optString("nick", "") ?: "" }   // H-07
        meet = Meet(pl.optString("name"), pl.optDouble("lat"), pl.optDouble("lon"), i(m, "my_eta_min"), i(pe, "eta_min"), d(pe, "lat"), d(pe, "lon"), pe?.optBoolean("home", false) ?: false)
    }

    // ───── 직접 여는 주변 물건 목록 (browse) ─────
    /**
     * C-01: 「내 주변」이 앞에 보이는 동안 주변 전체 목록(browse)을 열어 둬요. 번호·말(B-42, B-52)은 이 목록으로 해요.
     * 찾는 물건 알림 목록이 이미 있으면 그대로 둬요. 탭을 떠나거나 앱이 뒤로 가면, 아무 줄에도 보내지 않은 주변 전체 목록만
     * 조용히 닫아요 (목소리·"방금" 줄 없음). 닫혀 있어야 찾는 물건 알림(B-20)이 올 수 있어요.
     */
    fun showAround(on: Boolean) {
        if (!serverMode) return
        val nb = nearby
        if (on) {
            if (!tradeOn || nb != null || !Link.reachable) return   // H-08: 거래 중(incoming·deal)에도 열어요. 답 기다리는 중은 nb가 있어요
            nearby = Nearby(nextListId++, emptyList(), browse = true)
            Link.cmd("browse", "on" to true)
            onBrowse()
        } else if (nb != null && nb.browse && nb.rows.none { it.sent || it.state != RowState.NONE }) {
            closeNearby()
            checkNearby()
        }
    }

    /** 주변 전체 목록 열기 (B-62). 찾는 물건 알림 목록이 이미 있으면 그것을 보여 줘요. */
    fun openBrowse() {
        if (!tradeOn || !serverMode) return
        if (nearby != null) { Ui.go(Screen.NEARBY); return }
        if (inProgress) return
        started()
        nearby = Nearby(nextListId++, emptyList(), browse = true)
        Link.cmd("browse", "on" to true)
        Ui.go(Screen.NEARBY)
        onBrowse()
    }

    /** 아직 아무 줄에도 보내지 않았으면 서버가 준 browse로 줄을 새로 써요. 보낸 뒤에는 줄이 그대로예요. */
    private fun onBrowse() {
        val nb = nearby ?: return
        if (!nb.browse || !Link.browseSeen) return
        if (nb.rows.any { it.sent || it.state != RowState.NONE }) return
        val found = Link.browse.filter { !it.busy }.sortedBy { it.minutes }
        if (found.map { it.key } == nb.rows.map { it.car.key }) return
        val list = Nearby(nextListId++, found.mapIndexed { i, c -> Row(i + 1, c.copy()) }, browse = true)
        nearby = list
        if (list.rows.isNotEmpty())
            Link.cmd("list", "list" to list.id, "rows" to JSONArray().also { a -> list.rows.forEach { a.put(JSONObject().put("no", it.no).put("key", it.car.key)) } })
        Ui.refresh()
    }

    // 글레오 "주변에 뭐 있어?" / "주변에 카시트 있어?": 서버의 browse가 오면 앱 목소리로 읽어요
    private var sayWhat: String? = null           // "" = 다, 글자 = 그 키워드
    private var sayUntil = 0L
    private var browseForSay = false

    fun askBrowse(keyword: String) {
        sayWhat = keyword
        sayUntil = SystemClock.elapsedRealtime() + 4_000L
        if (nearby?.browse != true) { browseForSay = true; Link.cmd("browse", "on" to true) }
        else if (Link.browseSeen) sayPending()
    }

    private fun sayPending() {
        val k = sayWhat ?: return
        val late = SystemClock.elapsedRealtime() > sayUntil
        if (!Link.browseSeen && !late) return
        if (Link.browseSeen && Link.browse.isEmpty() && !late) return       // 서버가 아직 다 못 셌을 수 있어요
        sayWhat = null
        val key = k.replace(" ", "")
        val rows = Link.browse.filter { !it.busy && (key.isEmpty() || (it.item + it.desc).replace(" ", "").contains(key)) }.sortedBy { it.minutes }.take(3)
        Voice.say(if (rows.isEmpty()) (if (key.isEmpty()) Texts.G_NOTHING_AROUND else Texts.noneAround(k)) else rows.joinToString(" ") { Texts.aroundItem(it) })
        if (browseForSay && nearby?.browse != true) Link.cmd("browse", "on" to false)
        browseForSay = false
    }

    // ───── 급구 (약속 4절, 8절) ─────
    fun postUrgent(name: String, desc: String) {
        if (!serverMode || !tradeOn) return
        val n = cutName(name); val d = desc.take(Config.DESC_MAX).trim()
        myUrgent = Urgent("", n, d); urgentOffers.clear()
        Link.cmd("urgent_post", "name" to n, "desc" to d)
        Ui.refresh()
    }

    fun cancelUrgent() {
        if (myUrgent == null) return
        myUrgent = null; urgentOffers.clear()
        Link.cmd("urgent_cancel")
        Ui.refresh()
    }

    /** 받은 급구에 「판매하기」. 서버가 그 자리에서 내 물건으로 만들어요. price 0 = 나눔. */
    fun replyUrgent(name: String, desc: String, price: Long) {
        val u = urgentIn ?: return
        repliedUrgent.add(u.id)
        Link.cmd("urgent_reply", "id" to u.id, "name" to cutName(name), "desc" to desc.take(Config.DESC_MAX).trim(), "price" to price)
        urgentIn = null
        Notes.clearCard()
        justEnded = Texts.URGENT_REPLIED
        Ui.refresh()
    }

    fun closeUrgentIn() { urgentIn = null; Notes.clearCard(); Ui.refresh() }

    /** 급구에 온 답 중 하나로 하기 (urgent_pick). 거래가 정해지면 서버가 deal을 보내요. */
    fun pickUrgent(no: Int) {
        if (urgentOffers.none { it.no == no } || inProgress) return
        pickedUrgent = true
        Link.cmd("urgent_pick", "no" to no)
    }

    // ───── 경로 추천과 드라이브 코스 (약속 5절) ─────
    private var viaAdded = false

    /** 요청·거래가 시작됨: 떠 있던 경로 추천·드라이브 코스 카드와 알림을 치워요 (답은 보내지 않아요). */
    private fun dropRoute() {
        if (routeOffer == null) return
        routeOffer = null
        Notes.clearCard()
    }

    fun answerRoute(accept: Boolean) {
        val r = routeOffer ?: return
        routeOffer = null
        Notes.clearCard()
        Link.cmd("route_answer", "id" to r.id, "accept" to accept)
        if (accept) {
            // 거래의 만날 곳(첫째 경유지)과 섞이지 않게 따로 기억해요
            Navi.addVia(r.via, second = deal?.inserted == Navi.Inserted.WAYPOINT) { ok ->
                viaAdded = ok
                Voice.say(if (ok) Texts.NAVI_OK + "." else Texts.NAVI_FAIL_SHORT)
            }
        }
        Ui.refresh()
    }

    fun askCourse() { Link.cmd("course_ask") }

    /** 내 요청이 거절·응답 없음·취소로 끝남: 같은 판매자의 "다른 거래 중" 줄을 다시 「거래 요청」으로 돌려요.
     *  서버가 지금도 그 줄을 busy라고 하면 그대로 둬요. 다시 보내면 서버의 답을 따라요. */
    private fun freeSameSeller(ended: Row) {
        val seller = ended.car.key.substringBefore(':')
        nearby?.rows?.forEach { o ->
            if (o !== ended && o.busy && o.car.key.substringBefore(':') == seller && o.car.key !in Link.busyKeys && o.no !in Link.rowBusy) o.busy = false
        }
    }

    // ───── 서버가 보낸 일 ─────
    fun onServer(e: JSONObject) {
        val list = nearby
        val r = if (list != null && e.optLong("list", -1L) == list.id) row(e.optInt("no", -1)) else null
        when (e.optString("t").ifEmpty { e.optString("type") }) {
            "reply" -> if (r != null && !r.gone && r.state == RowState.WAITING) {           // B-43
                r.state = RowState.ACCEPTED
                Ui.refresh()
                announce(Texts.sayAccepted(r.no), Screen.NEARBY)
            }
            "reply_no" -> if (r != null && !r.gone && r.state == RowState.WAITING) {        // B-45
                r.state = RowState.REJECTED
                freeSameSeller(r)
                Ui.refresh()
                announce(Texts.sayRejected(r.no), Screen.NEARBY)
            }
            "no_answer" -> if (r != null && !r.gone && r.state == RowState.WAITING) {       // B-46
                r.state = RowState.NO_ANSWER
                freeSameSeller(r)
                Ui.refresh()
                announce(Texts.sayNoAnswer(r.no), Screen.NEARBY)
            }
            "seller_cancel" -> if (r != null && !r.gone && r.state == RowState.ACCEPTED) {  // B-47
                r.state = RowState.CANCELLED
                freeSameSeller(r)
                Ui.refresh()
                announce(Texts.sayCancelled(r.no), Screen.NEARBY)
            }
            "gone" -> if (r != null && !r.gone) {                                           // B-53
                val wasLive = r.state == RowState.WAITING || r.state == RowState.ACCEPTED
                r.gone = true
                Ui.refresh()
                if (wasLive) announce(Texts.sayGone(r.no), Screen.NEARBY)
            }
            "not_picked" -> if (list != null && e.optLong("list") == list.id) {             // B-51
                nearby = null
                ended(Texts.SAY_NOT_PICKED)
                Ui.go(Screen.HOME)
                announce(Texts.SAY_NOT_PICKED, Screen.HOME, end = true)
                checkNearby()
            }
            // C-24: 폰에서 넣은 것. 말하지 않고 알림도 없어요
            // 서버가 목록을 가지면 폰에서 넣은 것은 me로 와요. 여기서 또 넣으면 두 번 들어가요
            "phone_add_sell", "phone_add_want", "phone_set_photo" -> if (serverMode) return else when (e.optString("t").ifEmpty { e.optString("type") }) {
            "phone_add_sell" -> {
                val n = cutName(e.optString("name").trim()); val v = e.optLong("price", 0L)
                if (n.isNotEmpty() && v >= 1 && v <= Config.PRICE_MAX) addSell(n, v, e.optString("photo", ""))   // 50개가 넘으면 addSell이 조용히 무시해요
            }
            "phone_add_want" -> cutName(e.optString("name").trim()).let { if (it.isNotEmpty()) addWant(it) }   // 이미 있으면 addWant가 무시해요
            "phone_set_photo" -> setSellPhoto(e.opt("id")?.toString() ?: "", e.optString("photo", ""))
            }
            "busy" -> if (r != null && !r.sent && !r.gone && !r.busy) { r.busy = true; Ui.refresh() }   // B-56 (이벤트로 와도 받아요)
            "request" -> onRequest(e)                                                       // S-20
            "urgent" -> {                                                                   // 약속 4절: 주변에 급구
                val id = e.optString("id")
                if (id in seenUrgent || !tradeOn) return
                seenUrgent.add(id)
                val u = UrgentIn(id, e.optString("name"), e.optString("desc"), e.optInt("min"), e.optString("nick"))
                urgentIn = u
                Ui.refresh()
                Voice.say(Texts.sayUrgent(u.name))
                if (!Ui.front) Notes.card(Texts.urgentTitle(u.name), Texts.urgentText(u.desc, u.min, u.nick), Screen.HOME,
                    listOf(Texts.URGENT_SELL to NoteReceiver.URGENT_SELL, Texts.CLOSE to NoteReceiver.URGENT_CLOSE))
            }
            "urgent_end" -> {
                val id = e.optString("id")
                if (urgentIn?.id == id) { urgentIn = null; Notes.clearCard(); Ui.refresh() }
                if (id in repliedUrgent && e.optString("why") == "picked_other") {                  // 약속 8절: 다른 답이 골라짐
                    repliedUrgent.remove(id)
                    justEnded = Texts.SAY_PEER_PICKED_OTHER
                    Ui.refresh()
                    announce(Texts.SAY_PEER_PICKED_OTHER, Screen.HOME, end = true)
                } else repliedUrgent.remove(id)
            }
            "urgent_offer" -> {                                                             // 약속 8절: 내 급구에 답이 옴
                val o = offerFrom(e)
                urgentOffers.removeAll { it.no == o.no }; urgentOffers.add(o); urgentOffers.sortBy { it.no }
                Ui.refresh()
                announce(Texts.sayUrgentOffer(o.nick, o.name, o.price), Screen.WANTS)
            }
            "route", "course" -> {                                                          // 약속 5절
                if (inProgress) return                                                      // 요청을 주고받는 중·거래가 정해진 동안에는 버려요
                val v = e.optJSONObject("via") ?: return
                val text = if (e.optString("t") == "route") Texts.routeText(e.optString("label"), e.optInt("extra_min"), e.optString("side") == "sell")
                    else Texts.courseText(e.optString("title"), e.optInt("extra_min"))
                routeOffer = RouteOffer(e.optString("id"), text, Config.Place(v.optString("name"), v.optDouble("lat"), v.optDouble("lon")))
                Ui.refresh()
                Voice.say(text)
                if (!Ui.front) Notes.card(text, null, Screen.HOME, listOf(Texts.ROUTE_GO to NoteReceiver.ROUTE_YES, Texts.ROUTE_NO to NoteReceiver.ROUTE_NO))
            }
            "course_none" -> Voice.say(Texts.G_NO_COURSE)
            "request_end" -> {
                val inc = incoming
                if (inc == null || inc.req != e.getString("req")) return
                when (e.getString("why")) {
                    "timeout" -> if (!inc.accepted) {                                       // S-24
                        clearIncoming(); ended(Texts.JUST_NO_ANSWER); Ui.go(Screen.HOME); checkNearby()
                    }
                    "picked_other" -> endIncoming(Texts.SAY_PEER_PICKED_OTHER)               // S-26
                    "no_pick" -> endIncoming(Texts.SAY_PEER_NO_PICK)                         // S-27
                    "withdrew" -> endIncoming(Texts.SAY_PEER_WITHDREW)                       // S-29
                }
            }
            "deal" -> onDeal(e)                                                             // S-25, B-44, B-49
            "place" -> {
                val d = deal ?: return
                if (d.id != e.getString("deal") || d.stage != Stage.WAIT_PEER) return
                d.placeDesc = e.optString("note", "").ifEmpty { e.optString("desc", "") }         // 서버가 한 줄 설명을 주면 그것을 따라요 (집 물건 등)
                setPlace(d, Config.Place(e.getString("name"), e.getDouble("lat"), e.getDouble("lon")))
            }
            "peer_cancel" -> {                                                              // M-16
                val d = deal ?: return
                if (d.id != e.getString("deal") || d.stage == Stage.ASK) return
                val hadInserted = d.inserted != null              // M-02 중이거나 M-07 뒤면 내비에 넣은 것이 없어요
                endDeal(d, Deals.PEER_CANCELLED)
                justEnded = Texts.SAY_PEER_CANCELLED_SHORT
                Ui.go(Screen.HOME)
                announce(if (hadInserted) Texts.SAY_PEER_CANCELLED else Texts.SAY_PEER_CANCELLED_SHORT, Screen.HOME, end = true)
                checkNearby()
            }
        }
    }

    /** S-20, S-30, S-31, S-33: 받을 수 없으면 서버에 그렇다고만 알려요 (화면에는 아무것도 안 떠요). */
    private fun onRequest(e: JSONObject) {
        val req = e.getString("req")
        val sell = sells.firstOrNull { it.id == e.get("sell_id").toString() }
        if (!tradeOn || inProgress || sell == null || !Link.reachable) { Link.cmd("ignore", "req" to req); return }
        closeNearby()
        started()
        val b = e.optJSONObject("buyer")                  // 약속 8절. 없으면(옛 서버) 지금 문구 그대로
        val r = Incoming(req, sell, e.optInt("minutes", e.optInt("min")), b?.optInt("deals", e.optInt("deals")) ?: e.optInt("deals"),
            e.optString("photo", "").ifEmpty { sell.photo }, b?.optString("nick", "") ?: "", b?.let { rating(it) })
        incoming = r
        Ui.go(Screen.REQUEST)
        announce(
            if (r.nick.isNotEmpty()) Texts.sayRequestBy(r.nick, r.sell.name, r.minutes) else Texts.sayRequest(r.sell.name, r.minutes), Screen.REQUEST,
            Texts.requestNoteTitle(r.sell.name, r.sell.price),
            if (r.nick.isNotEmpty()) Texts.buyerLine(r.nick, r.rating, r.minutes) else Texts.requestNoteText(r.minutes, r.deals), buttons = true,
        )
    }

    private fun onDeal(e: JSONObject) {
        val id = e.getString("deal")
        if (deal?.id == id) return
        val selling = e.getBoolean("selling")
        var nick = ""                                     // H-07: 상대 닉네임 (모르면 "" → meet가 오면 채워요)
        if (selling) {
            nick = incoming?.nick ?: ""
            if (incoming?.accepted == true) clearIncoming()
            else if (repliedUrgent.isEmpty() || inProgress) return            // 급구에 「판매하기」로 답한 물건이 골라짐 (약속 8절)
            repliedUrgent.clear(); urgentIn = null
        } else if (pickedUrgent && !inProgress) {                             // 내 급구에 온 답 중 하나를 골랐어요 (약속 8절)
            pickedUrgent = false; urgentOffers.clear(); myUrgent = null
        } else {
            val list = nearby ?: return
            if (e.optLong("list") != list.id) return
            if (list.browse) Link.cmd("browse", "on" to false)
            nick = row(e.optInt("no", -1))?.car?.nick ?: ""
            nearby = null                                 // 서버가 나머지 요청을 끝냈어요 (B-49)
        }
        startDeal(Deal(id, selling, e.getString("item"), e.getLong("price"), e.optInt("peer_deals"), e.optString("peer_car", ""),
            if (selling) e.opt("sell_id")?.toString() else null).also { it.peerNick = nick }, home = e.optBoolean("home", false))
    }

    // ───── 만날 곳 정하기 (M-01~M-07, M-20, M-21) ─────
    private fun startDeal(d: Deal, home: Boolean = false) {   // M-01
        deal = d
        dropRoute()
        if (home) {                                       // 집 물건: 목적지를 묻지 않고 서버가 정한 만날 곳(place)을 기다려요
            d.home = true
            decideDest(d, withDest = false, asked = true)
        } else if (Navi.hasRoute) {
            decideDest(d, withDest = true, asked = false)
        } else {                                          // M-02
            d.stage = Stage.WAIT_DEST
            save()
            waitForDestination(d)
            Ui.go(Screen.MEET)
            announce(Texts.SAY_NEED_DEST, Screen.MEET, Texts.NEED_DEST_NOTE_TITLE, Texts.NEED_DEST_NOTE_TEXT)
        }
    }

    private fun waitForDestination(d: Deal) {
        d.destTimer = Runnable { if (deal === d && d.stage == Stage.WAIT_DEST) decideDest(d, withDest = false, asked = true) }
            .also { h.postDelayed(it, Config.DEST_WAIT_MS) }
    }

    /** M-04: 「가까운 곳으로 정하기」 */
    fun decideWithoutDestination() {
        val d = deal ?: return
        if (d.stage == Stage.WAIT_DEST) decideDest(d, withDest = false, asked = true)
    }

    /** M-03: 기다리는 동안 내비에 목적지가 들어옴. */
    fun onRouteChanged() {
        val d = deal ?: return
        if (d.stage == Stage.WAIT_DEST && Navi.hasRoute) decideDest(d, withDest = true, asked = true)
    }

    /** 내 목적지가 정해짐: 서버에 알리고 만날 곳을 기다려요. 상대도 정해야 서버가 골라요 (M-20, M-21). */
    private fun decideDest(d: Deal, withDest: Boolean, asked: Boolean) {
        d.destTimer?.let(h::removeCallbacks); d.destTimer = null
        d.stage = Stage.WAIT_PEER
        d.withDest = withDest
        d.askedDest = asked
        save()
        Ui.go(Screen.MEET)
        if (withDest) Navi.withDestination { p -> if (deal === d) Link.cmd("dest", "deal" to d.id, "with" to true, "lat" to p?.first, "lon" to p?.second) }
        else Link.cmd("dest", "deal" to d.id, "with" to false)
    }

    fun placeLine(d: Deal) = d.placeDesc.ifEmpty { if (d.withDest) Texts.DESC_WITH_DEST else Texts.DESC_NO_DEST }

    private fun setPlace(d: Deal, place: Config.Place) {    // M-03, M-04
        val withDest = d.withDest
        d.stage = Stage.MEETING
        d.place = place
        d.doorOpen = false; d.doorCycles = 0
        save()
        Ui.go(Screen.MEET)
        Navi.insert(place, asWaypoint = withDest) { kind ->
            if (deal !== d) {                             // 그 사이에 거래가 끝났으면 넣은 것을 바로 지워요
                if (kind != null) Navi.remove(kind)
                return@insert
            }
            d.inserted = kind
            d.naviFailed = kind == null                   // M-07
            save()
            Ui.refresh()
            val tail = if (kind != null) Texts.NAVI_OK + "." else Texts.NAVI_FAIL
            val sentence = (if (withDest && !d.askedDest) Texts.DEAL_FIXED + " " else "") + Texts.placeIs(place.name) + " " +
                placeLine(d) + ". " + tail
            announce(sentence, Screen.MEET, Texts.meetNoteTitle(place.name), if (kind != null) Texts.NAVI_OK else Texts.NAVI_FAIL)   // M-05
        }
    }

    /** M-13: 내비에서 만날 곳을 지우고 거래를 끝내요. */
    private fun endDeal(d: Deal, result: String) {
        Deals.record(d, result)                           // H-07: 지난 거래에 한 줄
        d.destTimer?.let(h::removeCallbacks)
        d.inserted?.let { Navi.remove(it) }
        d.inserted = null
        deal = null
        Notes.endTask()
        save()
    }

    // ───── 거래 끝 (M-08~M-16) ─────

    /** M-09: 「만났어요」 */
    fun finishDeal() {
        val d = deal ?: return
        if (d.stage != Stage.MEETING) return
        d.stage = Stage.ASK
        save()
        Ui.go(Screen.ASK)
    }

    fun onDriverDoor(open: Boolean) {                     // M-08
        val d = deal ?: return
        if (d.stage != Stage.MEETING || !Car.parked) return
        if (open) { d.doorOpen = true; return }
        if (!d.doorOpen) return
        d.doorOpen = false
        if (!d.gotOutSent) { d.gotOutSent = true; Link.cmd("got_out", "deal" to d.id) }   // 약속 9절: 폰에 "거래 어땠어요?"가 뜨게
        d.doorCycles++
        if (d.doorCycles >= Config.DOOR_CYCLES_FOR_RETURN) {
            d.stage = Stage.ASK
            save()
            Ui.go(Screen.ASK)
            announce(Texts.ASK_TITLE, Screen.ASK)
        }
    }

    fun onGearChanged() {
        if (!Car.parked) deal?.let { it.doorOpen = false; it.doorCycles = 0 }
        Ui.gearChanged()
    }

    /** M-10, M-11: 거래했어요 / 안 했어요. 내 답은 상대에게 가지 않아요 (M-17). */
    fun answerDeal(did: Boolean) {
        val d = deal ?: return
        if (d.stage != Stage.ASK) return
        if (did) {
            dealCount++
            if (serverMode) {                             // 서버가 sold로 바꿔요. 화면은 먼저 바꿔 둬요
                if (d.selling) sells.firstOrNull { it.id == d.sellId }?.status = "sold"
                else wants.filter { d.itemName.replace(" ", "").contains(it.replace(" ", "")) }.forEach { removeWant(it) }
            } else if (d.selling) sells.removeAll { it.id == d.sellId }
            else wants.removeAll { d.itemName.replace(" ", "").contains(it.replace(" ", "")) }
        }
        Link.cmd("deal_end", "deal" to d.id, "done" to did, "stars" to d.stars)       // 서버가 sold/sale을 정해요 (약속 바뀐 것)
        endDeal(d, if (did) Deals.DONE else Deals.NOT_DONE)
        justEnded = if (did) Texts.G_DONE else Texts.JUST_NOT_DONE
        Ui.go(Screen.HOME)
        checkNearby()
    }

    /** M-12: 아직이에요. */
    fun setStars(n: Int) { val d = deal ?: return; d.stars = if (d.stars == n) null else n; Ui.refresh() }

    fun notYet() {
        val d = deal ?: return
        if (d.stage != Stage.ASK) return
        d.stage = Stage.MEETING
        d.deferred = true                                 // H-04
        d.doorOpen = false; d.doorCycles = 0
        save()
        Ui.go(Screen.MEET)
    }

    /** M-15: 거래 취소. 상대에게 취소가 가요. */
    fun cancelDeal() {
        val d = deal ?: return
        Link.cmd("cancel_deal", "deal" to d.id)
        endDeal(d, Deals.CANCELLED)
        justEnded = Texts.JUST_CANCELLED
        Ui.go(Screen.HOME)
        checkNearby()
    }
}
