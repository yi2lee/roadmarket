package ai.roadmarket.phone

import android.content.Context
import android.content.SharedPreferences

/** 앱 전용 저장소예요. 로그인 표와 이미 알린 것들을 둬요. 토큰은 로그에 찍지 않아요. */
class Store(context: Context) {

    private val p: SharedPreferences = context.getSharedPreferences("watch", Context.MODE_PRIVATE)

    var token: String
        get() = p.getString(KEY_TOKEN, "") ?: ""
        set(v) = p.edit().putString(KEY_TOKEN, v).apply()

    /** 서버가 401로 거절한 표예요. 페이지에 남아 있어도 다시 받지 않아요. */
    var deadToken: String
        get() = p.getString(KEY_DEAD, "") ?: ""
        set(v) = p.edit().putString(KEY_DEAD, v).apply()

    /** 알림 허용을 이미 물어봤는지예요. 한 번만 물어요. */
    var askedNotify: Boolean
        get() = p.getBoolean(KEY_ASKED, false)
        set(v) = p.edit().putBoolean(KEY_ASKED, v).apply()

    /** 로그아웃·표 만료: 표만 지워요. 이미 알린 기록은 남겨서 같은 알림을 또 띄우지 않아요. */
    fun clearToken() = p.edit().remove(KEY_TOKEN).apply()

    // ── 이미 알린 id (종류별로 최근 것만) ─────────────────────

    fun seen(kind: String): List<String> =
        (p.getString("seen_$kind", "") ?: "").split('\n').filter { it.isNotEmpty() }

    /** 새 id를 뒤에 붙이고, 오래된 것부터 버려요. */
    fun addSeen(kind: String, ids: Collection<String>) {
        if (ids.isEmpty()) return
        val list = (seen(kind) - ids.toSet()) + ids
        p.edit().putString("seen_$kind", list.takeLast(SEEN_MAX).joinToString("\n")).apply()
    }

    private companion object {
        const val KEY_TOKEN = "token"
        const val KEY_DEAD = "dead_token"
        const val KEY_ASKED = "asked_notify"
        // 종류별로 기억할 id 수예요. 임의로 정한 값이에요.
        const val SEEN_MAX = 100
    }
}
