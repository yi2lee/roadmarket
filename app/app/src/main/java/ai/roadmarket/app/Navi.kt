package ai.roadmarket.app

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NaviMode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.Looper

/** 내비: 가는 길이 있는지 보고, 만날 곳을 넣고 지워요. */
object Navi {
    enum class Inserted { WAYPOINT, DESTINATION }

    private val h = Handler(Looper.getMainLooper())
    private var helper: NaviHelper? = null
    private var connected = false
    private val whenConnected = ArrayList<() -> Unit>()

    /** 내비에 가는 길(목적지)이 있는지. */
    var hasRoute = false
        private set

    private class Pending(val kind: Inserted, val done: (Inserted?) -> Unit)
    private var pending: Pending? = null
    private val pendingTimeout = Runnable { finish(false) }

    fun init(c: Context) {
        try {
            val n = NaviHelper(c)
            helper = n
            n.initialize({
                h.post {
                    n.addListener(listener)
                    try { n.getRouteStateInfo() } catch (e: Throwable) {}
                    h.postDelayed({ ready() }, Config.NAVI_FIRST_STATE_MS)
                }
            }, { })
        } catch (e: Throwable) {
            helper = null
        }
    }

    /** 내비에 길이 있는지 처음 알게 된 뒤에, 기다리던 데모 명령을 돌려요. */
    private fun ready(route: Boolean? = null) {
        val first = !connected
        connected = true
        if (route != null) setRoute(route)
        if (!first) return
        val jobs = ArrayList(whenConnected); whenConnected.clear()
        jobs.forEach { it() }
    }

    private fun setRoute(v: Boolean) {
        if (!v) dest = null
        if (hasRoute == v) return
        hasRoute = v
        Market.onRouteChanged()
    }

    private fun finish(ok: Boolean) {
        val p = pending ?: return
        pending = null
        h.removeCallbacks(pendingTimeout)
        p.done(if (ok) p.kind else null)
    }

    /** 만날 곳 넣기. 경유지로(M-03) 또는 목적지로(M-04). 못 넣으면 null을 돌려줘요 (M-07). */
    fun insert(place: Config.Place, asWaypoint: Boolean, done: (Inserted?) -> Unit) {
        val n = helper
        if (n == null || !connected || pending != null) { done(null); return }
        if (asWaypoint && !hasRoute) { done(null); return }      // 가는 길이 없는데 경유지를 넣으면 내비에 오류 창이 떠요
        val kind = if (asWaypoint) Inserted.WAYPOINT else Inserted.DESTINATION
        pending = Pending(kind, done)
        h.postDelayed(pendingTimeout, Config.NAVI_ANSWER_MS)
        try {
            if (kind == Inserted.WAYPOINT) {
                n.addWaypoint(RequestWaypointInfo(place.lat, place.lon, place.name, WaypointIndex.FIRST, "", "", "", ""))
            } else {
                n.requestRoute(route(place))
            }
        } catch (e: Throwable) {
            finish(false)
        }
    }

    /**
     * 경로 추천·드라이브 코스의 경유지 넣기 (약속 5절). 거래의 만날 곳은 첫째 경유지라,
     * 거래 경유지가 이미 있으면 둘째 자리에 넣어요. 가는 길이 없으면 넣지 않아요(내비 오류 창).
     */
    fun addVia(place: Config.Place, second: Boolean, done: (Boolean) -> Unit) {
        val n = helper
        if (n == null || !connected || pending != null || !hasRoute) { done(false); return }
        pending = Pending(Inserted.WAYPOINT) { done(it != null) }
        h.postDelayed(pendingTimeout, Config.NAVI_ANSWER_MS)
        try {
            n.addWaypoint(RequestWaypointInfo(place.lat, place.lon, place.name, if (second) WaypointIndex.SECOND else WaypointIndex.FIRST, "", "", "", ""))
        } catch (e: Throwable) {
            finish(false)
        }
    }

    /** 만날 곳 지우기 (M-13). 경유지면 그 경유지만, 목적지면 길 안내를 끝내요. */
    fun remove(kind: Inserted) {
        val n = helper ?: return
        if (!connected || !hasRoute) return
        try {
            if (kind == Inserted.WAYPOINT) n.removeWaypoint(WaypointIndex.FIRST) else n.cancelRoute()
        } catch (e: Throwable) {
        }
    }

