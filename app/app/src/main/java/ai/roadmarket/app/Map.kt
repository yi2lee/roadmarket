package ai.roadmarket.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * 만날 곳 지도 (약속 9절). OpenStreetMap 타일을 직접 받아 메모리에 둬요. 바깥 라이브러리 없음.
 * 못 받은 타일이 있으면 [failed]가 켜지고, 화면은 지도 칸을 빼요.
 */
object Tiles {
    private val h = Handler(Looper.getMainLooper())
    private val net = Executors.newFixedThreadPool(2)
    private val got = HashMap<String, Bitmap>()
    private val loading = HashSet<String>()
    private var failedAt = 0L
    private const val RETRY_MS = 30_000L                   // 임의: 못 받았으면 이만큼 지도를 빼고 다시 시도
    val failed get() = failedAt != 0L && SystemClock.elapsedRealtime() - failedAt < RETRY_MS

    fun get(z: Int, x: Int, y: Int, onGot: () -> Unit): Bitmap? {
        val n = 1 shl z
        val k = "$z/${((x % n) + n) % n}/$y"
        got[k]?.let { return it }
        if (k in loading || y < 0 || y >= n) return null
        loading.add(k)
        net.execute {
            val b = try { fetch(k) } catch (e: Exception) { null }
            h.post {
                loading.remove(k)
                if (b != null) { got[k] = b; onGot() }
                else if (!failed) { failedAt = SystemClock.elapsedRealtime(); Ui.refresh() }
            }
        }
        return null
    }

    private fun fetch(k: String): Bitmap? {
        val c = URL("https://tile.openstreetmap.org/$k.png").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 3000; c.readTimeout = 5000
            c.setRequestProperty("User-Agent", "RoadmarketDemo/0.1")
            if (c.responseCode != 200) return null
            return c.inputStream.use { BitmapFactory.decodeStream(it) }
        } finally { c.disconnect() }
    }
}

/** 보기만 하는 지도. 점: (글자, 위도, 경도). 세 점이 다 들어오는 가장 큰 줌을 골라요. */
class MeetMap(c: Context, private val p: Theme.Palette, private val points: List<Triple<String, Double, Double>>) : View(c) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val src = Rect(); private val dst = Rect()

    init { isClickable = false; isFocusable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    private fun px(lon: Double, z: Int) = (lon + 180.0) / 360.0 * 256.0 * (1 shl z)
    private fun py(lat: Double, z: Int): Double {
        val r = Math.toRadians(lat)
        return (1.0 - ln(tan(r) + 1.0 / kotlin.math.cos(r)) / PI) / 2.0 * 256.0 * (1 shl z)
    }

    override fun onDraw(cv: Canvas) {
        if (points.isEmpty() || width == 0) return
        val margin = 80                                   // 임의: 표시 글자가 가장자리에 잘리지 않게
        var z = 17
        while (z > 3) {
            val xs = points.map { px(it.third, z) }; val ys = points.map { py(it.second, z) }
            if (xs.max() - xs.min() <= width - 2 * margin && ys.max() - ys.min() <= height - 2 * margin) break
            z--
        }
        val xs = points.map { px(it.third, z) }; val ys = points.map { py(it.second, z) }
        val cx = (xs.max() + xs.min()) / 2; val cy = (ys.max() + ys.min()) / 2
        val left = cx - width / 2.0; val top = cy - height / 2.0
        for (tx in floor(left / 256).toInt()..floor((left + width) / 256).toInt())
            for (ty in floor(top / 256).toInt()..floor((top + height) / 256).toInt()) {
                val b = Tiles.get(z, tx, ty) { invalidate() } ?: continue
                val dx = (tx * 256 - left).toInt(); val dy = (ty * 256 - top).toInt()
                src.set(0, 0, b.width, b.height); dst.set(dx, dy, dx + 256, dy + 256)
                cv.drawBitmap(b, src, dst, null)
            }
        paint.textSize = Theme.BODY_SMALL.size.toFloat()
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        points.forEachIndexed { i, (label, lat, lon) ->
            val x = (px(lon, z) - left).toFloat(); val y = (py(lat, z) - top).toFloat()
            paint.color = p.dark; cv.drawCircle(x, y, 14f, paint)
            paint.color = p.darkText; cv.drawCircle(x, y, 6f, paint)
            val w = paint.measureText(label)
            // 만날 곳은 위, 나는 아래, 상대는 오른쪽: 점이 가까워도 글자가 겹치지 않게
            val lx = if (i == 2) x + 24f + w / 2 else x
            val ly = when (i) { 0 -> y - 26f; 1 -> y + 26f + Theme.BODY_SMALL.size; else -> y + Theme.BODY_SMALL.size / 2f - 4f }
            paint.color = p.popup; cv.drawRoundRect(lx - w / 2 - 10, ly - Theme.BODY_SMALL.size, lx + w / 2 + 10, ly + 8, 12f, 12f, paint)
            paint.color = p.text; cv.drawText(label, lx - w / 2, ly, paint)
        }
        val credit = "© OpenStreetMap"
        paint.textSize = 20f; paint.typeface = android.graphics.Typeface.DEFAULT
        val cw = paint.measureText(credit)
        paint.color = 0xCCFFFFFF.toInt(); cv.drawRect(width - cw - 16, height - 32f, width.toFloat(), height.toFloat(), paint)
        paint.color = 0xFF131417.toInt(); cv.drawText(credit, width - cw - 8, height - 9f, paint)
    }
}

