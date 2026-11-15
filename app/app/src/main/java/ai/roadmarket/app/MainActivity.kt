package ai.roadmarket.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

sealed class Overlay {
    /** 물건 올리기 입력 창. edit가 있으면 「수정」으로 연 것 (값이 채워져 열려요). */
    class SellInput(val edit: Market.Sell? = null) : Overlay() { var loc = edit?.loc ?: "car" }
    object UrgentInput : Overlay()           // 「급구 올리기」
    object UrgentSell : Overlay()            // 받은 급구에 「판매하기」
    object WantInput : Overlay()
    class RemoveSell(val sell: Market.Sell) : Overlay()
    class RemoveWant(val name: String) : Overlay()
    object CancelDeal : Overlay()
    object TurnOff : Overlay()
}

/** 지금 보이는 화면과 창. 상태 기계가 화면을 바꿀 때 여기를 불러요. */
object Ui {
    var front = false
    var screen = Screen.HOME
    var overlay: Overlay? = null
    var activity: MainActivity? = null
    /** C-01: 처음 열 때만 홈. 한 번 연 뒤에는 보던 화면을 그대로 둬요. */
    var opened = false

    private val taskScreens = setOf(Screen.NEARBY, Screen.REQUEST, Screen.MEET, Screen.ASK)

    /**
     * 0-5: 로드마켓 안의 화면을 바꿔요. 다른 화면(내비)이 앞이어도 로드마켓 안은 바뀌고, 앞의 화면은 그대로예요.
     * "홈으로 가요"는 하던 일 화면(주변 물건·거래 요청·만날 곳·거래 어땠어요?)에서만 홈이 돼요.
     * 사용자가 왼쪽 메뉴로 간 파는 물건·찾는 물건 화면과 그 입력 창은 그대로 둬요 (1절).
     * 하던 일 화면이 새로 뜨면 열린 창을 닫아요 (S-08).
     */
    fun go(s: Screen) {
        if (s == Screen.HOME) { if (screen in taskScreens) screen = Screen.HOME }
        else { screen = s; overlay = null }
        refresh()
    }

    fun refresh() { activity?.render() }

    /** S-08: 달리기 시작하면 입력 창을 닫아요. 확인 창은 그대로예요 (C-17). */
    fun gearChanged() {
        if (!Car.parked && (overlay is Overlay.SellInput || overlay is Overlay.WantInput || overlay is Overlay.UrgentInput || overlay is Overlay.UrgentSell)) overlay = null
        refresh()
    }
}

