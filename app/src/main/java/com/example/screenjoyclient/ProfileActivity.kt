package com.example.screenjoyclient

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 지금 레이아웃/커스텀키 구성을 이름 붙여 저장해두고, 게임마다 다른 구성을 오가며
 * 불러오는 화면. 프로필을 불러오면 "현재" 슬롯(OverlayService가 실제로 쓰는 값)에 덮어쓴다. */
class ProfileActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)
        container = findViewById(R.id.containerProfileRows)
        findViewById<Button>(R.id.btnSaveCurrentProfile).setOnClickListener { showSaveDialog() }
        loadAndRender()
    }

    private fun loadAndRender() {
        CoroutineScope(Dispatchers.Main).launch {
            val names = LayoutStore.listProfileNames(applicationContext)
            container.removeAllViews()

            if (names.isEmpty()) {
                container.addView(TextView(this@ProfileActivity).apply {
                    text = getString(R.string.profile_empty)
                    setTextColor(getColor(R.color.white))
                    setPadding(0, 24, 0, 24)
                })
                return@launch
            }

            names.forEach { name -> addRow(name) }
        }
    }

    private fun addRow(name: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 24, 0, 24)
        }
        val labelView = TextView(this).apply {
            text = name
            setTextColor(getColor(R.color.white))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val loadButton = Button(this).apply {
            text = getString(R.string.profile_load)
            setOnClickListener { loadProfile(name) }
        }
        val deleteButton = Button(this).apply {
            text = getString(R.string.key_settings_delete)
            setOnClickListener { deleteProfile(name) }
        }
        row.addView(labelView)
        row.addView(loadButton)
        row.addView(deleteButton)
        container.addView(row)
    }

    private fun showSaveDialog() {
        val nameInput = EditText(this).apply {
            hint = getString(R.string.profile_name_hint)
        }
        val dialogLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 0)
            addView(nameInput)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.profile_save_current)
            .setView(dialogLayout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isNotEmpty()) saveCurrentAsProfile(name)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun saveCurrentAsProfile(name: String) {
        CoroutineScope(Dispatchers.Main).launch {
            LayoutStore.saveCurrentAsProfile(applicationContext, name)
            loadAndRender()
        }
    }

    private fun loadProfile(name: String) {
        CoroutineScope(Dispatchers.Main).launch {
            LayoutStore.loadProfileIntoCurrent(applicationContext, name)
            notifyOverlayIfRunning()
        }
    }

    private fun deleteProfile(name: String) {
        CoroutineScope(Dispatchers.Main).launch {
            LayoutStore.deleteProfile(applicationContext, name)
            loadAndRender()
        }
    }

    private fun notifyOverlayIfRunning() {
        if (OverlayService.isRunning) {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_REFRESH_LAYOUT))
        }
    }
}
