package ai.roadmarket.app

import android.content.Context
import android.content.res.Configuration

/**
 * 색, 글자 크기, 여백, 버튼 크기 토큰. 화면은 160dpi라 1dp = 1px이에요.
 * 주석의 "공식(104/102)"은 research/104·102 문서(Pleos Connect 공식 Figma 부품, connect-s 값)에서 그대로 가져온 값,
 * "임의"는 문서에 없어 우리가 정한 값이에요. 두 문서가 다르면 102의 변수 값을 따랐어요.
 */
object Theme {
    class Palette(
        val background: Int,      // 화면 바탕 — 공식(104/102) background/primary
        val surface: Int,         // 카드·줄 바탕 — 공식(104/102) dim/dark/primary (공식 목록 상자 바탕. 카드 부품은 문서에 없음)
        val popup: Int,           // 확인 창·입력 창 바탕 — 공식(104/102) background/popup
        val popupBorder: Int,     // 창 테두리 2px — 공식(104/102) divider/primary
        val divider: Int,         // 구분선 — 공식(104/102) divider/secondary
        val text: Int,            // 주 글자 — 공식(104/102) text/dark/primary
        val textSub: Int,         // 보조 글자 — 공식(104/102) text/dark/secondary
        val textDim: Int,         // 흐린 글자(끝난 줄, 입력 칸 안내 글) — 공식(104/102) text/dark/tertiary
        val textDisabled: Int,    // 비활성 버튼 글자 — 공식(104/102) text/dark/quaternary
        // 공식 Primary = 옅은 버튼 (그 밖의 버튼)
        val light: Int,           // 공식(104/102) button/basic/enabled
        val lightPressed: Int,    // 공식(104/102) button/basic/pressed
        val lightDisabled: Int,   // 공식(104/102) button/basic/disabled
        val lightText: Int,       // 공식(104/102) text/dark/primary
        // 공식 Secondary = 진한 버튼 (가장 중요한 버튼)
        val dark: Int,            // 공식(104/102) button/filled/enabled
        val darkPressed: Int,     // 공식(104/102) button/filled/pressed
        val darkDisabled: Int,    // 공식(104/102) button/filled/disabled
        val darkText: Int,        // 공식(104/102) text/light/primary
        val disabledBorder: Int,  // 비활성 버튼 2px 안쪽 테두리 — 공식(104/102) divider/secondary
        val accent: Int,          // 파랑: 왼쪽 메뉴 표시 막대와 급구 카드의 빛(Glow.kt)에만 — 공식(104/102) informative/positive
        // 스위치 (ivi_switch)
        val on: Int,              // 공식(104/102) switch/on
        val trackOff: Int,        // 공식(104/102) switch/off
        val knobOn: Int,          // 공식(104/102) switch/knob_on
        val knobOff: Int,         // 공식(104/102) switch/knob_off
        val switchPressed: Int,   // 눌림 때 트랙 위에 한 겹 — 공식(102) dim/dark/secondary
        // 입력 칸 (ivi_text_field)
        val field: Int,           // 공식(104/102) field/background
        val fieldFocused: Int,    // 초점 테두리 — 공식(102) field/focused (밤 값은 102 변수 원본)
        val scrim: Int,           // 창 뒤 어둡게 — 공식(104/102) overlay/default (낮 20%, 밤 50%)
    )

