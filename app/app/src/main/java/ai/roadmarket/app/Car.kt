package ai.roadmarket.app

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import android.content.Context
import android.os.Handler
import android.os.Looper

/** 차 신호: 기어와 운전석 문. */
object Car {
    private val h = Handler(Looper.getMainLooper())
    private var vehicle: Vehicle? = null

    /** true = 선 상태(P). 기어를 못 읽으면 false(달리는 중)로 다뤄요. (C-13) */
    var parked = false
        private set

    fun init(c: Context) {
        try {
            val v = Vehicle(c)
            v.initialize()
            vehicle = v
            v.getGear().getCurrentGearSelectionState({ g -> h.post { setGear(g) } }, { h.post { setGear(null) } })
            v.getGear().registerGearSelection(object : GearSelectionListener {
                override fun onSelected(g: VehicleGear?) { h.post { setGear(g) } }
                override fun onFailed(e: Exception) { h.post { setGear(null) } }
            })
            v.getDoor().registerDoorPosition(DoorArea(DoorValue.ROW_1_LEFT), object : DoorPositionListener {
                override fun onPositionUpdated(p: Int?) { if (p != null) h.post { Market.onDriverDoor(p != 0) } }
                override fun onFailed(e: Exception) {}
            })
        } catch (e: Throwable) {
            setGear(null)
        }
    }

    private fun setGear(g: VehicleGear?) {
        val p = g == VehicleGear.GEAR_PARK
        if (p == parked) return
        parked = p
        Market.onGearChanged()
    }
}