/**
 * 차 화면 「만날 곳」의 카카오 지도. 서버의 /carmap 쪽을 WebView 하나로 띄워요.
 * 쪽 제목이 "ready"가 되면 [ready], "fail"이거나 5초 안에 ready가 아니거나 WebView를 못 만들면 [failed].
 * failed면 화면은 OpenStreetMap 지도([MeetMap])를 그려요. 화면을 다시 그려도 WebView는 하나를 계속 써요.
 */
object KakaoMap {
    private const val READY_WAIT_MS = 5_000L
    private val h = Handler(Looper.getMainLooper())
    private var web: android.webkit.WebView? = null
    private var shown: List<Double?>? = null              // 쪽에 마지막으로 보낸 좌표 (같으면 다시 보내지 않아요)
    var ready = false; private set
    var failed = false; private set
    private val timeout = Runnable { if (!ready) fail("timeout") }

    private fun fail(why: String, refresh: Boolean = true) {
        if (failed) return
        android.util.Log.w("KakaoMap", "OpenStreetMap으로 바꿔요: $why")
        failed = true; ready = false
        web?.let { w -> (w.parent as? android.view.ViewGroup)?.removeView(w); try { w.destroy() } catch (_: Exception) {} }
        web = null
        if (refresh) Ui.refresh()                          // 그리는 도중(만들기 실패)에는 다시 그리지 않아요
    }

    private fun f(v: Double?) = v?.toString() ?: "null"

    /** 좌표: 만날 곳, 나, 상대 (없으면 null). 지도 칸에 넣을 View를 돌려줘요. 실패했으면 null. */
    fun view(c: Context, pts: List<Double?>): View? {
        if (failed) return null
        var w = web
        if (w == null) {
            w = try {
                android.webkit.WebView(c.applicationContext).apply {
                    settings.javaScriptEnabled = true
                    isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false
                    isClickable = false; isFocusable = false; isLongClickable = false
                    overScrollMode = View.OVER_SCROLL_NEVER
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    setOnTouchListener { _, _ -> true }    // 터치를 받지 않아요 (지도를 손으로 움직이지 않게)
                    setBackgroundColor(0)
                    webViewClient = object : android.webkit.WebViewClient() {
                        // 5초는 쪽을 불러오기 시작한 때부터 세요 (WebView를 처음 띄우는 시간은 빼요)
                        override fun onPageStarted(v: android.webkit.WebView, url: String?, fav: android.graphics.Bitmap?) {
                            if (v === web && !ready) { h.removeCallbacks(timeout); h.postDelayed(timeout, READY_WAIT_MS) }
                        }
                        override fun onReceivedError(v: android.webkit.WebView, r: android.webkit.WebResourceRequest, e: android.webkit.WebResourceError) {
                            if (r.isForMainFrame) h.post { fail("load ${e.errorCode}") }
                        }
                    }
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onReceivedTitle(v: android.webkit.WebView, title: String?) {
                            if (v !== web) return
                            android.util.Log.i("KakaoMap", "title $title")
                            when (title) {
                                "ready" -> if (!ready && !failed) {
                                    ready = true; h.removeCallbacks(timeout)
                                    if (shownUrl != shown) send(v)
                                    Ui.refresh()
                                }
                                "fail" -> fail("page fail")
                            }
                        }
                        override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                            android.util.Log.i("KakaoMap", "console ${m.messageLevel()}: ${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                            return true
                        }
                    }
                }
            } catch (e: Throwable) { fail("create ${e.javaClass.simpleName}", false); return null }
            web = w; ready = false; shown = pts; shownUrl = pts
            val q = StringBuilder("?p=${f(pts[0])},${f(pts[1])}")
            if (pts[2] != null && pts[3] != null) q.append("&m=${f(pts[2])},${f(pts[3])}")
            if (pts[4] != null && pts[5] != null) q.append("&o=${f(pts[4])},${f(pts[5])}")
            h.postDelayed(timeout, READY_WAIT_MS * 2)          // 쪽이 시작조차 못 하면 (onPageStarted 전) 이만큼 뒤 물러나요. 임의
            w.loadUrl(BuildConfig.SERVER_URL + "/carmap" + q)
        } else if (pts != shown) {                         // 값이 바뀔 때만 표시를 옮겨요 (쪽은 다시 불러오지 않아요)
            shown = pts
            if (ready) send(w)
        }
        (w.parent as? android.view.ViewGroup)?.removeView(w)
        w.visibility = if (ready) View.VISIBLE else View.INVISIBLE
        return w
    }

    private var shownUrl: List<Double?>? = null            // 쪽 주소에 실은 좌표

    private fun send(w: android.webkit.WebView) {
        val s = shown ?: return
        shownUrl = s
        w.evaluateJavascript("upd(${s.joinToString(",") { f(it) }})", null)
    }

    /** 거래가 끝나 「만날 곳」을 떠나면 정리해요. 다음 거래에서 다시 시도해요. */
    fun release() {
        h.removeCallbacks(timeout)
        web?.let { w -> (w.parent as? android.view.ViewGroup)?.removeView(w); try { w.destroy() } catch (_: Exception) {} }
        web = null; ready = false; failed = false; shown = null; shownUrl = null
    }
}
