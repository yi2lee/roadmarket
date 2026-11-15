package ai.roadmarket.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 알림의 「수락」 「거절」 버튼. (S-21) */
class NoteReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.action) {
            ACCEPT -> Market.acceptRequest()
            REJECT -> Market.rejectRequest()
            URGENT_CLOSE -> Market.closeUrgentIn()
            ROUTE_YES -> Market.answerRoute(true)
            ROUTE_NO -> Market.answerRoute(false)
        }
    }

    companion object {
        const val ACCEPT = "ai.roadmarket.app.NOTE_ACCEPT"
        const val REJECT = "ai.roadmarket.app.NOTE_REJECT"
        const val URGENT_SELL = "ai.roadmarket.app.NOTE_URGENT_SELL"     // 앱을 열고 「판매하기」 입력 창을 띄워요 (Notes.card)
        const val URGENT_CLOSE = "ai.roadmarket.app.NOTE_URGENT_CLOSE"
        const val ROUTE_YES = "ai.roadmarket.app.NOTE_ROUTE_YES"
        const val ROUTE_NO = "ai.roadmarket.app.NOTE_ROUTE_NO"
    }
}

/**
 * 데모용 장치. demo.sh가 불러요. 화면에는 보이지 않아요. (요건표 7절)
 * 가짜 차 명령은 이제 서버로 가요. 여기는 이 차 안에서 하는 것만 남았어요.
 */
class DemoReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.getStringExtra("cmd")) {
            "id" -> resultData = Link.carId                                   // demo.sh가 서버에 "어느 차"인지 알리려고 물어요
            "dest" -> Navi.demoDestination()                                   // D-11
            "dest-clear" -> Navi.demoClear()                                   // D-11
            "pos" -> {                                                         // D-23
                val la = i.getStringExtra("lat")?.toDoubleOrNull(); val lo = i.getStringExtra("lon")?.toDoubleOrNull()
                if (la != null && lo != null) Link.setTestPos(la, lo)
            }
            "mycar" -> {                                                       // D-24
                val k = i.getStringExtra("kind").orEmpty().trim(); val n = i.getStringExtra("plate").orEmpty().trim()
                if (k.isNotEmpty() && n.isNotEmpty()) Link.setCarInfo(k, n)
            }
        }
    }
}