    /** D-11: 데모 목적지 넣기 / 길 안내 끝내기. */
    fun demoDestination() = run { n -> dest = Config.DEMO_DEST.lat to Config.DEMO_DEST.lon; n.requestRoute(route(Config.DEMO_DEST)) }
    fun demoClear() = run { n -> if (hasRoute) n.cancelRoute() }

    /** 내비가 알려 준 지금 위치. 모르면 null. (D-23 시험용 위치가 있으면 그것이 먼저예요) */
    var location: Pair<Double, Double>? = null
        private set
    /** 내비의 목적지 좌표. 모르면 null. */
    private var dest: Pair<Double, Double>? = null
    /** 서버에 올리는 목적지 (약속 2절 state.dest). 모르면 null. */
    val destination: Pair<Double, Double>? get() = if (hasRoute) dest else null
    private var lastAsk = 0L
    private val destWaiters = ArrayList<(Pair<Double, Double>?) -> Unit>()

    fun askLocation() {
        val n = helper ?: return
        val t = android.os.SystemClock.elapsedRealtime()
        if (!connected || t - lastAsk < Config.NAVI_LOCATION_EVERY_MS) return
        lastAsk = t
        try { n.getCurrentLocationInfo() } catch (e: Throwable) {}
    }

    /** 목적지 좌표를 돌려줘요. 모르면 내비에 묻고 잠깐 기다려요. 끝내 모르면 null. */
    fun withDestination(done: (Pair<Double, Double>?) -> Unit) {
        dest?.let { done(it); return }
        val n = helper
        if (n == null || !connected) { done(null); return }
        destWaiters.add(done)
        try { n.getDestinationInfo() } catch (e: Throwable) {}
        h.postDelayed({ if (destWaiters.remove(done)) done(dest) }, Config.NAVI_DEST_WAIT_MS)
    }

    private fun gotDestination(p: Pair<Double, Double>) {
        dest = p
        val w = ArrayList(destWaiters); destWaiters.clear()
        w.forEach { it(p) }
    }

    private fun run(job: (NaviHelper) -> Unit) {
        val n = helper ?: return
        val go = { try { job(n) } catch (e: Throwable) {} }
        if (connected) go() else whenConnected.add(go)
    }

    private fun route(p: Config.Place) = RouteInfo(p.lat, p.lon, null, null, p.name, "", "", "", RouteOption.RECOMMENDED, "", false)

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(i: RouteStartInfo) { h.post { try { helper?.getDestinationInfo() } catch (e: Throwable) {}; setRoute(true); if (pending?.kind == Inserted.DESTINATION) finish(true) } }
        override fun onRouteEnded() {}       // 길 안내가 시작될 때도 오는 신호라 쓰지 않아요. 길이 있는지는 onNavigationStatus로 봐요
        override fun onRoutingCompleted() {}
        override fun onReRouteEnded() {}
        override fun onRouteOptionChanged(o: RouteOption) {}
        override fun onRouteCancelled() { h.post { setRoute(false) } }
        override fun onWaypointChanged(i: WaypointChangedInfo) { h.post { if (pending?.kind == Inserted.WAYPOINT) finish(true) } }
        override fun onDestinationArrived(i: DestinationArrivedInfo) {}
        override fun onWayPointArrived(i: WaypointArrivedInfo) {}
        override fun onDestinationChanged() {}
        override fun onError(e: NaviErrorCode) { h.post { finish(false) } }
        override fun onCurrentLocationInfo(i: CurrentLocationInfo) { h.post { if (i.latitude != 0.0 || i.longitude != 0.0) location = i.latitude to i.longitude } }
        override fun onRouteStateInfo(i: RouteStateInfo) { h.post { ready(i.naviMode == NaviMode.ROUTE_GUIDING || i.naviMode == NaviMode.SIMULATION_GUIDING) } }
        override fun onDestinationInfo(i: DestinationInfo) { h.post { if (hasRoute && (i.latitude != 0.0 || i.longitude != 0.0)) gotDestination(i.latitude to i.longitude) } }
        override fun onWayPointInfo(l: List<WaypointInfo>) {}
        override fun onNavigationStatus(s: NavigationStatus) {
            h.post { if (s == NavigationStatus.ROUTE_DRIVE) setRoute(true) else if (s == NavigationStatus.FREE_DRIVE) setRoute(false) }
        }
        override fun onTBTInfo(l: List<TBTInfo>) {}
        override fun onTripInfo(i: TripInfo) {}
        override fun onDrivingInfo(i: DrivingInfo) {}
    }
}
