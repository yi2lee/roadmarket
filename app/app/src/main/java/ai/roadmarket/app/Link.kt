package ai.roadmarket.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors

/**
 * 서버와 주고받기 (요건표 7-0). 1초마다 내 상태와 명령을 올리고, 나에게 온 일을 받아요.
 * 명령은 서버가 받았다고 답할 때까지 다시 보내요. 서버는 같은 번호의 명령을 두 번 하지 않아요.
 * 화면에 쓰는 값과 상태 기계는 모두 메인 스레드에서만 만져요. 네트워크만 뒤 스레드에서 해요.
 */
object Link {
    private val h = Handler(Looper.getMainLooper())
    private val net = Executors.newSingleThreadExecutor()
    private lateinit var app: Context

    /** 설치마다 무작위로 만든 차 식별자. */
    lateinit var carId: String
        private set
    private val session = UUID.randomUUID().toString()
    private var nextCid = 1L
    private val outbox = ArrayList<JSONObject>()
    private var lastSeq = 0L
    private var boot = ""              // 서버가 켜질 때마다 바뀌는 값. 바뀌면 일 번호를 처음부터 받아요 (D-21)
    private var inFlight = false
    private var again = false
    private var lastOk = 0L
    private val started = SystemClock.elapsedRealtime()

    /** 서버가 알려 준 주변 물건 (가까운 순). 서버에 닿지 않으면 비어요. */
    var near: List<Market.Offer> = emptyList()
        private set
    /** 열린 목록의 줄 번호 → 지금 거리(분) */
    var rowMinutes: Map<Int, Int> = emptyMap()
        private set
    /** B-56: 주변 물건 줄 중 상태가 "busy"인 것의 key, 열린 목록 줄 번호 */
    var busyKeys: Set<String> = emptySet()
        private set
    var rowBusy: Set<Int> = emptySet()
        private set
    /** 「주변 물건 보기」로 받은 주변 물건 전체 (browse on일 때만. 가까운 순) */
    var browse: List<Market.Offer> = emptyList()
        private set
    /** 마지막 응답에 browse가 있었는지 */
    var browseSeen = false
        private set
    /** 서버에 닿기 전에 화면에 먼저 반영해 둔 목록 명령. 이것이 남아 있는 동안 온 me의 목록은 쓰지 않아요 */
    private val listCmds = setOf("sell_add", "sell_update", "sell_remove", "sell_hide", "sell_status", "want_add", "want_remove", "restore", "urgent_post", "urgent_cancel", "urgent_pick")
    /** C-23: 폰 연결 번호 (여섯 자리 글자). 없으면 "" */
    var pair = ""
        private set
    /** M-21: 내 목적지는 정해졌고 상대가 아직 정하는 중 */
    var peerDeciding = false
        private set

    /** 7-0: 서버에 닿지 않음. 마지막으로 닿은 지 이만큼 지나면 닿지 않는 것으로 봐요. */
    val reachable: Boolean get() = SystemClock.elapsedRealtime() - (if (lastOk == 0L) started else lastOk) < Config.UNREACHABLE_MS

    // D-23, D-24: 시험용 위치와 내 차 정보 (앱에 저장해요)
    private var testPos: Pair<Double, Double>? = null
    var carInfo = ""
        private set

    private var running = false

    /** 차 식별자와 저장한 값을 읽어요. 상태 기계보다 먼저 불러요. */
    fun prepare(c: Context) {
        app = c
        val p = c.getSharedPreferences("link", Context.MODE_PRIVATE)
        carId = p.getString("car", null) ?: UUID.randomUUID().toString().replace("-", "").also { p.edit().putString("car", it).apply() }
        lastSeq = p.getLong("seq", 0L)
        boot = p.getString("boot", "") ?: ""
        p.getString("latS", null)?.let { la -> p.getString("lonS", null)?.let { lo -> testPos = la.toDouble() to lo.toDouble() } }
        carInfo = p.getString("carInfo", "") ?: ""
    }

    /** 1초마다 주고받기를 시작해요. */
    fun init(c: Context) {
        running = true
        h.post(loop)
    }

    fun setTestPos(lat: Double, lon: Double) {
        testPos = lat to lon
        app.getSharedPreferences("link", Context.MODE_PRIVATE).edit().putString("latS", lat.toString()).putString("lonS", lon.toString()).apply()
        now()
    }

    fun setCarInfo(kind: String, plate: String) {
        carInfo = "$kind · 번호 끝 $plate"
        app.getSharedPreferences("link", Context.MODE_PRIVATE).edit().putString("carInfo", carInfo).apply()
        now()
    }

    /** 명령을 넣고 바로 한 번 주고받아요. */
    fun cmd(t: String, vararg kv: Pair<String, Any?>) {
        val o = JSONObject().put("cid", nextCid++).put("t", t)
        kv.forEach { (k, v) -> if (v != null) o.put(k, v) }
        outbox.add(o)
        now()
    }

    private fun now() { if (!running) return; if (inFlight) again = true else send() }

    private val loop = object : Runnable {
        override fun run() {
            if (!inFlight) send()
            Navi.askLocation()
            h.postDelayed(this, Config.POLL_MS)
        }
    }

    private fun position(): Pair<Double, Double>? = testPos ?: Navi.location
    /** 서버에 올리는 내 위치 (만날 곳 지도의 "나") */
    val myPos: Pair<Double, Double>? get() = position()

