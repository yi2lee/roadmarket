package ai.roadmarket.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.SweepGradient
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.widget.LinearLayout

/**
 * 급구 카드: 카드 테두리를 따라 빛 띠가 천천히 한 바퀴 돌고, 안쪽 바탕에 같은 빛이 옅게 번져요.
 * - API 33 이상이고 하드웨어 가속 화면이면 Skia RuntimeShader(AGSL)로 그려요.
 * - 그 밖에는 SweepGradient를 돌려서 같은 모양을 흉내 내요 (minSdk 28까지).
 * - 시계는 SystemClock 기준이라 화면을 다시 만들어도(render) 빛이 처음으로 튀지 않아요.
 * - 화면이 안 보이거나(창이 가려짐·떼어짐) 기기의 애니메이션이 꺼져 있으면 멈춘 채 한 번만 그려요.
 * 값은 Theme.GLOW_* (모두 임의 값)와 palette.accent를 써요.
 */
class GlowCard(c: Context, private val color: Int, private val surface: Int) : LinearLayout(c) {
    private val radius = Theme.CARD_RADIUS.toFloat()
    private val ring = Theme.GLOW_RING.toFloat()
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ring }
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rect = RectF()
    private val inner = RectF()
    private val m = Matrix()
    private var shown = false
    // 안쪽 바탕용과 테두리용 두 벌 (값이 달라 한 벌을 나눠 쓰지 않아요)
    // 셰이더 글이 이 기기에서 안 읽히면(예외) 아래 SweepGradient로 안전하게 그려요
    private val agslSheen: RuntimeShader? = makeShader()
    private val agslRing: RuntimeShader? = makeShader()
    private fun makeShader(): RuntimeShader? = if (Build.VERSION.SDK_INT < 33) null else try { RuntimeShader(SRC) } catch (e: RuntimeException) { null }
    private var sweep: SweepGradient? = null
    private var sweepW = 0f
    private var sweepH = 0f

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        background = GradientDrawable().apply { setColor(surface); cornerRadius = radius }
        setPadding(Theme.CARD_PAD, Theme.CARD_PAD, Theme.CARD_PAD, Theme.CARD_PAD)
        minimumHeight = Theme.ROW_MIN_HEIGHT
    }

    private val running get() = shown && isAttachedToWindow && ValueAnimator.areAnimatorsEnabled()

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        shown = isVisible
        if (running) postInvalidateOnAnimation()
    }

    /** 0..1: 지금 빛 띠가 한 바퀴 중 어디쯤인지. 애니메이션이 멈췄으면 고정 위치. */
    private fun phase(): Float =
        if (!running) 0.125f else (SystemClock.uptimeMillis() % Theme.GLOW_PERIOD_MS).toFloat() / Theme.GLOW_PERIOD_MS

    override fun onDraw(cv: Canvas) {
        super.onDraw(cv)
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val ph = phase()
        rect.set(ring / 2, ring / 2, w - ring / 2, h - ring / 2)
        inner.set(0f, 0f, w, h)
        val sheen = agslSheen
        val ringS = agslRing
        if (sheen != null && ringS != null && cv.isHardwareAccelerated && Build.VERSION.SDK_INT >= 33) {
            // 안쪽 바탕: 띠 근처만 옅게
            setUniforms(sheen, w, h, ph, 0f, Theme.GLOW_SHEEN_PEAK)
            sheenPaint.shader = sheen
            cv.drawRoundRect(inner, radius, radius, sheenPaint)
            // 테두리: 늘 옅게 보이고 띠 자리는 진하게
            setUniforms(ringS, w, h, ph, Theme.GLOW_RING_BASE, Theme.GLOW_RING_PEAK)
            ringPaint.shader = ringS
            cv.drawRoundRect(rect, radius, radius, ringPaint)
        } else {
            val g = sweepFor(w, h)
            m.setRotate(ph * 360f, w / 2, h / 2)
            g.setLocalMatrix(m)
            ringPaint.shader = g; ringPaint.alpha = 255
            cv.drawRoundRect(rect, radius, radius, ringPaint)
            sheenPaint.shader = g; sheenPaint.alpha = (255 * Theme.GLOW_SHEEN_PEAK / Theme.GLOW_RING_PEAK).toInt()
            cv.drawRoundRect(inner, radius, radius, sheenPaint)
        }
        if (running) postInvalidateOnAnimation()
    }

    @android.annotation.TargetApi(33)
    private fun setUniforms(s: RuntimeShader, w: Float, h: Float, ph: Float, base: Float, peak: Float) {
        s.setFloatUniform("size", w, h)
        s.setFloatUniform("phase", ph)
        s.setFloatUniform("base", base)
        s.setFloatUniform("peak", peak)
        s.setFloatUniform("band", Theme.GLOW_BAND)
        s.setColorUniform("glow", color)
    }

    /** API 32 이하: 진한 띠 하나가 있는 원형 그라디언트. 크기가 같으면 다시 쓰고 돌리기만 해요. */
    private fun sweepFor(w: Float, h: Float): SweepGradient {
        sweep?.let { if (sweepW == w && sweepH == h) return it }
        fun a(k: Float) = Color.argb((Color.alpha(color) * k).toInt(), Color.red(color), Color.green(color), Color.blue(color))
        val b = Theme.GLOW_BAND
        val g = SweepGradient(w / 2, h / 2,
            intArrayOf(a(Theme.GLOW_RING_BASE), a(Theme.GLOW_RING_BASE), a(Theme.GLOW_RING_PEAK), a(Theme.GLOW_RING_BASE), a(Theme.GLOW_RING_BASE)),
            floatArrayOf(0f, 0.5f - 2 * b, 0.5f, 0.5f + 2 * b, 1f))
        sweep = g; sweepW = w; sweepH = h
        return g
    }

    private companion object {
        /**
         * 카드 가운데에서 본 각도(가로세로 비율을 맞춰 0..1)로 빛 띠 위치를 정해요.
         * k = base + (peak - base) * 가우스(띠까지 거리). 돌려주는 색은 미리 곱한 알파예요.
         */
        const val SRC = """
            uniform float2 size;
            uniform float phase;
            uniform float base;
            uniform float peak;
            uniform float band;
            layout(color) uniform half4 glow;
            half4 main(float2 p) {
                float2 q = (p - size * 0.5) / size;
                float a = atan(q.y, q.x) / 6.2831853 + 0.5;
                float d = abs(fract(a - phase + 0.5) - 0.5);
                float g = exp(-(d * d) / (2.0 * band * band));
                half k = half((base + (peak - base) * g) * float(glow.a));
                return half4(glow.rgb * k, k);
            }
        """
    }
}