    val DAY = Palette(
        background = 0xFFF7F8FA.toInt(), surface = 0x0D131417, popup = 0xFFFFFFFF.toInt(), popupBorder = 0x0D131417,
        divider = 0x1A131417,
        text = 0xFF131417.toInt(), textSub = 0xD6131417.toInt(), textDim = 0xA3131417.toInt(), textDisabled = 0x52131417,
        light = 0x0D131417, lightPressed = 0x1A131417, lightDisabled = 0x33FFFFFF, lightText = 0xFF131417.toInt(),
        dark = 0xFF44464E.toInt(), darkPressed = 0xFF313236.toInt(), darkDisabled = 0x0D131417, darkText = 0xFFFFFFFF.toInt(),
        disabledBorder = 0x1A131417,
        accent = 0xFF0064FF.toInt(),
        on = 0xFF02C265.toInt(), trackOff = 0x4D131417, knobOn = 0xFFFFFFFF.toInt(), knobOff = 0xFFFFFFFF.toInt(), switchPressed = 0x1A131417,
        field = 0xFFFFFFFF.toInt(), fieldFocused = 0xFF313236.toInt(),
        scrim = 0x33131417,
    )
    val NIGHT = Palette(
        background = 0xFF1D1E21.toInt(), surface = 0x0DFFFFFF, popup = 0xFF313236.toInt(), popupBorder = 0x0DFFFFFF,
        divider = 0x1AFFFFFF,
        text = 0xFFFFFFFF.toInt(), textSub = 0xD6FFFFFF.toInt(), textDim = 0xA3FFFFFF.toInt(), textDisabled = 0x52FFFFFF,
        light = 0x0DFFFFFF, lightPressed = 0x1AFFFFFF, lightDisabled = 0x33131417, lightText = 0xFFFFFFFF.toInt(),
        dark = 0xFFE0E1E6.toInt(), darkPressed = 0xFFC7C9CE.toInt(), darkDisabled = 0x0DFFFFFF, darkText = 0xFF131417.toInt(),
        disabledBorder = 0x1AFFFFFF,
        accent = 0xFF0082FF.toInt(),
        on = 0xFF00E074.toInt(), trackOff = 0x1AFFFFFF, knobOn = 0xFFFFFFFF.toInt(), knobOff = 0xFFA4A8AE.toInt(), switchPressed = 0x1AFFFFFF,
        field = 0xFF131417.toInt(), fieldFocused = 0xFFEDEEF2.toInt(),
        scrim = 0x80131417.toInt(),
    )

