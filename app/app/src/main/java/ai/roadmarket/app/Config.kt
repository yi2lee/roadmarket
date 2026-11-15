package ai.roadmarket.app

/**
 * 시간 값을 한곳에 모아 둬요. (요건표 8절)
 * 20초(답 없음)와 60초(고르지 않음), 가짜 차 대본은 서버에 있어요 (7-0, D-01). 앱은 세지 않아요.
 */
object Config {
    // 요건표 8절의 시간
    const val DEST_WAIT_MS = 30_000L          // M-02 (내 차만의 기다림이라 앱이 세요)
    const val NEARBY_MAX = 3                  // B-20
    const val DOOR_CYCLES_FOR_RETURN = 2      // M-08
    const val VOICE_WANT_NOTICE_DELAY_MS = 5_000L   // B-20: 말로 찾는 물건을 넣은 직후 맞으면 이만큼 늦게 알려요
    const val POLL_MS = 1_000L                // 7-0: 앱이 서버에 묻는 간격

    // 서버가 받는 한도. 넘으면 서버가 그 차의 요청을 통째로 거절해요
    const val NAME_MAX = 60                   // 물건 이름 글자 수
    const val DESC_MAX = 200                  // 물건 설명 글자 수 (약속 1절)
    const val ITEMS_MAX = 50                  // 파는 물건, 찾는 물건 각각
    const val PRICE_MAX = 1_000_000_000L      // 10억 원

    // 요건표에 없는 값
    const val UNREACHABLE_MS = 3_000L         // 7-0: 이만큼 서버에 닿지 않으면 "서버에 닿지 않아요."
    const val NAVI_FIRST_STATE_MS = 3_000L    // 앱이 켜진 뒤 내비가 길 상태를 알려 주기를 기다리는 한도
    const val NAVI_ANSWER_MS = 20_000L        // M-07 판정: 이 시간 안에 내비가 답하지 않으면 못 넣은 것으로 봐요
    const val NAVI_DEST_WAIT_MS = 3_000L      // 목적지 좌표를 내비에 물은 뒤 기다리는 한도
    const val NAVI_LOCATION_EVERY_MS = 5_000L // 내비에 지금 위치를 묻는 간격

    class Place(val name: String, val lat: Double, val lon: Double)

    // D-11 데모 목적지
    val DEMO_DEST = Place("데모 목적지", 37.4854, 127.0364)
}
