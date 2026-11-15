package ai.roadmarket.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/** 거래 알림이 켜진 동안 떠 있는 서비스. "로드마켓 거래 알림 켜짐" 알림을 계속 띄워 둬요. (C-04) */
class TradeService : Service() {
    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (!Market.tradeOn) { stopSelf(); return START_NOT_STICKY }
        try {
            startForeground(Notes.ID_ON, Notes.tradeOn(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (e: Throwable) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        fun start(c: Context): Boolean = try {
            c.startForegroundService(Intent(c, TradeService::class.java)); true
        } catch (e: Throwable) {
            android.util.Log.w("ROADMARKET", "service start blocked", e); false
        }

        fun stop(c: Context) { c.stopService(Intent(c, TradeService::class.java)) }
    }
}
