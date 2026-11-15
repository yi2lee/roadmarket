package ai.roadmarket.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** 알림. 상시 알림 하나("로드마켓 거래 알림 켜짐")와, 다른 화면이 앞일 때 위에 뜨는 알림 (0-5). */
object Notes {
    const val CH_ON = "trade_on"
    const val CH_ALERT = "alert"
    const val ID_ON = 1
    private lateinit var app: Context
    private lateinit var nm: NotificationManager
    private var requestId = 0          // S-21 거래 요청 알림. 답하거나 요청이 끝나면 지워요 (S-22~S-24, S-29)
    private val taskIds = ArrayList<Int>()   // 지금 일로 뜬 알림들. 일이 끝나면 지워요 (0-5)
    private val endIds = ArrayList<Int>()    // 끝났다고 알리는 알림. 다음 일이 시작되거나 거래 알림을 끌 때 지워요 (0-5)
    private var nextId = 100

    fun init(c: Context) {
        app = c
        nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_ON, Texts.TRADE_ON_NOTE, NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, c.getString(R.string.app_name), NotificationManager.IMPORTANCE_HIGH))
    }

    fun tradeOn(): Notification =
        Notification.Builder(app, CH_ON).setContentTitle(Texts.TRADE_ON_NOTE).setSmallIcon(R.drawable.ic_note).setOngoing(true).build()

    /** 위에 뜨는 알림. 누르면 [screen] 화면이 열려요. 같은 번호로는 다시 안 뜨니 번호를 매번 바꿔요. */
    fun alert(title: String, text: String?, screen: Screen, withAnswerButtons: Boolean = false, endNotice: Boolean = false) {
        val id = nextId++
        val open = PendingIntent.getActivity(
            app, id,
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(MainActivity.EXTRA_SCREEN, screen.name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = Notification.Builder(app, CH_ALERT).setContentTitle(title).setSmallIcon(R.drawable.ic_note)
            .setContentIntent(open).setAutoCancel(true).setCategory(Notification.CATEGORY_MESSAGE)   // 이 종류여야 차 화면 위에 떠요 (시험으로 확인)
        if (text != null) b.setContentText(text)
        if (withAnswerButtons) {
            b.addAction(Notification.Action.Builder(null, Texts.ACCEPT, answer(id, NoteReceiver.ACCEPT)).build())
            b.addAction(Notification.Action.Builder(null, Texts.REJECT, answer(id + 100_000, NoteReceiver.REJECT)).build())
        }
        nm.notify(id, b.build())
        if (endNotice) endIds.add(id) else taskIds.add(id)
        if (withAnswerButtons) requestId = id
    }

    private var cardId = 0
    /** 급구·경로 추천 알림. 버튼 [actions]: (글, NoteReceiver 동작). URGENT_SELL은 앱을 열어 입력 창을 띄워요. */
    fun card(title: String, text: String?, screen: Screen, actions: List<Pair<String, String>>) {
        clearCard()
        val id = nextId++
        val open = PendingIntent.getActivity(app, id,
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(MainActivity.EXTRA_SCREEN, screen.name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = Notification.Builder(app, CH_ALERT).setContentTitle(title).setSmallIcon(R.drawable.ic_note)
            .setContentIntent(open).setAutoCancel(true).setCategory(Notification.CATEGORY_MESSAGE)
        if (text != null) b.setContentText(text)
        actions.forEachIndexed { i, (label, action) ->
            val pi = if (action == NoteReceiver.URGENT_SELL) PendingIntent.getActivity(app, id + 200_000 + i,
                Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(MainActivity.EXTRA_SCREEN, Screen.HOME.name)
                    .putExtra(MainActivity.EXTRA_URGENT_SELL, true), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                else answer(id + 200_000 + i, action)
            b.addAction(Notification.Action.Builder(null, label, pi).build())
        }
        nm.notify(id, b.build())
        cardId = id
    }

    fun clearCard() { if (cardId != 0) { nm.cancel(cardId); cardId = 0 } }

    private fun answer(code: Int, action: String) = PendingIntent.getBroadcast(
        app, code, Intent(app, NoteReceiver::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** 0-5: 일이 끝나면 그 일로 떴던 알림을 알림 목록에서 지워요. */
    fun endTask() {
        taskIds.forEach { nm.cancel(it) }
        taskIds.clear(); requestId = 0
    }

    fun clearEndNotices() {
        endIds.forEach { nm.cancel(it) }
        endIds.clear()
    }

    /** 앱이 다시 시작될 때: 지난번 일의 알림이 남아 있으면 지워요 (그 일은 사라졌어요, C-15). */
    fun clearLeftAlerts() {
        nm.activeNotifications.filter { it.notification.channelId == CH_ALERT }.forEach { nm.cancel(it.id) }
    }

    fun clearAlert() {
        if (requestId != 0) { nm.cancel(requestId); requestId = 0 }
    }
}
