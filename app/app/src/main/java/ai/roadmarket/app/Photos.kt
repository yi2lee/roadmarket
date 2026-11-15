package ai.roadmarket.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 물건 사진 (B-30, C-24, S-01). 서버의 GET /photo/<id>에서 JPEG를 뒤에서 받아 메모리에만 둬요.
 * 받는 동안은 사진 자리 없이 그리고, 받으면 화면을 한 번 다시 그려요.
 */
object Photos {
    private val h = Handler(Looper.getMainLooper())
    private val net = Executors.newSingleThreadExecutor()       // 서버와 주고받기(Link)를 막지 않게 따로 받아요
    private val got = HashMap<String, Bitmap>()
    private val loading = HashSet<String>()
    private val failedAt = HashMap<String, Long>()
    private const val RETRY_MS = 10_000L                        // 못 받았으면 이만큼 뒤에 다시 받아요 (요건표에 없는 값)
    private const val MAX_SIDE = 256                            // 화면에는 작게만 그려서 줄여 받아요 (요건표에 없는 값)

    /** 받아 둔 사진. 없으면 받기를 시작하고 null을 돌려줘요. 메인 스레드에서만 불러요. */
    fun get(id: String): Bitmap? {
        if (id.isEmpty()) return null
        got[id]?.let { return it }
        if (id in loading) return null
        failedAt[id]?.let { if (SystemClock.elapsedRealtime() - it < RETRY_MS) return null }
        loading.add(id)
        net.execute {
            val b = try { fetch(id) } catch (e: Exception) { null }
            h.post {
                loading.remove(id)
                if (b != null) { got[id] = b; failedAt.remove(id); Ui.refresh() }
                else failedAt[id] = SystemClock.elapsedRealtime()
            }
        }
        return null
    }

    private fun fetch(id: String): Bitmap? {
        val c = URL(BuildConfig.SERVER_URL + "/photo/" + java.net.URLEncoder.encode(id, "UTF-8")).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 2000; c.readTimeout = 5000
            if (c.responseCode != 200) return null
            val bytes = c.inputStream.use { it.readBytes() }
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            var s = 1
            while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= MAX_SIDE) s *= 2
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
        } finally { c.disconnect() }
    }
}
