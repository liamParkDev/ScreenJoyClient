package com.example.screenjoyclient

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 버튼/스틱 상태와 UDP 송신 로직. OverlayService가 MainActivity 없이(문라이트가 앞에 있을 때도)
 * 계속 패킷을 보낼 수 있도록 앱 생명주기 동안 유지되는 싱글턴으로 둔다.
 */
object GamepadState {

    private const val PORT = 50001

    /** 사용자가 앱에서 입력해 SettingsStore에 저장한 값. 송신 스레드에서 읽으므로 @Volatile. */
    @Volatile
    private var pcIp: String = SettingsStore.DEFAULT_PC_IP

    val pcIpAddress: String get() = pcIp

    fun setPcIpAddress(ip: String) {
        pcIp = ip
    }

    // 버튼 상태 비트마스크 (서버 Program.cs의 비트 순서와 반드시 동일하게 유지)
    object ButtonBit {
        const val A = 0x01
        const val B = 0x02
        const val X = 0x04
        const val Y = 0x08
        const val DPAD_UP = 0x10
        const val DPAD_DOWN = 0x20
        const val DPAD_LEFT = 0x40
        const val DPAD_RIGHT = 0x80
    }

    // 숄더(LB/RB) 비트마스크 — byte0(ButtonBit)과 별개의 byte9에 실림
    object ShoulderBit {
        const val LB = 0x01
        const val RB = 0x02
    }

    private const val MAX_CUSTOM_KEYS = 8

    private var buttonState = 0
    private var shoulderState = 0
    private var leftStickX = 0
    private var leftStickY = 0
    private var rightStickX = 0
    private var rightStickY = 0
    private var ltValue = 0
    private var rtValue = 0
    private val customKeys = mutableSetOf<Int>()

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        appContext = context.applicationContext
        scope.launch { pcIp = SettingsStore.loadPcIp(appContext) }
    }

    fun setButton(bit: Int, pressed: Boolean) {
        buttonState = if (pressed) buttonState or bit else buttonState and bit.inv()
        sendState()
    }

    fun setLeftStick(x: Float, y: Float) {
        leftStickX = (x * 32767).toInt()
        leftStickY = (y * 32767).toInt()
        sendState()
    }

    fun setRightStick(x: Float, y: Float) {
        rightStickX = (x * 32767).toInt()
        rightStickY = (y * 32767).toInt()
        sendState()
    }

    fun setShoulder(bit: Int, pressed: Boolean) {
        shoulderState = if (pressed) shoulderState or bit else shoulderState and bit.inv()
        sendState()
    }

    /** LT/RT는 v1에서 디지털(누르면 최대치)로만 쓰지만, 프로토콜은 처음부터 0~255 바이트로 실어
     * 나중에 진짜 아날로그로 바꿔도 프로토콜을 다시 안 건드려도 되게 한다. */
    fun setTrigger(isLeft: Boolean, pressed: Boolean) {
        val value = if (pressed) 255 else 0
        if (isLeft) ltValue = value else rtValue = value
        sendState()
    }

    /** 최대 MAX_CUSTOM_KEYS개까지만 동시에 실어보낸다 — 초과분은 조용히 무시(문서화된 제약). */
    fun setCustomKey(vkCode: Int, pressed: Boolean) {
        if (pressed) {
            if (customKeys.size < MAX_CUSTOM_KEYS || customKeys.contains(vkCode)) {
                customKeys.add(vkCode)
            }
        } else {
            customKeys.remove(vkCode)
        }
        sendState()
    }

    /** 편집모드 진입 등으로 서버에 입력이 고정되지 않도록 전체 상태를 비운다. */
    fun resetAll() {
        buttonState = 0
        shoulderState = 0
        leftStickX = 0
        leftStickY = 0
        rightStickX = 0
        rightStickY = 0
        ltValue = 0
        rtValue = 0
        customKeys.clear()
        sendState()
    }

    fun sendState() {
        val buffer = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(buttonState.toByte())
        buffer.putShort(leftStickX.toShort())
        buffer.putShort(leftStickY.toShort())
        buffer.putShort(rightStickX.toShort())
        buffer.putShort(rightStickY.toShort())
        buffer.put(shoulderState.toByte())
        buffer.put(ltValue.toByte())
        buffer.put(rtValue.toByte())
        val keyIterator = customKeys.iterator()
        repeat(MAX_CUSTOM_KEYS) {
            buffer.put(if (keyIterator.hasNext()) keyIterator.next().toByte() else 0)
        }
        sendUdpPacket(buffer.array())
    }

    private fun sendUdpPacket(payload: ByteArray) {
        if (!::appContext.isInitialized) return
        scope.launch {
            var socket: DatagramSocket? = null
            try {
                val connMgr = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

                val wifiNetwork = connMgr.allNetworks.firstOrNull { network ->
                    connMgr.getNetworkCapabilities(network)
                        ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                }

                socket = DatagramSocket()
                wifiNetwork?.bindSocket(socket)

                val address = InetAddress.getByName(pcIp)
                val packet = DatagramPacket(payload, payload.size, address, PORT)

                socket.send(packet)
            } catch (e: Exception) {
                Log.e("ScreenJoy", "Send error: ${e.message}", e)
            } finally {
                socket?.close()
            }
        }
    }
}
