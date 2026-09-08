package com.example.screenjoyclient

/**
 * OverlayService(배치/동작)와 KeySettingsActivity(목록 표시)가 같은 컨트롤 키 문자열을
 * 공유하도록 하는 단일 소스. 커스텀 키보드 버튼은 여기 없고 CUSTOM_KEY_PREFIX + id로 식별한다.
 */
object ControlCatalog {
    const val KEY_LEFT_STICK = "leftStick"
    const val KEY_RIGHT_STICK = "rightStick"
    const val KEY_DPAD_UP = "btnDpadUp"
    const val KEY_DPAD_DOWN = "btnDpadDown"
    const val KEY_DPAD_LEFT = "btnDpadLeft"
    const val KEY_DPAD_RIGHT = "btnDpadRight"
    const val KEY_A = "btnA"
    const val KEY_B = "btnB"
    const val KEY_X = "btnX"
    const val KEY_Y = "btnY"
    const val KEY_LB = "btnLB"
    const val KEY_RB = "btnRB"
    const val KEY_LT = "btnLT"
    const val KEY_RT = "btnRT"
    const val KEY_PAD_TOGGLE = "padToggle"

    const val CUSTOM_KEY_PREFIX = "custom_"

    data class FixedControl(val key: String, val displayNameResId: Int)

    val fixedControls: List<FixedControl> = listOf(
        FixedControl(KEY_LEFT_STICK, R.string.list_left_stick),
        FixedControl(KEY_RIGHT_STICK, R.string.list_right_stick),
        FixedControl(KEY_DPAD_UP, R.string.list_dpad_up),
        FixedControl(KEY_DPAD_DOWN, R.string.list_dpad_down),
        FixedControl(KEY_DPAD_LEFT, R.string.list_dpad_left),
        FixedControl(KEY_DPAD_RIGHT, R.string.list_dpad_right),
        FixedControl(KEY_A, R.string.list_button_a),
        FixedControl(KEY_B, R.string.list_button_b),
        FixedControl(KEY_X, R.string.list_button_x),
        FixedControl(KEY_Y, R.string.list_button_y),
        FixedControl(KEY_LB, R.string.list_button_lb),
        FixedControl(KEY_RB, R.string.list_button_rb),
        FixedControl(KEY_LT, R.string.list_button_lt),
        FixedControl(KEY_RT, R.string.list_button_rt),
        FixedControl(KEY_PAD_TOGGLE, R.string.list_pad_toggle)
    )
}
