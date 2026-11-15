package ai.roadmarket.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * 앱이 뒤에 있어도 서버에 몇 초마다 물어서, 새 요청·거래 물음·급구를 알림으로 띄워요.
 * 서버에는 읽기만 해요 (GET /phone/state).
 */
class WatchService : Service() {

    private lateinit var store: Store
    private lateinit var nm: NotificationManager
    private lateinit var thread: HandlerThread
    private lateinit var worker: Handler
    private var fails = 0

    private val tick = Runnable { poll() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        store = Store(this)
        nm = getSystemService(NotificationManager::class.java)
        makeChannels(this)
        thread = HandlerThread("watch").apply { start() }
        worker = Handler(thread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 다시 불려도 매번 상시 알림을 붙여요. 시작하고 몇 초 안에 해야 해요.
        goForeground()
        worker.removeCallbacks(tick)
        worker.post(tick)
        // 시스템이 서비스를 죽이면 되살리지 않아요. 안드로이드 12+는 뒤에서 다시 켤 수 없어요.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        // 로그아웃했으면 그 계정의 알림은 거둬요.
        if (store.token.isEmpty()) for (k in listOf(K_REQ, K_DEAL, K_URG)) dropGone(k, emptySet())
        worker.removeCallbacksAndMessages(null)
        thread.quitSafely()
        super.onDestroy()
    }

    private fun goForeground() {
        val n = Notification.Builder(this, CH_WAIT)
            .setSmallIcon(R.drawable.ic_stat_mark)
            .setContentText(getString(R.string.watch_text))
            .setContentIntent(openApp(this))
            .setOngoing(true)
            .setShowWhen(false)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(ID_WAIT, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID_WAIT, n)
        }
    }

    // ── 묻기 ──────────────────────────────────────────────────

    /** 일하는 스레드에서 돌아요. */
    private fun poll() {
        val token = store.token
        if (token.isEmpty()) return quit()
        var code = 0
        var body: String? = null
        try {
            val c = URL(BuildConfig.SERVER_URL + "/phone/state").openConnection() as HttpURLConnection
            try {
                c.connectTimeout = TIMEOUT_MS
                c.readTimeout = TIMEOUT_MS
                c.useCaches = false
                c.setRequestProperty("X-Phone-Token", token)
                code = c.responseCode
                if (code == 200) body = c.inputStream.bufferedReader().use { it.readText() }
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            // 네트워크 문제는 조용히 다음 차례에 다시 해요.
        }
        if (code == 401) {
            // 표가 죽었어요. 저장소를 비우고 멈춰요.
            if (store.token == token) {
                store.deadToken = token
                store.clearToken()
            }
            return quit()
        }
        val ok = body != null && try {
            handle(JSONObject(body!!))
            true
        } catch (e: Exception) {
            false
        }
        fails = if (ok) 0 else fails + 1
        // 실패가 이어지면 5초 → 10초 → 20초 → 30초로 늘려요. 성공하면 다시 5초.
        val wait = if (fails == 0) POLL_MS else minOf(MAX_MS, POLL_MS shl minOf(fails, 4))
        worker.postDelayed(tick, wait)
    }

    private fun quit() {
        worker.removeCallbacks(tick)
        stopSelf()
    }

    // ── 알림 고르기 ────────────────────────────────────────────

    private fun handle(st: JSONObject) {
        if (!st.optBoolean("ok", false)) throw IllegalStateException("not ok")
        // 화면이 보이는 중이면 페이지가 이미 보여 줘요. 알림은 띄우지 않고 "알린 것"으로만 기억해요.
        val quiet = appVisible

        // 새 요청
        val reqs = st.optJSONArray("requests")
        val reqIds = mutableSetOf<String>()
        val seenReq = store.seen(K_REQ).toSet()
        val newReq = mutableListOf<String>()
        if (reqs != null) for (i in 0 until reqs.length()) {
            val r = reqs.optJSONObject(i) ?: continue
            val id = r.optString("id")
            if (id.isEmpty()) continue
            reqIds += id
            if (id in seenReq) continue
            newReq += id
            if (!quiet) {
                val it = r.optJSONObject("item")
                val nick = r.optJSONObject("buyer")?.optString("nick").orEmpty()
                val text = itemLine(it) + " · " + nick
                show(K_REQ, id, getString(R.string.n_request_title), text)
            }
        }
        store.addSeen(K_REQ, newReq)
        // 사라진 요청(수락·거절·취소)의 알림은 지워요.
        dropGone(K_REQ, reqIds)

        // 거래 뒤 물음
        val d = st.optJSONObject("deal")
        val dealId = d?.optString("id").orEmpty()
        val asking = d != null && d.optBoolean("ask", false) && dealId.isNotEmpty()
        if (asking && dealId !in store.seen(K_DEAL)) {
            store.addSeen(K_DEAL, listOf(dealId))
            if (!quiet) {
                val name = d!!.optJSONObject("item")?.optString("name").orEmpty()
                show(K_DEAL, dealId, getString(R.string.n_deal_title), getString(R.string.n_deal_text, name))
            }
        }
        // 물음이 끝나면(별점을 남겼거나 거래가 끝남) 그 알림도 지워요.
        dropGone(K_DEAL, if (asking) setOf(dealId) else emptySet())

        // 급구 (phone.html의 급구 카드와 같은 글귀)
        val us = st.optJSONArray("urgents")
        val seenUrg = store.seen(K_URG).toSet()
        val newUrg = mutableListOf<String>()
        if (us != null) for (i in 0 until us.length()) {
            val u = us.optJSONObject(i) ?: continue
            val id = u.optString("id")
            if (id.isEmpty() || id in seenUrg) continue
            newUrg += id
            if (!quiet) {
                val desc = u.optString("desc")
                val title = getString(R.string.n_urgent_title, u.optString("name"))
                val text = (if (desc.isNotEmpty()) "$desc · " else "") + u.optString("nick")
                show(K_URG, id, title, text)
            }
        }
        store.addSeen(K_URG, newUrg)
    }

    /** 「<물건 이름> <가격>」. 가격 표기는 phone.html의 won()과 같아요. */
    private fun itemLine(it: JSONObject?): String {
        val name = it?.optString("name").orEmpty()
        val price = it?.optDouble("price", 0.0) ?: 0.0
        return "$name ${won(price.toLong())}"
    }

    private fun show(kind: String, id: String, title: String, text: String) {
        val n = Notification.Builder(this, CH_EVENT)
            .setSmallIcon(R.drawable.ic_stat_mark)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp(this))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .build()
        // 허용이 없으면 시스템이 조용히 버려요.
        try {
            nm.notify(tag(kind, id), ID_EVENT, n)
        } catch (e: Exception) {
        }
    }

