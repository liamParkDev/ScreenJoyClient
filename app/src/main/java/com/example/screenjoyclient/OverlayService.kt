package com.example.screenjoyclient

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 컨트롤(스틱/버튼)마다 독립된 TYPE_APPLICATION_OVERLAY 창을 띄우는 서비스.
 * 창이 없는 빈 화면은 터치가 그대로 아래(문라이트 등)로 통과한다.
 */
class OverlayService : Service() {

    private class OverlayControl(
        val key: String,
        val view: View,
        val params: WindowManager.LayoutParams,
        var baseWidthPx: Int,
        var baseHeightPx: Int,
        var baseX: Int,
        var baseY: Int
    )

    private data class BaseGeometry(val x: Int, val y: Int, val size: Int)

    private enum class EditUnit { INDIVIDUAL, GROUP }

    private lateinit var windowManager: WindowManager

    private val controls = mutableMapOf<String, OverlayControl>()
    private val transforms = mutableMapOf<String, ControlTransform>()

    private val controlGroups = mapOf(
        ControlCatalog.KEY_DPAD_UP to "dpad", ControlCatalog.KEY_DPAD_DOWN to "dpad",
        ControlCatalog.KEY_DPAD_LEFT to "dpad", ControlCatalog.KEY_DPAD_RIGHT to "dpad",
        ControlCatalog.KEY_A to "abxy", ControlCatalog.KEY_B to "abxy",
        ControlCatalog.KEY_X to "abxy", ControlCatalog.KEY_Y to "abxy"
    )