    fun palette(c: Context): Palette =
        if ((c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES) NIGHT else DAY

    // 글자 크기 / 줄 높이 / 굵기 (공식(104/102) 글자 스타일 표). 공식 글꼴 Asta Sans는 넣지 않아 시스템 글꼴을 써요.
    // 이 기기의 한국어 글꼴(NotoSansCJK)은 Regular 한 벌뿐이라 700·800은 시스템이 굵게 만든 같은 모양이에요.
    class Type(val size: Int, val line: Int, val weight: Int) {
        val bold get() = weight >= 600
    }

    val HEADLINE_SMALL = Type(48, 64, 800)      // 화면 제목 — 공식(104/102) Headline/Small/Extra_Strong
    val TITLE_BOLD = Type(36, 44, 700)          // 핵심 정보 — 공식(104/102) Title/Medium/Strong
    val TITLE = Type(36, 44, 400)               // 공식(104/102) Title/Medium/Normal (입력 칸 글자 Medium과 같음)
    val BODY_LARGE = Type(30, 38, 400)          // 설명 문장 — 공식(104/102) Body/Large/Normal
    val BODY_SMALL = Type(26, 34, 400)          // 보조 정보 — 공식(104/102) Body/Small/Normal

    /** 기본 버튼(ivi_basic_button) 크기 한 벌. */
    class ButtonSize(val height: Int, val padH: Int, val padV: Int, val minWidth: Int, val radius: Int, val type: Type)

    // 공식(104/102) Large: 높이 100, 좌우 40, 위아래 16, 최소 폭 140, 모서리 32, 글자 Title/Small/Strong 32/40 Bold
    val BUTTON_LARGE = ButtonSize(100, 40, 16, 140, 32, Type(32, 40, 700))
    // 공식(104/102) Medium: 높이 80, 좌우 32, 위아래 16, 최소 폭 120, 모서리 24, 글자 Body/Large/Strong 30/38 Bold
    val BUTTON_MEDIUM = ButtonSize(80, 32, 16, 120, 24, Type(30, 38, 700))
    // 공식(102) Small: 높이 64, 좌우 24, 위아래 8, 최소 폭 100, 모서리 16, 글자 Body/Small/Strong 26/34 Bold
    val BUTTON_SMALL = ButtonSize(64, 24, 8, 100, 16, Type(26, 34, 700))
    const val BUTTON_DISABLED_BORDER = 2       // 공식(104/102) 비활성 버튼 안쪽 테두리 2px

    // 확인 창·입력 창 (ivi_system_popup Medium)
    const val POPUP_WIDTH = 808                // 공식(104/102)
    const val POPUP_RADIUS = 48                // 공식(104/102)
    const val POPUP_PAD = 48                   // 공식(104/102) 안 여백
    const val POPUP_BORDER = 2                 // 공식(104/102)

    // 입력 칸 (ivi_text_field Medium)
    const val FIELD_HEIGHT = 100               // 공식(104/102)
    const val FIELD_PAD_H = 32                 // 공식(104/102)
    const val FIELD_RADIUS = 24                // 공식(104/102)
    const val FIELD_BORDER = 2                 // 공식(102) 평소 테두리 2px
    const val FIELD_BORDER_FOCUSED = 3         // 공식(104/102) 초점 테두리 3px

    // 스위치 (ivi_switch Medium)
    const val SWITCH_WIDTH = 80                // 공식(104/102) 트랙 80
    const val SWITCH_HEIGHT = 48               // 공식(104/102) 트랙 48
    const val SWITCH_RADIUS = 16               // 공식(104/102) 트랙 모서리
    const val SWITCH_PAD = 8                   // 공식(104/102) 트랙 안 여백
    const val KNOB_SIZE = 32                   // 공식(104/102) 손잡이 32x32
    const val KNOB_RADIUS = 8                  // 공식(104/102) 손잡이 모서리

    // 카드·줄 모서리 (카드 부품은 문서에 없어 가장 가까운 공식 컨테이너 값)
    const val CARD_RADIUS = 32                 // 공식(104/102) 목록 상자 모서리 32
    const val ROW_RADIUS = 24                  // 공식(104/102) 목록 줄 모서리 24

    // 크기와 여백
    const val TOUCH_MIN = 96                   // 임의(요건 9절): 「거래 알림」 줄처럼 누르는 칸의 최소 높이
    const val GAP = 24                         // 임의(요건 9절)
    const val SCREEN_PAD = 40                  // 임의(요건 9절). 공식 머리글 왼쪽 여백 40과 같아요
    const val ROW_MIN_HEIGHT = 112             // 공식(104/102) 목록 줄 높이와 같아요
    const val MENU_WIDTH = 300                 // 임의 (왼쪽 메뉴 부품은 문서에 없음)
    const val NARROW_BELOW = 1000              // 임의: 창 가로가 이보다 좁으면 메뉴를 위 한 줄로

    // 스낵바 (ivi_toast_popup은 102·104에 이름과 Margin 속성만 있고 값이 없어요). 색은 공식 Secondary 버튼 채움(dark/darkText)을 써요.
    val SNACK = BUTTON_MEDIUM                  // 임의: 모서리 24·좌우 32·위아래 16·글자 Body/Large 30/38은 공식 Medium 버튼 값을 빌렸어요
    const val SNACK_BOTTOM = 40                // 임의: 화면 아래에서 띄우는 거리 (SCREEN_PAD와 같아요)
    const val SNACK_MS = 2500L                 // 임의: 저절로 사라지기까지 (2~3초 사이)

    // 아래는 문서에도 지시에도 없어 정한 값
    const val CARD_PAD = 32                    // 임의
    const val MENU_BAR_WIDTH = 8               // 임의: 선택된 메뉴 표시 막대
    const val PHOTO_SIZE = 96                  // 임의: 8절 "줄 높이에 맞춘 작은 정사각형" (B-30)
    const val BADGE_PAD_H = 16                 // 임의: 배지(옅은 버튼 채움 + 보조 글자 26) 좌우 여백
    const val BADGE_PAD_V = 4                  // 임의: 배지 위아래 여백
    const val ROW_BIG_MIN_HEIGHT = 180         // 임의: 달리는 중 주변 물건 세 줄을 크게 보일 때 줄 높이
    const val NARROW_ROW_PAD = 16              // 임의: 9-2 좁은 창에서 목록 세 줄이 한 화면에 들어오게 줄인 줄 안 여백

    // 급구 카드의 흐르는 빛 (Glow.kt). 공식 부품에 빛 효과가 없어 모두 임의예요. 색은 accent(공식 informative/positive)를 써요.
    const val GLOW_PERIOD_MS = 3200L           // 임의: 빛이 카드 테두리를 한 바퀴 도는 시간 (2.5~4초 사이, 번쩍임 없게 느리게)
    const val GLOW_RING = 3                    // 임의: 빛나는 테두리 굵기. 공식 입력 칸 초점 테두리 3과 같은 굵기를 빌렸어요
    const val GLOW_RING_BASE = 0.22f           // 임의: 빛 띠가 없는 곳의 테두리 진하기 (accent에 곱함)
    const val GLOW_RING_PEAK = 0.85f           // 임의: 빛 띠 가운데의 테두리 진하기
    const val GLOW_SHEEN_PEAK = 0.07f          // 임의: 카드 안쪽 바탕에 번지는 빛의 가장 진한 값
    const val GLOW_BAND = 0.07f                // 임의: 빛 띠의 폭 (한 바퀴를 1로 볼 때의 표준편차)
}