    /** 이 종류의 떠 있는 알림 중 지금 없는 id는 지워요. 서비스가 다시 켜져도 맞게요. */
    private fun dropGone(kind: String, alive: Set<String>) {
        val prefix = "$kind:"
        val active = try { nm.activeNotifications } catch (e: Exception) { return }
        for (sb in active) {
            val t = sb.tag ?: continue
            if (sb.id == ID_EVENT && t.startsWith(prefix) && t.removePrefix(prefix) !in alive) nm.cancel(t, ID_EVENT)
        }
    }

    companion object {
        // 몇 초마다 물을지예요. 임의로 정한 값이에요.
        private const val POLL_MS = 5_000L
        // 실패가 이어질 때 가장 긴 간격이에요.
        private const val MAX_MS = 30_000L
        private const val TIMEOUT_MS = 5_000

        private const val CH_WAIT = "wait"
        private const val CH_EVENT = "event"
        private const val ID_WAIT = 1
        private const val ID_EVENT = 2

        const val K_REQ = "req"
        const val K_DEAL = "deal"
        const val K_URG = "urg"

        /** 서비스가 돌고 있는지예요. 같은 프로세스 안에서만 봐요. */
        @Volatile
        var running = false
            private set

        /** MainActivity가 앞에 보이는 중인지예요 (onResume~onPause). */
        @Volatile
        var appVisible = false

        private fun tag(kind: String, id: String) = "$kind:$id"

        /** 0은 「나눔」, 나머지는 천 단위 쉼표에 「원」. */
        fun won(n: Long): String = if (n == 0L) "나눔" else String.format(Locale.US, "%,d원", n)

        /** 누르면 앱을 열어요. 이미 떠 있으면 그 화면이 앞으로 와요 (singleTask). */
        fun openApp(c: Context): PendingIntent {
            val i = Intent(c, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        fun makeChannels(c: Context) {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH_WAIT, c.getString(R.string.ch_wait), NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_EVENT, c.getString(R.string.ch_event), NotificationManager.IMPORTANCE_HIGH)
            )
        }

        /** 앱이 앞에 있을 때만 불러요. 안드로이드 12+는 뒤에서 시작하면 막혀요. */
        fun start(c: Context) {
            if (running) return
            try {
                c.startForegroundService(Intent(c, WatchService::class.java))
            } catch (e: Exception) {
                // 뒤에서 시작이 막힌 경우예요. 다음에 앱이 앞에 오면 다시 해요.
            }
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, WatchService::class.java))
        }
    }
}