    private var isEditMode = false
    private var isPadHidden = false
    private var editUnit = EditUnit.INDIVIDUAL
    private var selectedKeys: Set<String> = emptySet()
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private var gestureCatcherView: View? = null
    private var toolbarView: View? = null
    private lateinit var editScaleDetector: ScaleGestureDetector

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        GamepadState.init(applicationContext)
        editScaleDetector = ScaleGestureDetector(this, ScaleListener())
        startForegroundWithNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> addAllControlWindows()
            ACTION_STOP -> {
                removeAllWindows()
                stopSelf()
            }
            ACTION_TOGGLE_EDIT_MODE -> toggleEditMode()
            ACTION_TOGGLE_EDIT_UNIT -> toggleEditUnit()
            ACTION_TOGGLE_VISIBILITY -> toggleVisibilityForSelected()
            ACTION_RESET_LAYOUT -> resetLayout()
            ACTION_REFRESH_LAYOUT -> reconcileCustomKeys()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        removeAllWindows()
        isRunning = false
        super.onDestroy()
    }

    /** 폴드/언폴드(내부 화면 ↔ 커버 화면 전환), 회전 등으로 화면 크기가 바뀔 때마다 호출됨 */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        onScreenGeometryChanged()
    }

    // ---------------- 알림/포그라운드 ----------------

    private fun startForegroundWithNotification() {
        val channel = NotificationChannel(
            NOTIF_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)

        val stopIntent = Intent(this, OverlayService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .addAction(0, getString(R.string.notification_action_stop), stopPendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    // ---------------- 화면 좌표 계산 ----------------

    private fun screenSize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = windowManager.currentWindowMetrics.bounds
            return bounds.width() to bounds.height()
        }
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }

    // ---------------- 컨트롤 창 생성 ----------------

    /**
     * 화면 크기 기준 기본 위치/크기 계산 (기존 activity_main.xml 마진 그대로 이식).
     * 폴드 기기는 접힘/펼침에 따라 화면 크기가 통째로 바뀌므로, 창을 새로 만들 때뿐 아니라
     * onConfigurationChanged에서도 다시 호출해 기존 창들을 재배치한다.
     */
    private fun computeBaseGeometry(): Map<String, BaseGeometry> {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).roundToInt()

        val (screenW, screenH) = screenSize()
        val stickSize = dp(140)
        val btnSize = dp(64)

        // D-Pad: btnDpadDown이 기준점
        val dpadDownX = dp(64)
        val dpadDownY = screenH - dp(48) - btnSize
        val dpadLeftX = dpadDownX - btnSize
        val dpadRightX = dpadDownX + btnSize + dp(4)
        val dpadUpY = dpadDownY - btnSize

        // ABXY: btnA가 기준점
        val btnAX = screenW - dp(64) - btnSize
        val btnAY = screenH - dp(48) - btnSize
        val btnXX = btnAX - btnSize
        val btnBX = btnAX + btnSize

        val leftStickX = dp(32)
        val leftStickY = dpadUpY - dp(24) - stickSize
        val rightStickX = screenW - dp(32) - stickSize
        val rightStickY = (btnAY - btnSize) - dp(24) - stickSize

        // 숄더(LB/RB)+트리거(LT/RT): 물리 패드처럼 트리거가 위, 범퍼가 아래
        val shoulderSize = dp(56)
        val ltX = dp(32)
        val ltY = dp(16)
        val lbX = dp(32)
        val lbY = ltY + shoulderSize + dp(8)
        val rtX = screenW - dp(32) - shoulderSize
        val rtY = dp(16)
        val rbX = rtX
        val rbY = rtY + shoulderSize + dp(8)

        // 패드 전체 숨김/보이기 토글 — 화면 상단 중앙, 작게
        val toggleSize = dp(40)
        val toggleX = (screenW - toggleSize) / 2
        val toggleY = dp(8)

        return mapOf(
            ControlCatalog.KEY_LEFT_STICK to BaseGeometry(leftStickX, leftStickY, stickSize),
            ControlCatalog.KEY_RIGHT_STICK to BaseGeometry(rightStickX, rightStickY, stickSize),
            ControlCatalog.KEY_DPAD_UP to BaseGeometry(dpadDownX, dpadUpY, btnSize),
            ControlCatalog.KEY_DPAD_DOWN to BaseGeometry(dpadDownX, dpadDownY, btnSize),
            ControlCatalog.KEY_DPAD_LEFT to BaseGeometry(dpadLeftX, dpadDownY, btnSize),
            ControlCatalog.KEY_DPAD_RIGHT to BaseGeometry(dpadRightX, dpadDownY, btnSize),
            ControlCatalog.KEY_A to BaseGeometry(btnAX, btnAY, btnSize),
            ControlCatalog.KEY_Y to BaseGeometry(btnAX, btnAY - btnSize, btnSize),
            ControlCatalog.KEY_X to BaseGeometry(btnXX, btnAY, btnSize),
            ControlCatalog.KEY_B to BaseGeometry(btnBX, btnAY, btnSize),
            ControlCatalog.KEY_LB to BaseGeometry(lbX, lbY, shoulderSize),
            ControlCatalog.KEY_RB to BaseGeometry(rbX, rbY, shoulderSize),
            ControlCatalog.KEY_LT to BaseGeometry(ltX, ltY, shoulderSize),
            ControlCatalog.KEY_RT to BaseGeometry(rtX, rtY, shoulderSize),
            ControlCatalog.KEY_PAD_TOGGLE to BaseGeometry(toggleX, toggleY, toggleSize)
        )
    }

    private fun addAllControlWindows() {
        if (controls.isNotEmpty()) return
        isRunning = true

        val geometry = computeBaseGeometry()

        addJoystickControl(ControlCatalog.KEY_LEFT_STICK, geometry.getValue(ControlCatalog.KEY_LEFT_STICK)) { x, y -> GamepadState.setLeftStick(x, y) }
        addJoystickControl(ControlCatalog.KEY_RIGHT_STICK, geometry.getValue(ControlCatalog.KEY_RIGHT_STICK)) { x, y -> GamepadState.setRightStick(x, y) }

        fun button(key: String, label: String, bit: Int) =
            addButtonControl(key, geometry.getValue(key), label) { pressed -> GamepadState.setButton(bit, pressed) }

        button(ControlCatalog.KEY_DPAD_UP, getString(R.string.dpad_up), GamepadState.ButtonBit.DPAD_UP)
        button(ControlCatalog.KEY_DPAD_DOWN, getString(R.string.dpad_down), GamepadState.ButtonBit.DPAD_DOWN)
        button(ControlCatalog.KEY_DPAD_LEFT, getString(R.string.dpad_left), GamepadState.ButtonBit.DPAD_LEFT)
        button(ControlCatalog.KEY_DPAD_RIGHT, getString(R.string.dpad_right), GamepadState.ButtonBit.DPAD_RIGHT)

        button(ControlCatalog.KEY_A, getString(R.string.button_a), GamepadState.ButtonBit.A)
        button(ControlCatalog.KEY_Y, getString(R.string.button_y), GamepadState.ButtonBit.Y)
        button(ControlCatalog.KEY_X, getString(R.string.button_x), GamepadState.ButtonBit.X)
        button(ControlCatalog.KEY_B, getString(R.string.button_b), GamepadState.ButtonBit.B)

        addButtonControl(ControlCatalog.KEY_LB, geometry.getValue(ControlCatalog.KEY_LB), getString(R.string.button_lb)) { pressed ->
            GamepadState.setShoulder(GamepadState.ShoulderBit.LB, pressed)
        }
        addButtonControl(ControlCatalog.KEY_RB, geometry.getValue(ControlCatalog.KEY_RB), getString(R.string.button_rb)) { pressed ->
            GamepadState.setShoulder(GamepadState.ShoulderBit.RB, pressed)
        }
        addButtonControl(ControlCatalog.KEY_LT, geometry.getValue(ControlCatalog.KEY_LT), getString(R.string.button_lt)) { pressed ->
            GamepadState.setTrigger(isLeft = true, pressed = pressed)
        }
        addButtonControl(ControlCatalog.KEY_RT, geometry.getValue(ControlCatalog.KEY_RT), getString(R.string.button_rt)) { pressed ->
            GamepadState.setTrigger(isLeft = false, pressed = pressed)
        }
        addButtonControl(ControlCatalog.KEY_PAD_TOGGLE, geometry.getValue(ControlCatalog.KEY_PAD_TOGGLE), getString(R.string.pad_toggle_glyph)) { pressed ->
            if (pressed) togglePadHidden()
        }

        loadSavedLayout()
    }

    /** 폴드/언폴드 등으로 화면 크기가 바뀌면 기존 창들의 기준 위치/크기를 다시 계산해 재배치한다.
     * 커스텀 키보드 버튼은 computeBaseGeometry()의 고정 컨트롤 목록에 없으므로 별도로 처리 — 안 그러면
     * 화면 크기가 바뀐 뒤에도 예전 화면 기준 좌표에 그대로 남아 배치가 어긋난다. */
    private fun onScreenGeometryChanged() {
        if (controls.isEmpty()) return
        val geometry = computeBaseGeometry()
        var customIndex = 0
        controls.forEach { (key, control) ->
            val g = if (key.startsWith(ControlCatalog.CUSTOM_KEY_PREFIX)) {
                customKeyGeometry(customIndex++)
            } else {
                geometry[key] ?: return@forEach
            }
            control.baseX = g.x
            control.baseY = g.y
            control.baseWidthPx = g.size
            control.baseHeightPx = g.size
            applyTransform(control, transforms.getValue(key))
        }
        gestureCatcherView?.let { view ->
            val (screenW, screenH) = screenSize()
            val lp = view.layoutParams as WindowManager.LayoutParams
            lp.width = screenW
            lp.height = screenH
            runCatching { windowManager.updateViewLayout(view, lp) }
        }
    }

    private fun baseParams(g: BaseGeometry) = WindowManager.LayoutParams(
        g.size, g.size,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = g.x
        y = g.y
    }

    private fun addJoystickControl(key: String, g: BaseGeometry, onMoved: (Float, Float) -> Unit) {
        val view = JoystickView(this)
        view.onStickMoved = onMoved
        val params = baseParams(g)
        windowManager.addView(view, params)
        controls[key] = OverlayControl(key, view, params, g.size, g.size, g.x, g.y)
        transforms[key] = ControlTransform()
    }

    private fun addButtonControl(key: String, g: BaseGeometry, label: String, onPress: (Boolean) -> Unit) {
        val view = GamepadButtonView(this)
        view.label = label
        view.onPressChanged = onPress
        val params = baseParams(g)
        windowManager.addView(view, params)
        controls[key] = OverlayControl(key, view, params, g.size, g.size, g.x, g.y)
        transforms[key] = ControlTransform()
    }

    /** 커스텀 키보드 버튼 기본 위치. 화면 중앙에서 index만큼 겹치지 않게 대각선으로 오프셋 —
     * 화면 크기가 바뀔 때(폴드/언폴드) 재계산할 수 있도록 addCustomKeyControl과 onScreenGeometryChanged가 공유. */
    private fun customKeyGeometry(index: Int): BaseGeometry {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).roundToInt()
        val (screenW, screenH) = screenSize()
        val size = dp(56)
        val offset = dp(20) * index
        return BaseGeometry((screenW - size) / 2 + offset, (screenH - size) / 2 + offset, size)
    }

    private fun addCustomKeyControl(def: CustomKeyDef, index: Int) {
        addButtonControl(ControlCatalog.CUSTOM_KEY_PREFIX + def.id, customKeyGeometry(index), def.label) { pressed ->
            GamepadState.setCustomKey(def.vkCode, pressed)
        }
    }

    private fun removeAllWindows() {
        removeEditWindows()
        controls.values.forEach { runCatching { windowManager.removeView(it.view) } }
        controls.clear()
        transforms.clear()
    }

    // ---------------- 위치/배율 적용 ----------------

    /** 편집으로 옮긴 컨트롤(offsetX/offsetY가 있는 것)은 "가장 가까운 가장자리로부터 고정 거리"로
     * 위치를 잡는다 — computeBaseGeometry()의 기본 위치와 같은 규칙이라, 옮긴 컨트롤도 기본 컨트롤과
     * 똑같이 폴드/언폴드를 따라간다. 아직 안 옮긴 컨트롤은 기준 위치를 그대로 쓴다. */
    private fun controlCenter(control: OverlayControl, t: ControlTransform): PointF {
        val (screenW, screenH) = screenSize()
        val defaultCx = control.baseX + control.baseWidthPx / 2f
        val defaultCy = control.baseY + control.baseHeightPx / 2f
        return PointF(
            when {
                t.offsetX == null -> defaultCx
                t.anchorRight -> screenW - t.offsetX
                else -> t.offsetX
            },
            when {
                t.offsetY == null -> defaultCy
                t.anchorBottom -> screenH - t.offsetY
                else -> t.offsetY
            }
        )
    }

    /** 중심 절대 좌표를 가장 가까운 가장자리 기준 거리로 바꿔 저장 형태로 만든다. */
    private fun ControlTransform.withCenter(centerX: Float, centerY: Float): ControlTransform {
        val (screenW, screenH) = screenSize()
        val anchorRight = centerX > screenW / 2f
        val anchorBottom = centerY > screenH / 2f
        return copy(
            offsetX = if (anchorRight) screenW - centerX else centerX,
            offsetY = if (anchorBottom) screenH - centerY else centerY,
            anchorRight = anchorRight,
            anchorBottom = anchorBottom,
            legacyCx = null, legacyCy = null, legacyDx = null, legacyDy = null
        )
    }

    private fun applyTransform(control: OverlayControl, t: ControlTransform) {
        val newWidth = (control.baseWidthPx * t.scale).roundToInt()
        val newHeight = (control.baseHeightPx * t.scale).roundToInt()
        val center = controlCenter(control, t)
        control.params.width = newWidth
        control.params.height = newHeight
        control.params.x = (center.x - newWidth / 2f).roundToInt()
        control.params.y = (center.y - newHeight / 2f).roundToInt()
        runCatching { windowManager.updateViewLayout(control.view, control.params) }
    }

    private fun currentRect(control: OverlayControl): android.graphics.RectF {
        val p = control.params
        return android.graphics.RectF(
            p.x.toFloat(), p.y.toFloat(),
            (p.x + p.width).toFloat(), (p.y + p.height).toFloat()
        )
    }

    // ---------------- 편집 모드 ----------------

    private fun toggleEditMode() {
        if (controls.isEmpty()) return
        isEditMode = !isEditMode
        if (isEditMode) {
            GamepadState.resetAll()
            applyVisibilityForAll() // 편집 중엔 숨긴 컨트롤도 찾아서 다시 켤 수 있도록 전부 노출
            addEditWindows()
        } else {
            selectControls(emptySet())
            removeEditWindows()
            applyVisibilityForAll()
        }
    }

    // ---------------- 표시/숨김 ----------------

    private fun applyVisibility(control: OverlayControl, visible: Boolean) {
        control.view.alpha = if (visible) 1f else 0f
        control.params.flags = if (visible)
            control.params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else
            control.params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { windowManager.updateViewLayout(control.view, control.params) }
    }

    private fun applyVisibilityForAll() {
        controls.forEach { (key, control) ->
            val t = transforms[key] ?: ControlTransform()
            if (isEditMode) {
                // 편집 중엔 숨김 컨트롤도 항상 터치 가능하게 두되(선택/이동 가능해야 함),
                // 뭘 숨김 처리했는지 알 수 있도록 완전히 안 지우고 반투명으로만 표시한다.
                control.view.alpha = if (t.visible) 1f else EDIT_HIDDEN_ALPHA
                control.params.flags = control.params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                runCatching { windowManager.updateViewLayout(control.view, control.params) }
            } else {
                val effective = when {
                    key == ControlCatalog.KEY_PAD_TOGGLE -> t.visible // 숨긴 뒤에도 다시 누를 수 있어야 함
                    else -> t.visible && !isPadHidden
                }
                applyVisibility(control, effective)
            }
        }
    }

    /** 편집모드에서 선택된 컨트롤(들)의 숨김 여부를 반전시킨다. 안 쓰는 키를 편집모드에서
     * 바로 치워두기 위함 — 실제 숨김(터치 불가)은 편집모드를 빠져나갈 때 적용된다. */
    private fun toggleVisibilityForSelected() {
        if (selectedKeys.isEmpty()) return
        selectedKeys.forEach { key ->
            val t = transforms.getValue(key)
            transforms[key] = t.copy(visible = !t.visible)
        }
        applyVisibilityForAll()
        persistLayout()
    }

    /** 타이핑 등으로 패드 전체를 잠깐 치워두는 토글. 개별 컨트롤의 visible 설정과는 별개다. */
    private fun togglePadHidden() {
        isPadHidden = !isPadHidden
        if (isPadHidden) GamepadState.resetAll() // 누르던 중 숨기면 서버에 입력이 고정되는 것 방지
        applyVisibilityForAll()
    }

    /** KeySettingsActivity에서 키 추가/삭제/표시토글 후 실행 중인 서비스에 즉시 반영시킨다. */
    private fun reconcileCustomKeys() {
        if (controls.isEmpty()) return
        CoroutineScope(Dispatchers.Main).launch {
            val defs = LayoutStore.loadCustomKeys(applicationContext)
            val newIds = defs.map { it.id }.toSet()
            val existingIds = controls.keys
                .filter { it.startsWith(ControlCatalog.CUSTOM_KEY_PREFIX) }
                .map { it.removePrefix(ControlCatalog.CUSTOM_KEY_PREFIX) }
                .toSet()

            (existingIds - newIds).forEach { id ->
                val key = ControlCatalog.CUSTOM_KEY_PREFIX + id
                controls.remove(key)?.let { runCatching { windowManager.removeView(it.view) } }
                transforms.remove(key)
            }
            defs.filter { it.id !in existingIds }.forEachIndexed { index, def -> addCustomKeyControl(def, index) }
            defs.filter { it.id in existingIds }.forEach { def ->
                (controls[ControlCatalog.CUSTOM_KEY_PREFIX + def.id]?.view as? GamepadButtonView)?.label = def.label
            }

            val saved = LayoutStore.load(applicationContext)
            var migrated = false
            saved.forEach { (key, rawT) ->
                controls[key]?.let { control ->
                    val t = migrateLegacyTransform(control, rawT)
                    if (t !== rawT) migrated = true
                    transforms[key] = t
                    applyTransform(control, t)
                }
            }
            applyVisibilityForAll()
            if (migrated) persistLayout()
        }
    }

    private fun toggleEditUnit() {
        editUnit = if (editUnit == EditUnit.INDIVIDUAL) EditUnit.GROUP else EditUnit.INDIVIDUAL
        (toolbarView?.findViewWithTag<Button>(TAG_UNIT_BUTTON))?.text =
            getString(if (editUnit == EditUnit.GROUP) R.string.edit_unit_group else R.string.edit_unit_individual)
        selectedKeys.firstOrNull()?.let { selectControls(resolveSelection(it)) }
    }

    private fun resolveSelection(key: String): Set<String> {
        if (editUnit == EditUnit.INDIVIDUAL) return setOf(key)
        val group = controlGroups[key] ?: return setOf(key)
        return controlGroups.filterValues { it == group }.keys
    }

    private fun selectControls(keys: Set<String>) {
        (selectedKeys - keys).forEach { controls[it]?.view?.foreground = null }
        keys.forEach { controls[it]?.view?.foreground = ColorDrawable(SELECTION_COLOR) }
        selectedKeys = keys
    }

    /** 선택된 컨트롤이 있으면 그것만, 없으면 전체를 기본 위치로 되돌린다 — 컨트롤 하나만 잘못 옮겼을 때
     * 전체 배치를 날리지 않고 그것만 되돌릴 수 있어야 하므로. */
    private fun resetLayout() {
        val targets = if (selectedKeys.isNotEmpty()) selectedKeys.toSet() else controls.keys.toSet()
        selectControls(emptySet())
        targets.forEach { key ->
            val control = controls[key] ?: return@forEach
            transforms[key] = ControlTransform()
            applyTransform(control, transforms.getValue(key))
        }
        applyVisibilityForAll()
        persistLayout()
    }

    private fun loadSavedLayout() {
        CoroutineScope(Dispatchers.Main).launch {
            LayoutStore.loadCustomKeys(applicationContext).forEachIndexed { index, def ->
                addCustomKeyControl(def, index)
            }
            val saved = LayoutStore.load(applicationContext)
            var migrated = false
            saved.forEach { (key, rawT) ->
                val control = controls[key] ?: return@forEach
                val t = migrateLegacyTransform(control, rawT)
                if (t !== rawT) migrated = true
                transforms[key] = t
                applyTransform(control, t)
            }
            applyVisibilityForAll()
            if (migrated) persistLayout()
        }
    }

    /** 예전 포맷(화면 비율 절대좌표 cx/cy, 그 이전의 기준 위치 대비 오프셋 dx/dy)으로 저장된 값을
     * 지금 화면 기준의 가장자리 거리로 변환한다 — 포맷이 바뀌었다고 사용자가 옮겨둔 위치가 사라지면
     * 안 되므로, 지금 보이는 자리를 그대로 새 포맷으로 옮겨 적는다(시각적 위치 변화 없음).
     *
     * 이동량이 0이면 "옮긴 적 없음"이므로 절대 변환하지 않는다 — 여기서 변환해버리면 한 번도 안 건드린
     * 컨트롤(D-pad 등)이 편집된 컨트롤로 굳어져 기본 위치 공식을 안 따르게 된다(실제로 그 버그를 냈었다). */
    private fun migrateLegacyTransform(control: OverlayControl, t: ControlTransform): ControlTransform {
        if (t.offsetX != null || t.offsetY != null) return t
        val (screenW, screenH) = screenSize()
        val baseCx = control.baseX + control.baseWidthPx / 2f
        val baseCy = control.baseY + control.baseHeightPx / 2f

        val centerX: Float
        val centerY: Float
        when {
            t.legacyCx != null || t.legacyCy != null -> {
                centerX = t.legacyCx?.let { it * screenW } ?: baseCx
                centerY = t.legacyCy?.let { it * screenH } ?: baseCy
            }
            (t.legacyDx != null && t.legacyDx != 0f) || (t.legacyDy != null && t.legacyDy != 0f) -> {
                centerX = baseCx + (t.legacyDx ?: 0f) * screenW
                centerY = baseCy + (t.legacyDy ?: 0f) * screenH
            }
            else -> return t.copy(legacyCx = null, legacyCy = null, legacyDx = null, legacyDy = null)
        }
        return t.withCenter(centerX, centerY)
    }

    private fun persistLayout() {
        CoroutineScope(Dispatchers.IO).launch {
            LayoutStore.save(applicationContext, transforms.toMap())
        }
    }

    /** 편집모드 전체화면 제스처 캐처 + 완료/초기화/개별·세트편집 툴바 창 추가 */
    private fun addEditWindows() {
        val (screenW, screenH) = screenSize()
        val catcher = View(this)
        val catcherParams = WindowManager.LayoutParams(
            screenW, screenH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 0 }
        catcher.setOnTouchListener(EditGestureListener())
        windowManager.addView(catcher, catcherParams)
        gestureCatcherView = catcher

        val toolbar = buildToolbar()
        val toolbarLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (16 * resources.displayMetrics.density).roundToInt()
            y = (16 * resources.displayMetrics.density).roundToInt()
        }
        // 툴바가 항상 제스처 캐처보다 위에 오도록 마지막에 addView (같은 TYPE의 창은 추가 순서가 z-order)
        windowManager.addView(toolbar, toolbarLp)
        toolbarView = toolbar
    }

    private fun removeEditWindows() {
        gestureCatcherView?.let { runCatching { windowManager.removeView(it) } }
        toolbarView?.let { runCatching { windowManager.removeView(it) } }
        gestureCatcherView = null
        toolbarView = null
    }

    private fun buildToolbar(): View {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).roundToInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.argb(200, 14, 20, 22))
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        fun addButton(text: String, tag: String? = null, onClick: () -> Unit): Button {
            val button = Button(this).apply {
                this.text = text
                this.tag = tag
                setOnClickListener { onClick() }
            }
            container.addView(button)
            return button
        }

        addButton(getString(R.string.edit_mode_off)) {
            sendActionToSelf(ACTION_TOGGLE_EDIT_MODE)
        }
        addButton(getString(R.string.reset_layout)) {
            sendActionToSelf(ACTION_RESET_LAYOUT)
        }
        addButton(getString(R.string.edit_unit_individual), TAG_UNIT_BUTTON) {
            sendActionToSelf(ACTION_TOGGLE_EDIT_UNIT)
        }
        addButton(getString(R.string.edit_hide_toggle)) {
            sendActionToSelf(ACTION_TOGGLE_VISIBILITY)
        }

        return container
    }

    private fun sendActionToSelf(action: String) {
        onStartCommand(Intent(this, OverlayService::class.java).setAction(action), 0, 0)
    }

    private inner class EditGestureListener : View.OnTouchListener {
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            editScaleDetector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val hitKey = controls.values
                        .firstOrNull { currentRect(it).contains(event.rawX, event.rawY) }
                        ?.key
                    selectControls(if (hitKey != null) resolveSelection(hitKey) else emptySet())
                    lastTouchX = event.rawX
                    lastTouchY = event.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 1 && selectedKeys.isNotEmpty()) {
                        val dx = event.rawX - lastTouchX
                        val dy = event.rawY - lastTouchY
                        selectedKeys.forEach { key ->
                            val control = controls[key] ?: return@forEach
                            val t = transforms.getValue(key)
                            val center = controlCenter(control, t)
                            transforms[key] = t.withCenter(center.x + dx, center.y + dy)
                            applyTransform(control, transforms.getValue(key))
                        }
                    }
                    lastTouchX = event.rawX
                    lastTouchY = event.rawY
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (selectedKeys.isNotEmpty()) persistLayout()
                }
            }
            return true
        }
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (selectedKeys.isEmpty()) return true
            val factor = detector.scaleFactor

            val centers = selectedKeys.associateWith { key -> controlCenter(controls.getValue(key), transforms.getValue(key)) }
            val anchorX = centers.values.sumOf { it.x.toDouble() }.toFloat() / centers.size
            val anchorY = centers.values.sumOf { it.y.toDouble() }.toFloat() / centers.size

            selectedKeys.forEach { key ->
                val control = controls.getValue(key)
                val t = transforms.getValue(key)
                val center = centers.getValue(key)
                val newCx = anchorX + (center.x - anchorX) * factor
                val newCy = anchorY + (center.y - anchorY) * factor
                val newScale = (t.scale * factor).coerceIn(0.5f, 2.5f)
                transforms[key] = t.withCenter(newCx, newCy).copy(scale = newScale)
                applyTransform(control, transforms.getValue(key))
            }
            return true
        }
    }

    companion object {
        const val ACTION_START = "com.example.screenjoyclient.action.START"
        const val ACTION_STOP = "com.example.screenjoyclient.action.STOP"
        const val ACTION_TOGGLE_EDIT_MODE = "com.example.screenjoyclient.action.TOGGLE_EDIT_MODE"
        const val ACTION_TOGGLE_EDIT_UNIT = "com.example.screenjoyclient.action.TOGGLE_EDIT_UNIT"
        const val ACTION_TOGGLE_VISIBILITY = "com.example.screenjoyclient.action.TOGGLE_VISIBILITY"
        const val ACTION_RESET_LAYOUT = "com.example.screenjoyclient.action.RESET_LAYOUT"
        const val ACTION_REFRESH_LAYOUT = "com.example.screenjoyclient.action.REFRESH_LAYOUT"

        var isRunning = false
            private set

        private const val NOTIF_CHANNEL_ID = "screenjoy_overlay"
        private const val NOTIF_ID = 1
        private const val TAG_UNIT_BUTTON = "unit_button"
        private const val SELECTION_COLOR = 0x668BC34A // 반투명 연두색, 편집 중 선택 표시용
        private const val EDIT_HIDDEN_ALPHA = 0.35f // 편집모드에서 숨김 처리된 컨트롤 표시 투명도
    }
}
