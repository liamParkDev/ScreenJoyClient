package com.example.screenjoyclient

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 게임 컨트롤은 전부 OverlayService(문라이트 등 다른 앱 위에 뜨는 오버레이 창들)로 옮겨갔고,
 * 이 화면은 권한 요청과 오버레이 시작/중지/편집모드 전환만 담당하는 제어판이다.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var btnOverlayPermission: Button
    private lateinit var btnOverlayStart: Button
    private lateinit var btnOverlayStop: Button
    private lateinit var btnEditMode: Button
    private lateinit var btnKeySettings: Button
    private lateinit var btnProfiles: Button
    private lateinit var txtStatus: TextView
    private lateinit var txtPcIp: TextView

    private val requestLocalNetworkPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) Log.e("ScreenJoy", "Local network permission denied")
        }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) Log.e("ScreenJoy", "Notification permission denied")
        }

    private val requestOverlayPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnOverlayPermission = findViewById(R.id.btnOverlayPermission)
        btnOverlayStart = findViewById(R.id.btnOverlayStart)
        btnOverlayStop = findViewById(R.id.btnOverlayStop)
        btnEditMode = findViewById(R.id.btnEditMode)
        btnKeySettings = findViewById(R.id.btnKeySettings)
        btnProfiles = findViewById(R.id.btnProfiles)
        txtStatus = findViewById(R.id.txtStatus)

        txtPcIp = findViewById(R.id.txtPcIp)
        txtPcIp.setOnClickListener { showPcIpDialog() }
        loadPcIp()

        requestRuntimePermissionsIfNeeded()

        btnOverlayPermission.setOnClickListener {
            requestOverlayPermission.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        btnOverlayStart.setOnClickListener {
            ContextCompat.startForegroundService(
                this,
                Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_START)
            )
            refreshStatus()
        }
        btnOverlayStop.setOnClickListener {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
            refreshStatus()
        }
        btnEditMode.setOnClickListener {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_TOGGLE_EDIT_MODE))
        }
        btnKeySettings.setOnClickListener {
            startActivity(Intent(this, KeySettingsActivity::class.java))
        }
        btnProfiles.setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun loadPcIp() {
        CoroutineScope(Dispatchers.Main).launch {
            val ip = SettingsStore.loadPcIp(applicationContext)
            GamepadState.setPcIpAddress(ip)
            txtPcIp.text = getString(R.string.current_pc_ip_label, ip)
        }
    }

    /** PC 주소는 사용자 네트워크마다 다르므로 소스에 하드코딩하지 않고 여기서 입력받아 저장한다. */
    private fun showPcIpDialog() {
        val input = EditText(this).apply {
            setText(GamepadState.pcIpAddress)
            inputType = InputType.TYPE_CLASS_TEXT
            hint = getString(R.string.pc_ip_hint)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.pc_ip_dialog_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val ip = input.text.toString().trim()
                if (ip.isNotEmpty()) savePcIp(ip)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun savePcIp(ip: String) {
        CoroutineScope(Dispatchers.Main).launch {
            SettingsStore.savePcIp(applicationContext, ip)
            GamepadState.setPcIpAddress(ip)
            txtPcIp.text = getString(R.string.current_pc_ip_label, ip)
        }
    }

    private fun requestRuntimePermissionsIfNeeded() {
        // Android 17(SDK 37)부터 사설 IP 전송에 ACCESS_LOCAL_NETWORK 런타임 권한이 필요
        if (Build.VERSION.SDK_INT >= 37 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_LOCAL_NETWORK)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestLocalNetworkPermission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun refreshStatus() {
        val hasOverlayPermission = Settings.canDrawOverlays(this)
        btnOverlayPermission.isEnabled = !hasOverlayPermission
        btnOverlayStart.isEnabled = hasOverlayPermission && !OverlayService.isRunning
        btnOverlayStop.isEnabled = OverlayService.isRunning
        btnEditMode.isEnabled = OverlayService.isRunning

        txtStatus.text = getString(
            when {
                !hasOverlayPermission -> R.string.status_need_permission
                OverlayService.isRunning -> R.string.status_running
                else -> R.string.status_stopped
            }
        )
    }
}