class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var p: Theme.Palette
    private var pendingScreen: Screen? = null
    private var shownOverlay: Overlay? = null
    private var overlayView: View? = null
    private var shownScreen: Screen? = null
    private var listScroll: ScrollView? = null
    private var narrow = false
    // 손가락이 닿아 있는 동안에는 화면을 다시 만들지 않아요. 다시 만들면 누르던 뷰가 사라져 그 터치가 먹지 않아요.
    private var touching = false
    private var renderAfterTouch = false
    private var touchDownAt = 0L
    private var pendingUrgentSell = false
    /** 열린 창을 다시 만들지 않고 안의 일부만 새 값으로 바꿔요 (입력한 글자가 지워지지 않게) */
    private var overlayRefresh: (() -> Unit)? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) { touching = true; touchDownAt = android.os.SystemClock.uptimeMillis() }
        val r = super.dispatchTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            touching = false
            if (renderAfterTouch) { renderAfterTouch = false; root.post { render() } }   // 눌림(클릭)이 먼저 처리된 뒤에 그려요
        }
        return r
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        root = FrameLayout(this)
        setContentView(root)
        Ui.activity = this
        readIntent(intent)
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        readIntent(i)
    }

    private fun readIntent(i: Intent?) {
        pendingScreen = i?.getStringExtra(EXTRA_SCREEN)?.let { n -> Screen.values().firstOrNull { it.name == n } }
        i?.removeExtra(EXTRA_SCREEN)
        if (i?.getBooleanExtra(EXTRA_URGENT_SELL, false) == true) { pendingUrgentSell = true; i.removeExtra(EXTRA_URGENT_SELL) }
    }

    override fun onStart() {
        super.onStart()
        Ui.front = true
        Notes.clearCard()                                  // 앞에 있을 때는 앱 안 카드만 보여요 (위에 뜬 급구·경로 알림은 치워요)
        if (!Ui.opened) { Ui.opened = true; Ui.screen = Screen.HOME; Ui.overlay = null }   // C-01: 처음 열면 홈
        render()
    }

    override fun onResume() {
        super.onResume()
        pendingScreen?.let { Ui.screen = it; pendingScreen = null; render() }   // 알림을 눌러 연 화면
        if (pendingUrgentSell) {                                                // 급구 알림의 「판매하기」
            pendingUrgentSell = false
            if (Car.parked && Market.urgentIn != null) { Ui.overlay = Overlay.UrgentSell; render() }
        }
    }

    override fun onStop() {
        Ui.front = false
        touching = false
        super.onStop()
    }

    override fun onDestroy() {
        if (Ui.activity === this) Ui.activity = null
        super.onDestroy()
    }

    override fun onConfigurationChanged(c: Configuration) {
        super.onConfigurationChanged(c)
        shownOverlay = null              // 낮·밤이 바뀌면 창도 새 색으로 다시 그려요
        render()
    }

    // ───────── 그리기 ─────────

    fun render() {
        // 손을 뗀 신호를 못 받는 경우(창이 바뀜 등)에 화면이 멈추지 않게 2초가 지나면 그냥 그려요
        if (touching && android.os.SystemClock.uptimeMillis() - touchDownAt < 2000) { renderAfterTouch = true; return }
        touching = false
        p = Theme.palette(this)
        narrow = resources.configuration.screenWidthDp < Theme.NARROW_BELOW
        // 상태가 사라진 화면은 홈으로
        val ok = when (Ui.screen) {
            Screen.NEARBY -> Market.nearby != null
            Screen.REQUEST -> Market.incoming != null
            Screen.MEET -> Market.deal?.let { it.stage != Market.Stage.ASK } == true
            Screen.ASK -> Market.deal?.stage == Market.Stage.ASK
            else -> true
        }
        if (!ok) Ui.screen = Screen.HOME
        if (Market.meet == null || Market.deal == null || Market.deal?.stage == Market.Stage.ASK) KakaoMap.release()   // 거래가 끝나면 카카오 지도 WebView를 정리해요
        when (val o = Ui.overlay) {
            is Overlay.SellInput, is Overlay.WantInput -> if (!Car.parked) Ui.overlay = null
            is Overlay.UrgentInput -> if (!Car.parked || Market.myUrgent != null) Ui.overlay = null
            is Overlay.UrgentSell -> if (!Car.parked || Market.urgentIn == null) Ui.overlay = null
            is Overlay.RemoveSell -> if (Market.sellBusy(o.sell.id) || Market.sells.none { it.id == o.sell.id }) Ui.overlay = null
            is Overlay.RemoveWant -> if (!Market.hasWant(o.name)) Ui.overlay = null
            is Overlay.CancelDeal -> if (Market.deal == null || Market.deal?.stage == Market.Stage.ASK) Ui.overlay = null
            is Overlay.TurnOff -> if (!Market.tradeOn) Ui.overlay = null
            null -> {}
        }

        val keepScroll = if (shownScreen == Ui.screen) listScroll?.scrollY ?: 0 else 0
        listScroll = null
        val main = LinearLayout(this).apply {
            orientation = if (narrow) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            setBackgroundColor(p.background)
        }
        main.addView(menu(), if (narrow) LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT) else LinearLayout.LayoutParams(Theme.MENU_WIDTH, MATCH_PARENT))
        main.addView(View(this).apply { setBackgroundColor(p.divider) }, if (narrow) LinearLayout.LayoutParams(MATCH_PARENT, 2) else LinearLayout.LayoutParams(2, MATCH_PARENT))
        val content = when (Ui.screen) {
            Screen.HOME -> home()
            Screen.SELLS -> sells()
            Screen.WANTS -> wants()
            Screen.NEARBY -> nearby()
            Screen.REQUEST -> request()
            Screen.MEET -> meet()
            Screen.ASK -> askScreen()
        }
        // 급구·경로 추천 카드는 어느 화면이든 맨 위에 (약속 4·5절 "앱 안 카드")
        val cards = topCards()
        val framed = if (cards == null) content else column().apply {
            addView(cards, wide()); addView(gap()); addView(content, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        framed.setPadding(Theme.SCREEN_PAD, Theme.SCREEN_PAD, Theme.SCREEN_PAD, Theme.SCREEN_PAD)
        main.addView(framed, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).also { if (narrow) { it.width = MATCH_PARENT; it.height = 0 } })
        shownScreen = Ui.screen
        listScroll?.let { sv -> sv.post { sv.scrollTo(0, keepScroll) } }

        val keptOverlay = if (Ui.overlay != null && Ui.overlay === shownOverlay) overlayView else null
        root.removeAllViews()
        root.addView(main, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        if (Ui.overlay == null) {
            if (shownOverlay != null) hideKeyboard()
            overlayView = null
            overlayRefresh = null
        } else {
            if (keptOverlay != null) overlayRefresh?.invoke() else overlayRefresh = null
            overlayView = keptOverlay ?: overlay(Ui.overlay!!)
            root.addView(overlayView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        shownOverlay = Ui.overlay
    }

    // ───────── 작은 부품 ─────────

    private fun text(s: String, t: Theme.Type, color: Int): TextView = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_PX, t.size.toFloat())
        lineHeight = t.line
        typeface = face(t)
        setTextColor(color)
        includeFontPadding = false
        // 한국어 줄바꿈을 글자 단위가 아니라 말 덩어리 단위로 (0-6: 긴 이름은 줄을 바꿔 다 보여 줘요)
        if (android.os.Build.VERSION.SDK_INT >= 33) lineBreakWordStyle = android.graphics.text.LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE
    }

    /** 글자 굵기. 시스템 글꼴에 그 굵기가 없으면 가장 가까운 굵기(굵게)로 그려져요. */
    private fun face(t: Theme.Type): Typeface = Typeface.create(Typeface.DEFAULT, t.weight, false)

    private fun fill(color: Int, radius: Int, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius.toFloat()
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

    private fun pressable(normal: Int, pressed: Int, radius: Int) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), fill(pressed, radius))
        addState(intArrayOf(), fill(normal, radius))
    }

    /**
     * 공식 기본 버튼(ivi_basic_button). strong = 가장 중요한 버튼 → 공식 Secondary(진한 채움),
     * 그 밖의 버튼 → 공식 Primary(옅은 채움). size: 화면·창 버튼 Large, 목록 줄 안 버튼 Medium.
     */
    private fun button(label: String, strong: Boolean = false, size: Theme.ButtonSize = Theme.BUTTON_LARGE, action: () -> Unit): TextView =
        text(label, size.type, 0).apply {
            gravity = Gravity.CENTER
            // 좁은 창에서 긴 버튼 이름이 잘리지 않게 두 줄까지. 높이가 고정이라 위아래 여백은 0으로 두고 가운데 정렬해요
            // (한 줄일 때 위아래 빈칸 Large 30·Medium 21로 공식 16보다 넓어요. 두 줄 Large 80, Medium 76이 높이 안에 들어가요).
            maxLines = 2
            minimumHeight = size.height
            minimumWidth = size.minWidth
            setPadding(size.padH, 0, size.padH, 0)
            val (normal, pressed, disabled) = if (strong) Triple(p.dark, p.darkPressed, p.darkDisabled) else Triple(p.light, p.lightPressed, p.lightDisabled)
            setTextColor(android.content.res.ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(p.textDisabled, if (strong) p.darkText else p.lightText),
            ))
            background = StateListDrawable().apply {
                addState(intArrayOf(-android.R.attr.state_enabled), fill(disabled, size.radius, Theme.BUTTON_DISABLED_BORDER, p.disabledBorder))
                addState(intArrayOf(android.R.attr.state_pressed), fill(pressed, size.radius))
                addState(intArrayOf(), fill(normal, size.radius))
            }
            isSoundEffectsEnabled = false
            setOnClickListener { action() }
        }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun rowOf() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun gap(px: Int = Theme.GAP) = View(this).apply { layoutParams = LinearLayout.LayoutParams(px, px) }
    private fun wrap() = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
    private fun wide() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    private fun grow() = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)

    private fun card() = column().apply {
        background = fill(p.surface, Theme.CARD_RADIUS)
        setPadding(Theme.CARD_PAD, Theme.CARD_PAD, Theme.CARD_PAD, Theme.CARD_PAD)
        minimumHeight = Theme.ROW_MIN_HEIGHT
    }

    /** 손으로 넘기는 스크롤만 있어요. 저절로 움직이지 않아요. */
    private fun scroll(inner: View) = ScrollView(this).apply {
        overScrollMode = View.OVER_SCROLL_NEVER
        isScrollbarFadingEnabled = false
        isFillViewport = true
        addView(inner, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    /** 버튼을 가로로 나란히. 좁은 창에서도 한 줄에 같은 폭으로. */
    private fun buttons(vararg b: View, even: Boolean = false) = rowOf().apply {
        b.forEachIndexed { i, v ->
            if (i > 0) addView(gap())
            addView(v, if (even || narrow) LinearLayout.LayoutParams(0, Theme.BUTTON_LARGE.height, 1f) else LinearLayout.LayoutParams(WRAP_CONTENT, Theme.BUTTON_LARGE.height))
        }
    }

    // ───────── 왼쪽 메뉴 (C-02) ─────────

    private fun menu(): View {
        val box = LinearLayout(this).apply { orientation = if (narrow) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        if (!narrow) box.setPadding(0, Theme.SCREEN_PAD, 0, 0)
        for ((label, target) in listOf(Texts.MENU_HOME to Screen.HOME, Texts.MENU_SELLS to Screen.SELLS, Texts.MENU_WANTS to Screen.WANTS)) {
            // 주변 물건, 거래 요청, 만날 곳, 거래 어땠어요?에서는 「홈」이 밝아요 (요건표 1절)
            val on = Ui.screen == target || (target == Screen.HOME && Ui.screen != Screen.SELLS && Ui.screen != Screen.WANTS)
            val item = FrameLayout(this).apply {
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), android.graphics.drawable.ColorDrawable(p.surface))
                    addState(intArrayOf(), android.graphics.drawable.ColorDrawable(if (on) p.surface else p.background))
                }
                isSoundEffectsEnabled = false
                setOnClickListener { Ui.screen = target; Ui.overlay = null; render() }
            }
            item.addView(
                text(label, if (on) Theme.TITLE_BOLD else Theme.TITLE, if (on) p.text else p.textSub).apply { gravity = Gravity.CENTER_VERTICAL; maxLines = 1 },
                FrameLayout.LayoutParams(if (narrow) WRAP_CONTENT else MATCH_PARENT, MATCH_PARENT, Gravity.CENTER).also { if (!narrow) it.leftMargin = Theme.SCREEN_PAD },
            )
            if (on) item.addView(
                View(this).apply { setBackgroundColor(p.accent) },
                if (narrow) FrameLayout.LayoutParams(MATCH_PARENT, Theme.MENU_BAR_WIDTH, Gravity.BOTTOM) else FrameLayout.LayoutParams(Theme.MENU_BAR_WIDTH, MATCH_PARENT, Gravity.LEFT),
            )
            box.addView(item, if (narrow) LinearLayout.LayoutParams(0, Theme.ROW_MIN_HEIGHT, 1f) else LinearLayout.LayoutParams(MATCH_PARENT, Theme.ROW_MIN_HEIGHT))
        }
        return box
    }

    // ───────── 공통 틀: 제목, 맨 아래 버튼 (1절, 9-2) ─────────

    /** 1절: 화면과 입력 창 맨 위의 이름. */
    private fun title(s: String) = text(s, Theme.HEADLINE_SMALL, p.text)

    /** 9-2: 버튼을 가로로 채워 똑같이 나눠요. null 자리는 비워 둬서 다른 버튼 자리가 바뀌지 않아요 (B-21). */
    private fun fullRow(vararg b: View?) = rowOf().apply {
        b.forEachIndexed { i, v ->
            if (i > 0) addView(gap())
            addView(v ?: View(this@MainActivity), LinearLayout.LayoutParams(0, Theme.BUTTON_LARGE.height, 1f))
        }
    }

    /** 화면 틀: 맨 위 제목 + 손으로 넘기는 내용 + 화면 맨 아래 버튼(있으면). */
    private fun page(head: List<View>, body: View, bar: View? = null): LinearLayout {
        val c = column()
        head.forEach { c.addView(it, wide()) }
        c.addView(gap())
        c.addView(scroll(body).also { listScroll = it }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        if (bar != null) { c.addView(gap()); c.addView(bar, wide()) }
        return c
    }

    /** 물건 이름과 가격 한 줄. 이름이 길면 줄을 바꾸고 가격은 오른쪽에 남아요 (0-6). */
    private fun nameAndPrice(name: String, price: Long, t: Theme.Type = Theme.TITLE_BOLD, color: Int = p.text, priceType: Theme.Type = Theme.TITLE) = rowOf().apply {
        addView(text(name, t, color), grow())
        addView(gap())
        addView(text(Texts.won(price), priceType, color), wrap())
    }

    /** 배지: 옅은 버튼과 같은 채움, 보조 글자 크기. 색으로 뜻을 나누지 않아요. */
    private fun badge(s: String) = text(s, Theme.BODY_SMALL, p.text).apply {
        background = fill(p.light, Theme.BUTTON_MEDIUM.radius)
        setPadding(Theme.BADGE_PAD_H, Theme.BADGE_PAD_V, Theme.BADGE_PAD_H, Theme.BADGE_PAD_V)
        maxLines = 1
    }

    /** 설명 한두 줄. 넘으면 말줄임. */
    private fun descText(s: String, lines: Int, t: Theme.Type = Theme.BODY_SMALL) = text(s, t, p.textSub).apply {
        maxLines = lines; ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private fun small(px: Int = Theme.GAP / 3) = gap(px)

    // ───────── 급구·경로 추천 카드 (약속 4·5·8절) ─────────
    private fun topCards(): View? {
        val u = Market.urgentIn
        val r = Market.routeOffer?.takeIf { !Market.inProgress }               // 요청·거래 중에는 경로 추천 카드를 안 보여요
        if (u == null && r == null) return null
        val c = column()
        if (u != null) {
            val k = card()
            k.addView(text(Texts.urgentTitle(u.name), Theme.TITLE_BOLD, p.text), wide())
            k.addView(small())
            k.addView(text(Texts.urgentText(u.desc, u.min, u.nick), Theme.BODY_LARGE, p.textSub), wide())
            k.addView(gap())
            if (Car.parked) k.addView(fullRow(button(Texts.CLOSE) { Market.closeUrgentIn() },
                button(Texts.URGENT_SELL, true) { Ui.overlay = Overlay.UrgentSell; render() }), wide())
            else {
                k.addView(text(Texts.URGENT_SELL_DRIVING, Theme.BODY_LARGE, p.text), wide())
                k.addView(gap())
                k.addView(fullRow(button(Texts.CLOSE) { Market.closeUrgentIn() }, null), wide())
            }
            c.addView(k, wide())
        }
        if (r != null) {
            if (u != null) c.addView(gap())
            val k = card()
            k.addView(text(r.text, Theme.TITLE_BOLD, p.text), wide())
            k.addView(gap())
            k.addView(fullRow(button(Texts.ROUTE_NO) { Market.answerRoute(false) }, button(Texts.ROUTE_GO, true) { Market.answerRoute(true) }), wide())
            c.addView(k, wide())
        }
        return c
    }

    /** B-30, S-01: 물건 사진. 받아 둔 사진이 없으면 null이라 자리 자체가 없어요. 누를 수 없어요. */
    private fun photo(id: String): View? {
        val b = Photos.get(id) ?: return null
        return ImageView(this).apply {
            setImageBitmap(b)
            scaleType = ImageView.ScaleType.CENTER_CROP
            isClickable = false; isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(Theme.PHOTO_SIZE, Theme.PHOTO_SIZE)
        }
    }

    /** 사진이 있으면 앞에 사진과 간격을 넣어요. */
    private fun addPhoto(row: LinearLayout, id: String) {
        val v = photo(id) ?: return
        row.addView(v, LinearLayout.LayoutParams(Theme.PHOTO_SIZE, Theme.PHOTO_SIZE))
        row.addView(gap())
    }

    private fun measure(s: String, t: Theme.Type): Int {
        val pt = Paint().apply { textSize = t.size.toFloat(); typeface = face(t) }
        return kotlin.math.ceil(pt.measureText(s)).toInt()
    }

    // ───────── 홈 (C-01, C-03, C-04, C-06, C-08, C-11, C-12, C-21, C-22) ─────────

    private fun home(): LinearLayout {
        val c = column()
        // 「거래 알림」 줄 전체(글자 포함)가 스위치예요. 스위치가 공식 80x48로 작아져서 줄 높이 96 이상을 누르는 칸으로 써요.
        val top = rowOf().apply {
            minimumHeight = Theme.TOUCH_MIN
            isSoundEffectsEnabled = false
            setOnClickListener {
                when {
                    !Market.tradeOn -> Market.turnOn()                                     // C-04
                    Market.inProgress -> { Ui.overlay = Overlay.TurnOff; render() }        // C-08
                    else -> Market.turnOff()                                               // C-06
                }
            }
        }
        top.addView(text(Texts.TRADE, Theme.TITLE_BOLD, p.text), grow())
        top.addView(text(if (Market.tradeOn) Texts.SWITCH_ON else Texts.SWITCH_OFF, Theme.TITLE_BOLD, p.text), wrap())
        top.addView(gap())
        top.addView(TradeSwitch(this, p, Market.tradeOn), LinearLayout.LayoutParams(Theme.SWITCH_WIDTH, Theme.SWITCH_HEIGHT))
        c.addView(top, wide())
        c.addView(gap())
        val status = if (!Link.reachable) Texts.SERVER_DOWN else if (!Market.tradeOn) Texts.STATUS_OFF else if (Market.inProgress) Texts.STATUS_BUSY else Texts.STATUS_ON
        c.addView(text(status, Theme.BODY_LARGE, p.textSub), wide())
        Market.justEnded?.let { c.addView(gap()); c.addView(text(Texts.just(it), Theme.BODY_LARGE, p.text), wide()) }               // C-22
        if (Market.sells.isEmpty() && Market.wants.isEmpty()) { c.addView(gap()); c.addView(text(Texts.HOME_EMPTY, Theme.BODY_LARGE, p.text), wide()) }   // C-21
        if (Market.tradeOn && Market.serverMode && Market.nearby == null && !Market.inProgress) {           // 직접 여는 주변 물건 목록
            c.addView(gap()); c.addView(buttons(button(Texts.BROWSE) { Market.openBrowse() }), wide())
        }
        c.addView(gap())

        val counts = LinearLayout(this).apply { orientation = if (narrow) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }
        val tiles = listOf(Texts.countSells(Market.sells.size), Texts.countWants(Market.wants.size), Texts.countDeals(Market.dealCount))
        tiles.forEachIndexed { i, s ->
            if (i > 0) counts.addView(gap())
            val cut = s.lastIndexOf(' ')
            val tile = card()
            tile.addView(text(s.substring(0, cut), Theme.BODY_SMALL, p.textSub))
            tile.addView(text(s.substring(cut + 1), Theme.TITLE_BOLD, p.text))
            counts.addView(tile, if (narrow) wide() else grow())
        }
        c.addView(counts, wide())

        val d = Market.deal
        val (label, target) = when {
            d != null && d.stage == Market.Stage.ASK -> Texts.CARD_ASK to Screen.ASK
            d != null -> Texts.CARD_MEET to Screen.MEET
            Market.incoming != null -> Texts.CARD_REQUEST to Screen.REQUEST
            Market.nearby != null -> Texts.cardNearby(Market.nearby!!.rows.size) to Screen.NEARBY
            Market.urgentOffers.isNotEmpty() -> Texts.cardUrgentOffers(Market.urgentOffers.size) to Screen.WANTS
            else -> null to Screen.HOME
        }
        if (label != null) {
            c.addView(gap())
            val card = rowOf().apply {
                background = pressable(p.dark, p.darkPressed, Theme.CARD_RADIUS)        // C-12: 누르는 것이라 공식 진한 버튼(Secondary) 채움
                setPadding(Theme.CARD_PAD, Theme.CARD_PAD, Theme.CARD_PAD, Theme.CARD_PAD)
                minimumHeight = Theme.ROW_MIN_HEIGHT
                isSoundEffectsEnabled = false
                setOnClickListener { Ui.screen = target; render() }
            }
            card.addView(text(label, Theme.TITLE_BOLD, p.darkText), grow())
            c.addView(card, wide())
        }
        if (Link.reachable && Link.pair.isNotEmpty()) {                                // C-23: 홈 맨 아래
            c.addView(gap())
            c.addView(text(if (Market.linked) Texts.PHONE_LINKED else Texts.pairLine(Link.pair), Theme.BODY_LARGE, p.textSub), wide())
        }
        return page(listOf(title(Texts.MENU_HOME)), c)
    }

    // ───────── 파는 물건 (S-01~S-12) ─────────

    /** S-07, B-07: 달리는 중 안내 두 줄. 첫 줄 작게, 말할 문장 크게. */
    private fun drivingHint(small: String, big: String) = column().apply {
        addView(text(small, Theme.BODY_SMALL, p.textSub), wide())
        addView(gap())
        addView(text(big, Theme.TITLE, p.text), wide())
    }

    private fun sells(): LinearLayout {
        val c = column()
        if (Car.parked) c.addView(buttons(button(Texts.SELL_ADD) { Ui.overlay = Overlay.SellInput(); render() }), wide())
        else c.addView(drivingHint(Texts.SELLS_DRIVING_1, Texts.SELLS_DRIVING_2), wide())
        c.addView(gap())
        if (Market.sells.isEmpty()) {
            c.addView(text(Texts.SELLS_EMPTY_1, Theme.TITLE, p.text), wide())
            c.addView(gap())
            c.addView(text(Texts.SELLS_EMPTY_2, Theme.BODY_LARGE, p.textSub), wide())
        } else for (s in Market.sells) {
            c.addView(sellRow(s), wide())
            c.addView(gap())
        }
        return page(listOf(title(Texts.MENU_SELLS)), c)
    }

    /** 파는 물건 한 줄: 사진·이름·가격·설명 두 줄·위치 배지·상태 배지, 선 상태면 아래에 버튼 한 줄. */
    private fun sellRow(s: Market.Sell): View {
        val st = Market.statusOf(s)
        val dim = s.hidden || st == "sold"
        val box = column().apply {
            background = fill(p.surface, Theme.ROW_RADIUS)
            setPadding(Theme.CARD_PAD, Theme.CARD_PAD, Theme.CARD_PAD, Theme.CARD_PAD)
            minimumHeight = Theme.ROW_MIN_HEIGHT
        }
        val top = rowOf()
        addPhoto(top, s.photo)
        val mid = column()
        mid.addView(nameAndPrice(s.name, s.price, color = if (dim) p.textDim else p.text), wide())
        if (s.desc.isNotEmpty()) { mid.addView(small()); mid.addView(descText(s.desc, 2), wide()) }
        mid.addView(small(Theme.GAP / 2))
        mid.addView(rowOf().apply { addView(badge(Texts.locBadge(s.loc)), wrap()); addView(gap(Theme.GAP / 2)); addView(badge(Texts.statusBadge(st, s.hidden)), wrap()) }, wide())
        top.addView(mid, grow())
        box.addView(top, wide())
        if (Car.parked && st != "deal") {                                             // 달리는 중·거래 중에는 버튼이 없어요
            val m = Theme.BUTTON_MEDIUM
            val bs = listOf(
                button(Texts.SELL_EDIT, size = m) { Ui.overlay = Overlay.SellInput(s); render() },
                button(if (s.hidden) Texts.UNHIDE else Texts.HIDE, size = m) { Market.hideSell(s.id, !s.hidden) },
                button(if (st == "sold") Texts.TO_SALE else Texts.TO_SOLD, size = m) { Market.setSellStatus(s.id, if (st == "sold") "sale" else "sold") },
                button(Texts.SELL_REMOVE, size = m) { Ui.overlay = Overlay.RemoveSell(s); render() },
            )
            // 한 줄에 다 안 들어가는 좁은 창에서는 두 줄로
            val lines = if (narrow) listOf(bs.subList(0, 2), bs.subList(2, 4)) else listOf(bs)
            lines.forEach { line ->
                box.addView(gap())
                box.addView(rowOf().apply { line.forEachIndexed { i, b -> if (i > 0) addView(gap()); addView(b, LinearLayout.LayoutParams(WRAP_CONTENT, m.height)) } }, wide())
            }
        }
        return box
    }

    private fun itemRow(name: String, price: String?, right: View?, photoId: String = ""): View {
        val r = rowOf().apply {
            background = fill(p.surface, Theme.ROW_RADIUS)
            setPadding(Theme.CARD_PAD, Theme.CARD_PAD, Theme.CARD_PAD, Theme.CARD_PAD)
            minimumHeight = Theme.ROW_MIN_HEIGHT
        }
        addPhoto(r, photoId)                                                           // S-01, C-24: 줄 왼쪽
        r.addView(text(name, Theme.TITLE_BOLD, p.text), grow())
        if (price != null) { r.addView(gap()); r.addView(text(price, Theme.TITLE, p.text), wrap()) }
        if (right != null) {
            r.addView(gap())
            r.addView(right, if (right.isClickable) LinearLayout.LayoutParams(WRAP_CONTENT, Theme.BUTTON_MEDIUM.height) else wrap())
        }
        return r
    }

    // ───────── 찾는 물건 (B-01~B-10) ─────────

    private fun wants(): LinearLayout {
        val c = column()
        val alert = if (Market.serverMode) button(if (Market.wantsAlert) Texts.ALERT_OFF else Texts.ALERT_ON) { Market.setWantsAlert(!Market.wantsAlert) } else null
        if (Car.parked) c.addView(if (alert != null) buttons(button(Texts.WANT_ADD) { Ui.overlay = Overlay.WantInput; render() }, alert) else buttons(button(Texts.WANT_ADD) { Ui.overlay = Overlay.WantInput; render() }), wide())
        else {
            if (alert != null) { c.addView(buttons(alert), wide()); c.addView(gap()) }
            c.addView(drivingHint(Texts.WANTS_DRIVING_1, Texts.WANTS_DRIVING_2), wide())
        }
        if (Market.serverMode && !Market.wantsAlert) { c.addView(gap()); c.addView(text(Texts.ALERT_IS_OFF, Theme.BODY_LARGE, p.text), wide()) }
        if (Market.serverMode) urgentSection(c)
        c.addView(gap())
        if (Market.wants.isEmpty()) {
            c.addView(text(Texts.WANTS_EMPTY_1, Theme.TITLE, p.text), wide())
            c.addView(gap())
            c.addView(text(Texts.WANTS_EMPTY_2, Theme.BODY_LARGE, p.textSub), wide())
        } else for (w in Market.wants) {
            val right = if (Car.parked) button(Texts.WANT_REMOVE, size = Theme.BUTTON_MEDIUM) { Ui.overlay = Overlay.RemoveWant(w); render() } else null
            c.addView(itemRow(w, null, right), wide())
            c.addView(gap())
        }
        return page(listOf(title(Texts.MENU_WANTS)), c)
    }

    // ───────── 급구 (약속 4절, 8절) ─────────
    private fun urgentSection(c: LinearLayout) {
        c.addView(gap())
        val mine = Market.myUrgent
        if (mine == null) {
            if (Car.parked) c.addView(buttons(button(Texts.URGENT_POST) { Ui.overlay = Overlay.UrgentInput; render() }.apply { isEnabled = Market.tradeOn }), wide())
            else c.addView(drivingHint(Texts.URGENT_DRIVING_1, Texts.URGENT_DRIVING_2), wide())
            return
        }
        c.addView(rowOf().apply {
            addView(text(Texts.urgentMine(mine.name), Theme.TITLE_BOLD, p.text), grow())
            addView(gap())
            addView(button(Texts.URGENT_CANCEL, size = Theme.BUTTON_MEDIUM) { Market.cancelUrgent() }, LinearLayout.LayoutParams(WRAP_CONTENT, Theme.BUTTON_MEDIUM.height))
        }, wide())
        if (Market.urgentOffers.isEmpty()) return
        c.addView(gap())
        c.addView(text(Texts.URGENT_OFFERS_TITLE, Theme.BODY_SMALL, p.textSub), wide())
        val btnW = maxOf(Theme.BUTTON_MEDIUM.minWidth, Market.urgentOffers.maxOf { measure(Texts.pick(it.no), Theme.BUTTON_MEDIUM.type) } + 2 * Theme.BUTTON_MEDIUM.padH)
        for (o in Market.urgentOffers) {
            c.addView(small(Theme.GAP / 2))
            val box = rowOf().apply {
                background = fill(p.surface, Theme.ROW_RADIUS)
                setPadding(Theme.CARD_PAD, Theme.CARD_PAD, Theme.CARD_PAD, Theme.CARD_PAD)
                minimumHeight = Theme.ROW_MIN_HEIGHT
            }
            box.addView(text(Texts.no(o.no), Theme.TITLE_BOLD, p.text), wrap())
            box.addView(gap())
            val mid = column()
            mid.addView(rowOf().apply {
                addView(text(o.name, Theme.TITLE_BOLD, p.text), grow()); addView(gap())
                addView(text(Texts.priceOrShare(o.price), Theme.TITLE, p.text), wrap())
            }, wide())
            if (o.desc.isNotEmpty()) { mid.addView(small()); mid.addView(descText(o.desc, 2), wide()) }
            mid.addView(small())
            mid.addView(text(Texts.minutes(o.min) + " · " + Texts.sellerLine(o.nick, o.deals, o.rating), Theme.BODY_SMALL, p.textSub), wide())
            box.addView(mid, grow())
            box.addView(gap())
            box.addView(button(Texts.pick(o.no), true, Theme.BUTTON_MEDIUM) { Market.pickUrgent(o.no) }.apply { isEnabled = !Market.inProgress },
                LinearLayout.LayoutParams(btnW, Theme.BUTTON_MEDIUM.height))
            c.addView(box, wide())
        }
    }

    // ───────── 주변 물건 (B-21, B-29, B-40~B-54) ─────────

    private fun nearby(): LinearLayout {
        val nb = Market.nearby ?: return column()
        // B-21: 상태가 바뀌어도 줄 높이와 글 자리가 안 움직이게 상태 글 칸과 버튼 칸의 폭을 고정해요.
        // 줄 안 버튼은 폭이 모두 같아요 (9-2). 폭은 가장 긴 글자에 맞춰 재요.
        val btnW = maxOf(Theme.BUTTON_MEDIUM.minWidth,
            (listOf(Texts.SEND) + nb.rows.map { Texts.pick(it.no) }).maxOf { measure(it, Theme.BUTTON_MEDIUM.type) } + 2 * Theme.BUTTON_MEDIUM.padH)
        val stateW = listOf(Texts.WAITING, Texts.ACCEPTED, Texts.REJECTED, Texts.NO_ANSWER, Texts.CANCELLED, Texts.BUSY_OTHER).maxOf { measure(it, Theme.BODY_LARGE) }
        val pad = if (narrow) Theme.NARROW_ROW_PAD else Theme.CARD_PAD
        val list = column()
        // 직접 연 목록: 달리는 중에는 가장 가까운 세 개만 크게, 선 상태에서는 전체
        val big = nb.browse && !Car.parked
        val shown = if (big) nb.rows.take(Config.NEARBY_MAX) else nb.rows
        val nameT = if (big) Theme.HEADLINE_SMALL else Theme.TITLE_BOLD
        val subT = if (big) Theme.BODY_LARGE else Theme.BODY_SMALL
        if (nb.rows.isEmpty()) list.addView(text(Texts.NEARBY_EMPTY, Theme.TITLE, p.text), wide())
        for (r in shown) {
            val ended = r.gone || r.busy || r.state == Market.RowState.REJECTED || r.state == Market.RowState.NO_ANSWER || r.state == Market.RowState.CANCELLED
            val main = if (ended) p.textDim else p.text                               // 끝난 줄은 흐린 글자 (B-21)
            val box = LinearLayout(this).apply {
                orientation = if (narrow) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = fill(p.surface, Theme.ROW_RADIUS)
                setPadding(pad, pad, pad, pad)
                minimumHeight = if (big) Theme.ROW_BIG_MIN_HEIGHT else Theme.ROW_MIN_HEIGHT
            }
            val info = rowOf()
            addPhoto(info, r.car.photo)                                               // B-30: 줄 왼쪽
            info.addView(text(Texts.no(r.no), nameT, main), wrap())
            info.addView(gap())
            val mid = column()
            mid.addView(nameAndPrice(r.car.item, r.car.price, nameT, main, if (big) Theme.TITLE_BOLD else Theme.TITLE), wide())
            if (r.car.desc.isNotEmpty()) { mid.addView(small()); mid.addView(descText(r.car.desc, if (big) 1 else 2, subT), wide()) }
            mid.addView(small())
            val l2 = rowOf()
            if (Market.serverMode) { l2.addView(badge(Texts.locBadge(r.car.loc)), wrap()); l2.addView(gap(Theme.GAP / 2)) }
            val where = if (r.car.home && r.car.gu.isNotEmpty()) r.car.gu else Texts.minutes(r.car.minutes)
            l2.addView(text(if (r.gone) Texts.NOT_NEAR else where, subT, p.textSub), wrap())
            if (r.car.nick.isEmpty()) { l2.addView(gap()); l2.addView(text(Texts.peerDeals(r.car.deals), subT, p.textSub), wrap()) }   // 옛 서버
            mid.addView(l2, wide())
            if (r.car.nick.isNotEmpty()) { mid.addView(small()); mid.addView(text(Texts.sellerLine(r.car.nick, r.car.deals, r.car.rating), subT, p.textSub), wide()) }
            if (r.car.home) { mid.addView(small()); mid.addView(text(Texts.PHONE_ANSWERS, subT, p.textSub), wide()) }   // 집 물건: 답은 폰으로 (3분)
            info.addView(mid, grow())
            box.addView(info, if (narrow) wide() else grow())

            val stateText = if (r.gone) "" else if (r.busy) Texts.BUSY_OTHER else when (r.state) {
                Market.RowState.NONE -> ""
                Market.RowState.WAITING -> Texts.WAITING
                Market.RowState.ACCEPTED -> Texts.ACCEPTED
                Market.RowState.REJECTED -> Texts.REJECTED
                Market.RowState.NO_ANSWER -> Texts.NO_ANSWER
                Market.RowState.CANCELLED -> Texts.CANCELLED
            }
            val btn: View = if (r.gone || r.busy) View(this) else when (r.state) {
                Market.RowState.NONE -> button(Texts.SEND, true, Theme.BUTTON_MEDIUM) { Market.send(r.no) }
                Market.RowState.ACCEPTED -> button(Texts.pick(r.no), true, Theme.BUTTON_MEDIUM) { Market.pick(r.no) }
                else -> View(this)
            }
            val act = rowOf().apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END }
            act.addView(text(stateText, Theme.BODY_LARGE, main).apply { gravity = if (narrow) Gravity.START else Gravity.END },
                if (narrow) LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) else LinearLayout.LayoutParams(stateW, WRAP_CONTENT))
            act.addView(gap())
            act.addView(btn, LinearLayout.LayoutParams(btnW, Theme.BUTTON_MEDIUM.height))
            box.addView(if (narrow) gap(pad) else gap())
            box.addView(act, if (narrow) LinearLayout.LayoutParams(MATCH_PARENT, Theme.BUTTON_MEDIUM.height) else LinearLayout.LayoutParams(WRAP_CONTENT, Theme.BUTTON_MEDIUM.height))
            list.addView(box, wide())
            list.addView(gap())
        }
        // B-21, B-40: 왼쪽 「모두 보내기」(안 보낸 줄이 2개 이상일 때만), 오른쪽 「닫기」/「요청 거두기」. 자리는 고정.
        val sendAll = if (!nb.browse && nb.rows.count { Market.canSend(it) } >= 2) button(Texts.SEND_ALL) { Market.sendAll() } else null
        val right = if (Market.buyerWaiting) button(Texts.WITHDRAW) { Market.withdrawRequests() } else button(Texts.CLOSE) { Market.closeList() }
        return page(listOf(title(Texts.NEARBY_TITLE)), list, fullRow(sendAll, right))
    }

    // ───────── 거래 요청 (S-20, S-22, S-23, S-28) ─────────

    private fun request(): LinearLayout {
        val r = Market.incoming ?: return column()
        val c = column()
        val info = card()
        val nameRow = rowOf()
        addPhoto(nameRow, r.photo)                                                     // B-30: 물건 이름 왼쪽
        nameRow.addView(nameAndPrice(r.sell.name, r.sell.price), grow())               // S-22: 수락 뒤에도 그대로
        info.addView(nameRow, wide())
        info.addView(gap())
        if (r.nick.isNotEmpty()) info.addView(text(Texts.buyerLine(r.nick, r.rating, r.minutes), Theme.BODY_LARGE, p.textSub), wide())   // 약속 8절
        else {
            val l2 = rowOf()
            l2.addView(text(Texts.minutes(r.minutes), Theme.BODY_SMALL, p.textSub), wrap())
            l2.addView(gap())
            l2.addView(text(Texts.peerDeals(r.deals), Theme.BODY_SMALL, p.textSub), wrap())
            info.addView(l2)
        }
        c.addView(info, wide())
        c.addView(gap())
        if (r.accepted) {
            c.addView(text(Texts.ACCEPTED_WAIT, Theme.BODY_LARGE, p.text), wide())
            c.addView(gap())
            c.addView(fullRow(button(Texts.ACCEPT_CANCEL) { Market.cancelAccept() }), wide())
        } else {
            // S-20: 「거절」 「수락」, 가로를 반씩. 순서는 알림(S-21)과 같아요.
            c.addView(fullRow(button(Texts.REJECT) { Market.rejectRequest() }, button(Texts.ACCEPT, true) { Market.acceptRequest() }), wide())
        }
        return page(listOf(title(Texts.REQUEST_TITLE)), c)
    }

    // ───────── 만날 곳 (M-02, M-06, M-07, M-09, M-15) ─────────

    private fun meet(): LinearLayout {
        val d = Market.deal ?: return column()
        val cancel = button(Texts.DEAL_CANCEL) { Ui.overlay = Overlay.CancelDeal; render() }
        val c = column()
        if (d.stage == Market.Stage.WAIT_DEST) {                                      // M-02
            c.addView(card().apply { addView(nameAndPrice(d.itemName, d.price), wide()) }, wide())
            c.addView(gap())
            c.addView(text(Texts.SAY_NEED_DEST, Theme.BODY_LARGE, p.text), wide())
            c.addView(gap())
            c.addView(text(Texts.destTimeout(Config.DEST_WAIT_MS / 1000), Theme.BODY_LARGE, p.textSub), wide())
            return page(listOf(title(Texts.MEET_TITLE)), c, fullRow(cancel, button(Texts.NEAR_PLACE_GO, true) { Market.decideWithoutDestination() }))
        }
        val place = d.place
        if (d.stage == Market.Stage.WAIT_PEER || place == null) {                    // M-21: 내 목적지는 정했고 만날 곳을 기다려요
            c.addView(card().apply { addView(nameAndPrice(d.itemName, d.price), wide()) }, wide())
            if (d.home) {                                                           // 집 물건: 목적지를 묻지 않아요
                c.addView(gap())
                c.addView(text(Texts.HOME_DECIDING, Theme.BODY_LARGE, p.text), wide())
            } else if (Link.peerDeciding) {
                c.addView(gap())
                c.addView(text(Texts.PEER_DECIDING, Theme.BODY_LARGE, p.text), wide())
            }
            return page(listOf(title(Texts.MEET_TITLE)), c, fullRow(cancel, null))
        }
        // M-06: 작은 "만날 곳" + 장소 이름(가장 큰 글자)이 제목 자리예요.
        val head = listOf(text(Texts.MEET_TITLE, Theme.BODY_SMALL, p.textSub), title(place.name))
        c.addView(text(Market.placeLine(d), Theme.BODY_LARGE, p.textSub), wide())
        c.addView(gap())
        Market.meet?.let { m ->                                                      // 약속 9절: 도착 시간 두 줄과 지도 한 칸
            m.myEta?.let { c.addView(text(Texts.myEta(place.name, it), Theme.TITLE_BOLD, p.text), wide()) }
            val peer = if (m.peerHome) Texts.PEER_FROM_HOME else m.peerEta?.let { Texts.peerEta(it) }
            peer?.let { c.addView(small()); c.addView(text(it, Theme.BODY_LARGE, p.text), wide()) }
            val pts = ArrayList<Triple<String, Double, Double>>()
            pts.add(Triple(Texts.MAP_PLACE, m.lat, m.lon))
            Link.myPos?.let { pts.add(Triple(Texts.MAP_ME, it.first, it.second)) }
            if (!m.peerHome && m.peerLat != null && m.peerLon != null) pts.add(Triple(Texts.MAP_PEER, m.peerLat, m.peerLon))
            // 화면 높이가 모자라면 지도를 줄여요. 아래 버튼 줄은 스크롤 밖이라 가려지지 않아요
            val mapH = minOf(440, resources.configuration.screenHeightDp - 760)    // 임의
            // 카카오 지도(WebView)가 준비되면 그것만, 준비 중이면 OpenStreetMap 위에 숨겨 두고, 실패하면 OpenStreetMap만 그려요
            val my = Link.myPos
            val peerOk = !m.peerHome && m.peerLat != null && m.peerLon != null
            val kakao = if (mapH >= 160) KakaoMap.view(this, listOf(m.lat, m.lon, my?.first, my?.second, if (peerOk) m.peerLat else null, if (peerOk) m.peerLon else null)) else null
            val osm = !Tiles.failed && !(kakao != null && KakaoMap.ready)
            if (mapH >= 160 && (osm || kakao != null)) {
                c.addView(gap())
                val frame = FrameLayout(this).apply { background = fill(p.surface, Theme.ROW_RADIUS); clipToOutline = true }
                if (osm) frame.addView(MeetMap(this, p, pts), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))   // "© OpenStreetMap"은 이 지도에만 있어요
                kakao?.let { frame.addView(it, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) }
                c.addView(frame, LinearLayout.LayoutParams(MATCH_PARENT, mapH))
            }
            c.addView(gap())
        }
        val info = card()
        info.addView(text(if (d.selling) Texts.SIDE_SELL else Texts.SIDE_BUY, Theme.BODY_SMALL, p.textSub))
        info.addView(gap())
        info.addView(nameAndPrice(d.itemName, d.price, Theme.TITLE), wide())
        info.addView(gap())
        if (d.peerCar.isNotEmpty() && !d.peerCar.startsWith("집 물건") && Market.meet?.peerHome != true) {   // 상대가 내 차 정보를 안 넣었거나(D-24) 집 판매자면 이 줄은 빼요
            info.addView(text(Texts.peerCar(d.peerCar), Theme.TITLE_BOLD, p.text))   // 장소 이름 다음으로 큰 글자
            info.addView(gap())
        }
        info.addView(text(Texts.peerDeals(d.peerDeals), Theme.BODY_SMALL, p.textSub))
        c.addView(info, wide())
        c.addView(gap())
        c.addView(text(Texts.PAY_DIRECT, Theme.BODY_LARGE, p.text), wide())
        if (d.naviFailed) {
            c.addView(gap())
            c.addView(text(Texts.NAVI_FAIL, Theme.BODY_LARGE, p.text), wide())
        }
        return page(head, c, fullRow(cancel, button(Texts.MET, true) { Market.finishDeal() }))
    }

    // ───────── 거래 어땠어요? (M-08~M-12) ─────────

    private fun askScreen(): LinearLayout {
        val d = Market.deal ?: return column()
        val c = column()
        c.addView(card().apply { addView(nameAndPrice(d.itemName, d.price), wide()) }, wide())   // M-08: 물건 이름과 가격
        c.addView(gap())
        c.addView(text(Texts.GIVE_STARS, Theme.BODY_LARGE, p.text), wide())                         // 약속 9절: 0~5, 안 골라도 돼요
        c.addView(gap(Theme.GAP / 2))
        c.addView(fullRow(*(0..5).map { n -> button(n.toString(), d.stars == n, Theme.BUTTON_MEDIUM) { Market.setStars(n) } }.toTypedArray()), wide())
        c.addView(gap())
        c.addView(fullRow(button(Texts.DID, true) { Market.answerDeal(true) }, button(Texts.DID_NOT) { Market.answerDeal(false) }, button(Texts.NOT_YET) { Market.notYet() }), wide())
        return page(listOf(title(Texts.ASK_TITLE)), c)
    }

    // ───────── 창: 입력 창과 확인 창 ─────────

    private fun overlay(o: Overlay): View {
        val scrim = FrameLayout(this).apply { setBackgroundColor(p.scrim); isClickable = true; isSoundEffectsEnabled = false }
        val box = column().apply {
            background = fill(p.popup, Theme.POPUP_RADIUS, Theme.POPUP_BORDER, p.popupBorder)
            setPadding(Theme.POPUP_PAD, Theme.POPUP_PAD, Theme.POPUP_PAD, Theme.POPUP_PAD)
        }
        var top = false
        when (o) {
            is Overlay.SellInput -> {
                top = true
                val ed = o.edit
                val name = field(Texts.FIELD_NAME, false).apply { if (ed != null) setText(ed.name) }
                val price = field(Texts.FIELD_PRICE, true).apply { if (ed != null) setText(ed.price.toString()) }
                val desc = field(Texts.FIELD_DESC, false, Config.DESC_MAX).apply { if (ed != null) setText(ed.desc) }
                val error = text("", Theme.BODY_LARGE, p.text)
                clearOnType(error, name, price)                                                    // S-05
                box.addView(title(if (ed != null) Texts.SELL_EDIT_TITLE else Texts.SELL_ADD), wide())
                box.addView(gap())
                // 차 키패드가 창 아래를 가리지 않게 이름과 가격을 한 줄에 두어 창 높이를 줄여요
                box.addView(rowOf().apply {                                                        // S-03: 가격 칸 옆 "원"
                    addView(name, LinearLayout.LayoutParams(0, Theme.FIELD_HEIGHT, 1.4f))
                    addView(gap())
                    addView(price, LinearLayout.LayoutParams(0, Theme.FIELD_HEIGHT, 1f))
                    addView(gap())
                    addView(text(Texts.WON_UNIT, Theme.TITLE, p.text), wrap())
                }, wide())
                box.addView(gap()); box.addView(desc, LinearLayout.LayoutParams(MATCH_PARENT, Theme.FIELD_HEIGHT))
                // 위치 고르기 「차에 있음」「집에 있음」. 고른 쪽이 진한 채움. 집인데 집 위치가 없으면 「지금 있는 곳을 집으로 정하기」
                val locBox = column()
                fun drawLoc() {
                    locBox.removeAllViews()
                    locBox.addView(fullRow(
                        button(Texts.LOC_CAR, o.loc == "car") { o.loc = "car"; drawLoc() },
                        button(Texts.LOC_HOME, o.loc == "home") { o.loc = "home"; drawLoc() },
                    ), wide())
                    if (Market.serverMode && o.loc == "home") {
                        locBox.addView(gap())
                        val gu = Market.homeGu
                        if (gu.isNullOrEmpty()) locBox.addView(fullRow(button(Texts.HOME_HERE) { Market.homeHere() }), wide())
                        else locBox.addView(text(Texts.homeIs(gu), Theme.BODY_LARGE, p.text), wide())
                    }
                }
                drawLoc()
                overlayRefresh = { drawLoc() }
                box.addView(gap()); box.addView(locBox, wide())
                box.addView(gap()); box.addView(error, wide())
                box.addView(gap())
                box.addView(fullRow(
                    button(Texts.CLOSE) { Ui.overlay = null; render() },          // S-06
                    button(if (ed != null) Texts.SAVE else Texts.SELL_SUBMIT, true) {
                        val n = name.text.toString().trim()
                        val s = price.text.toString().trim()
                        val v = s.toLongOrNull() ?: if (s.isNotEmpty()) Long.MAX_VALUE else 0L      // 너무 긴 숫자는 한도를 넘은 것으로 봐요
                        when {
                            n.isEmpty() || v < 1 -> error.text = Texts.SELL_MISSING                 // S-05
                            v > Config.PRICE_MAX -> error.text = Texts.PRICE_TOO_HIGH
                            ed != null -> { Ui.overlay = null; Market.updateSell(ed.id, n, v, desc.text.toString().trim(), o.loc) }
                            Market.sellsFull() -> error.text = Texts.ITEMS_FULL
                            else -> { Ui.overlay = null; Market.addSell(n, v, desc = desc.text.toString().trim(), loc = o.loc) }   // S-04
                        }
                    },
                ), wide())
            }
            is Overlay.WantInput -> {
                top = true
                val name = field(Texts.FIELD_NAME, false)
                val error = text("", Theme.BODY_LARGE, p.text)
                clearOnType(error, name)                                                           // B-05
                box.addView(title(Texts.WANT_ADD), wide())
                box.addView(gap()); box.addView(name, LinearLayout.LayoutParams(MATCH_PARENT, Theme.FIELD_HEIGHT))
                box.addView(gap()); box.addView(error, wide())
                box.addView(gap())
                box.addView(fullRow(
                    button(Texts.CLOSE) { Ui.overlay = null; render() },          // B-06
                    button(Texts.WANT_SUBMIT, true) {
                        val n = name.text.toString().trim()
                        when {
                            n.isEmpty() -> error.text = Texts.WANT_MISSING                         // B-05
                            Market.hasWant(n) -> error.text = Texts.WANT_DUP
                            Market.wantsFull() -> error.text = Texts.ITEMS_FULL
                            else -> { Ui.overlay = null; Market.addWant(n) }                       // B-04
                        }
                    },
                ), wide())
            }
            is Overlay.UrgentInput -> {
                top = true
                val name = field(Texts.FIELD_NAME, false)
                val desc = field(Texts.FIELD_URGENT_DESC, false, Config.DESC_MAX)
                val error = text("", Theme.BODY_LARGE, p.text)
                clearOnType(error, name)
                box.addView(title(Texts.URGENT_POST), wide())
                box.addView(gap()); box.addView(name, LinearLayout.LayoutParams(MATCH_PARENT, Theme.FIELD_HEIGHT))
                box.addView(gap()); box.addView(desc, LinearLayout.LayoutParams(MATCH_PARENT, Theme.FIELD_HEIGHT))
                box.addView(gap()); box.addView(error, wide())
                box.addView(gap())
                box.addView(fullRow(
                    button(Texts.CLOSE) { Ui.overlay = null; render() },
                    button(Texts.URGENT_POST, true) {
                        val n = name.text.toString().trim()
                        if (n.isEmpty()) error.text = Texts.URGENT_MISSING
                        else { Ui.overlay = null; Market.postUrgent(n, desc.text.toString().trim()) }
                    },
                ), wide())
            }
            is Overlay.UrgentSell -> {
                top = true
                val u = Market.urgentIn
                val name = field(Texts.FIELD_NAME, false).apply { setText(u?.name ?: "") }
                val desc = field(Texts.FIELD_DESC, false, Config.DESC_MAX)
                val price = field(Texts.FIELD_PRICE, true)
                val error = text("", Theme.BODY_LARGE, p.text)
                clearOnType(error, name, price)
                var share = false
                val shareBox = FrameLayout(this)
                fun drawShare() {
                    shareBox.removeAllViews()
                    shareBox.addView(button(Texts.SHARE, share) { share = !share; price.isEnabled = !share; if (share) price.setText(""); drawShare() },
                        FrameLayout.LayoutParams(WRAP_CONTENT, Theme.BUTTON_LARGE.height))
                }
                drawShare()
                box.addView(title(Texts.urgentTitle(u?.name ?: "")), wide())
                box.addView(gap()); box.addView(name, LinearLayout.LayoutParams(MATCH_PARENT, Theme.FIELD_HEIGHT))
                box.addView(gap()); box.addView(desc, LinearLayout.LayoutParams(MATCH_PARENT, Theme.FIELD_HEIGHT))
                box.addView(gap())
                box.addView(rowOf().apply {
                    addView(price, LinearLayout.LayoutParams(0, Theme.FIELD_HEIGHT, 1f))
                    addView(gap()); addView(text(Texts.WON_UNIT, Theme.TITLE, p.text), wrap())
                    addView(gap()); addView(shareBox, LinearLayout.LayoutParams(WRAP_CONTENT, Theme.BUTTON_LARGE.height))
                }, wide())
                box.addView(gap()); box.addView(error, wide())
                box.addView(gap())
                box.addView(fullRow(
                    button(Texts.CLOSE) { Ui.overlay = null; render() },
                    button(Texts.URGENT_SELL, true) {
                        val n = name.text.toString().trim()
                        val s = price.text.toString().trim()
                        val v = if (share) 0L else s.toLongOrNull() ?: if (s.isNotEmpty()) Long.MAX_VALUE else -1L
                        when {
                            n.isEmpty() || v < 0 || (v == 0L && !share) -> error.text = Texts.URGENT_SELL_MISSING
                            v > Config.PRICE_MAX -> error.text = Texts.PRICE_TOO_HIGH
                            else -> { Ui.overlay = null; Market.replyUrgent(n, desc.text.toString().trim(), v) }
                        }
                    },
                ), wide())
            }
            // 9-2: 왼쪽이 그만두는 쪽(공식 옅은 버튼), 오른쪽이 안전한 쪽(공식 진한 버튼)
            is Overlay.RemoveSell -> confirm(box, Texts.sellRemoveAsk(o.sell.name), Texts.SELL_REMOVE, Texts.KEEP) { Market.removeSell(o.sell.id) }
            is Overlay.RemoveWant -> confirm(box, Texts.wantRemoveAsk(o.name), Texts.WANT_REMOVE, Texts.KEEP) { Market.removeWant(o.name) }
            is Overlay.CancelDeal -> confirm(box, Texts.CANCEL_ASK, Texts.DEAL_CANCEL, Texts.DEAL_KEEP) { Market.cancelDeal() }
            is Overlay.TurnOff -> confirm(box, Texts.OFF_ASK, Texts.OFF_YES, Texts.KEEP) { Market.turnOff() }
        }
        val w = minOf(Theme.POPUP_WIDTH, resources.configuration.screenWidthDp - 2 * Theme.SCREEN_PAD)
        if (top) {
            // 키패드가 올라와 화면이 줄면(adjustResize) 창이 위로 밀려 아래 「닫기」「올리기」가 보이게 해요
            val sv = ScrollView(this).apply {
                isVerticalScrollBarEnabled = false
                addView(box, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                addOnLayoutChangeListener { v, _, t, _, b, _, ot, _, ob ->
                    if (b - t < ob - ot) v.post { (v as ScrollView).scrollTo(0, maxOf(0, box.height - v.height)) }
                }
            }
            scrim.addView(sv, FrameLayout.LayoutParams(w, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).also { it.topMargin = Theme.SCREEN_PAD; it.bottomMargin = Theme.SCREEN_PAD })
            // 차 키패드는 창 크기를 줄이지 않을 때가 있어 키패드 높이(ime)만큼 아래를 비워요
            scrim.setOnApplyWindowInsetsListener { v, ins ->
                val ime = ins.getInsets(android.view.WindowInsets.Type.ime()).bottom
                val loc = IntArray(2); v.getLocationOnScreen(loc)
                val covered = maxOf(0, loc[1] + v.height - (resources.displayMetrics.heightPixels - ime))
                if (v.paddingBottom != (if (ime > 0) covered else 0)) v.setPadding(0, 0, 0, if (ime > 0) covered else 0)
                ins
            }
        } else scrim.addView(box, FrameLayout.LayoutParams(w, WRAP_CONTENT, Gravity.CENTER))
        return scrim
    }

    /** S-05, B-05: 글자를 다시 치면 오류 글이 사라져요. */
    private fun clearOnType(error: TextView, vararg fields: EditText) {
        val w = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { error.text = "" }
            override fun afterTextChanged(s: android.text.Editable?) {}
        }
        fields.forEach { it.addTextChangedListener(w) }
    }

    private fun confirm(box: LinearLayout, message: String, quit: String, safe: String, action: () -> Unit) {
        box.addView(text(message, Theme.TITLE_BOLD, p.text), wide())
        box.addView(gap())
        box.addView(fullRow(
            button(quit) { Ui.overlay = null; action(); render() },
            button(safe, true) { Ui.overlay = null; render() },
        ), wide())
    }

    private fun field(hint: String, numbersOnly: Boolean, maxLen: Int = Config.NAME_MAX) = EditText(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Theme.TITLE.size.toFloat())
        setTextColor(p.text)
        setHintTextColor(p.textDim)                                                   // 공식 안내 글 text/dark/tertiary
        this.hint = hint
        maxLines = 1
        inputType = if (numbersOnly) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
        if (!numbersOnly) filters = arrayOf(android.text.InputFilter.LengthFilter(maxLen))   // 이름은 60자, 설명은 200자까지
        imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        typeface = face(Theme.TITLE)
        // 공식 입력 칸 Medium: 평소 테두리 2px divider/secondary, 초점 3px field/focused
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), fill(p.field, Theme.FIELD_RADIUS, Theme.FIELD_BORDER_FOCUSED, p.fieldFocused))
            addState(intArrayOf(), fill(p.field, Theme.FIELD_RADIUS, Theme.FIELD_BORDER, p.divider))
        }
        setPadding(Theme.FIELD_PAD_H, 0, Theme.FIELD_PAD_H, 0)
        gravity = Gravity.CENTER_VERTICAL
        isSoundEffectsEnabled = false
    }

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(root.windowToken, 0)
    }

    companion object {
        const val EXTRA_SCREEN = "screen"
        const val EXTRA_URGENT_SELL = "urgent_sell"
    }
}