    private fun send() {
        inFlight = true
        val sent = ArrayList(outbox)
        val st = Market.snapshot().put("car_info", carInfo)
        position()?.let { st.put("pos", JSONArray().put(it.first).put(it.second)) }
        val body = JSONObject().put("car", carId).put("session", session).put("ack", lastSeq)
            .put("state", st).put("cmds", JSONArray().also { a -> sent.forEach { a.put(it) } }).toString()
        net.execute {
            val res = try { post(body) } catch (e: Exception) { null }
            h.post {
                inFlight = false
                if (res != null) {
                    lastOk = SystemClock.elapsedRealtime()
                    outbox.removeAll(sent.toSet())
                    receive(res)
                } else {
                    near = emptyList()                  // 7-0: 닿지 않는 동안에는 알림과 요청이 오지 않아요
                    browse = emptyList(); browseSeen = false
                }
                // 1초마다 화면을 통째로 다시 만들면 누르던 버튼이 사라져 터치가 먹지 않아요.
                // 이 주고받기로 화면에 보이는 값이 바뀌었을 때만 다시 그려요 (상태 기계가 바꾼 것은 상태 기계가 그려요).
                val now = shown()
                if (now != lastShown) { lastShown = now; Ui.refresh() }
                if (again) { again = false; send() }
            }
        }
    }

    private var lastShown = ""
    /** 주고받기만으로 바뀌는, 화면에 보이는 값 (reachable은 시간으로 바뀌어서 지난번 값과 비교해요) */
    private fun shown(): String = listOf(reachable, pair, peerDeciding,
        Market.nearby?.rows?.joinToString(",") { "${it.no}:${it.car.minutes}:${it.busy}" }, browse.joinToString(",") { it.key },
        Market.meet?.let { m -> "${m.myEta}:${m.peerEta}:${m.peerLat}:${m.peerLon}:${m.place}" }, myPos?.let { "%.4f,%.4f".format(it.first, it.second) }).toString()

    private fun post(body: String): JSONObject {
        val c = URL(BuildConfig.SERVER_URL + "/sync").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 2000; c.readTimeout = 3000
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (c.responseCode != 200) {
                val why = try { c.errorStream?.bufferedReader(Charsets.UTF_8)?.readText() } catch (e: Exception) { null }
                android.util.Log.w("ROADMARKET_LINK", "http ${c.responseCode} $why")
                throw Exception("http ${c.responseCode}")
            }
            return JSONObject(c.inputStream.bufferedReader(Charsets.UTF_8).readText())
        } finally { c.disconnect() }
    }

    /** near·browse 한 줄. 옛 서버 값(item, minutes, deals)과 약속 2절의 새 값(name, min, desc, loc, gu, seller)을 모두 받아요. */
    private fun offer(it: JSONObject): Market.Offer {
        val sl = it.optJSONObject("seller")
        return Market.Offer(it.getString("key"), it.optString("item", "").ifEmpty { it.optString("name") }, it.optLong("price"),
            if (it.has("minutes")) it.optInt("minutes") else it.optInt("min"), sl?.optInt("deals", it.optInt("deals")) ?: it.optInt("deals"),
            it.optString("photo", ""), it.optString("state") == "busy" || it.optBoolean("busy", false),
            it.optString("desc", ""), it.optString("loc", "car").ifEmpty { "car" }, it.optString("gu", ""), sl?.optString("nick", "") ?: "", sl?.let { s -> Market.rating(s) })
    }

    private fun receive(o: JSONObject) {
        val nl = ArrayList<Market.Offer>()
        o.optJSONArray("near")?.let { a -> for (i in 0 until a.length()) nl.add(offer(a.getJSONObject(i))) }
        near = nl
        browseSeen = o.has("browse")
        browse = ArrayList<Market.Offer>().also { l -> o.optJSONArray("browse")?.let { a -> for (i in 0 until a.length()) l.add(offer(a.getJSONObject(i))) } }
        Market.onMeet(o.optJSONObject("meet"))
        o.optJSONObject("me")?.let { Market.onMe(it, outbox.none { c -> c.optString("t") in listCmds }) }
        busyKeys = nl.filter { it.busy }.map { it.key }.toSet()
        val rm = HashMap<Int, Int>()
        val rb = HashSet<Int>()
        o.optJSONObject("rows")?.let { r -> r.keys().forEach { k ->
            val v = r.get(k)
            if (v is JSONObject) {                    // {"minutes":5,"state":"busy"} 꼴로 와도 받아요
                if (v.has("minutes")) rm[k.toInt()] = v.getInt("minutes")
                if (v.optString("state") == "busy") rb.add(k.toInt())
            } else rm[k.toInt()] = r.getInt(k)
        } }
        rowMinutes = rm
        rowBusy = rb
        pair = o.optString("pair", "").trim()
        peerDeciding = o.optBoolean("peer_deciding", false)
        val b = o.optString("boot", "")
        if (b != boot) { boot = b; lastSeq = 0L }
        val ev = o.optJSONArray("events") ?: JSONArray()
        for (i in 0 until ev.length()) {
            val e = ev.getJSONObject(i)
            val seq = e.getLong("seq")
            if (seq <= lastSeq) continue
            lastSeq = seq
            try { Market.onServer(e) } catch (x: Exception) { android.util.Log.w("ROADMARKET", "event", x) }
        }
        app.getSharedPreferences("link", Context.MODE_PRIVATE).edit().putLong("seq", lastSeq).putString("boot", boot).apply()
        Market.onPoll()
    }
}