/**
 * 「거래」 스위치. 공식 ivi_switch Medium: 트랙 80x48(모서리 16, 안 여백 8), 손잡이 32x32(모서리 8).
 * 켜짐은 초록 트랙, 꺼짐은 반투명 트랙. 뜻은 옆의 "켜짐"/"꺼짐" 글자로도 적어요.
 * 스스로는 눌리지 않고, 「거래 알림」 줄 전체가 눌려요. 줄이 눌리면 눌림 덧칠을 그려요.
 */
class TradeSwitch(c: Context, private val p: Theme.Palette, private val on: Boolean) : View(c) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isClickable = false
        isFocusable = false
        isDuplicateParentStateEnabled = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onDraw(cv: Canvas) {
        val w = Theme.SWITCH_WIDTH.toFloat()
        val h = Theme.SWITCH_HEIGHT.toFloat()
        val r = Theme.SWITCH_RADIUS.toFloat()
        paint.color = if (on) p.on else p.trackOff
        cv.drawRoundRect(0f, 0f, w, h, r, r, paint)
        if (isPressed) { paint.color = p.switchPressed; cv.drawRoundRect(0f, 0f, w, h, r, r, paint) }
        val k = Theme.KNOB_SIZE.toFloat()
        val pad = Theme.SWITCH_PAD.toFloat()
        val x = if (on) w - pad - k else pad
        val kr = Theme.KNOB_RADIUS.toFloat()
        paint.color = if (on) p.knobOn else p.knobOff
        cv.drawRoundRect(x, pad, x + k, pad + k, kr, kr, paint)
    }
}
