package com.example.screenjoyclient

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

/** 어떤 키를 쓸지(표시여부)와 커스텀 키보드 버튼(i/q 등) 추가·삭제를 관리하는 화면. */
class KeySettingsActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_key_settings)
        container = findViewById(R.id.containerRows)
        findViewById<Button>(R.id.btnAddCustomKey).setOnClickListener { showAddKeyDialog() }
        loadAndRender()
    }

    private fun loadAndRender() {
        CoroutineScope(Dispatchers.Main).launch {
            val transforms = LayoutStore.load(applicationContext)
            val customKeys = LayoutStore.loadCustomKeys(applicationContext)
            container.removeAllViews()

            ControlCatalog.fixedControls.forEach { fc ->
                addRow(
                    label = getString(fc.displayNameResId),
                    visible = transforms[fc.key]?.visible ?: true,
                    onVisibleChanged = { checked -> setVisible(fc.key, checked) },
                    onDelete = null
                )
            }
            customKeys.forEach { def ->
                addRow(
                    label = def.label,
                    visible = transforms[ControlCatalog.CUSTOM_KEY_PREFIX + def.id]?.visible ?: true,
                    onVisibleChanged = { checked -> setVisible(ControlCatalog.CUSTOM_KEY_PREFIX + def.id, checked) },
                    onDelete = { deleteCustomKey(def.id) }
                )
            }
        }
    }

    private fun addRow(label: String, visible: Boolean, onVisibleChanged: (Boolean) -> Unit, onDelete: (() -> Unit)?) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 24, 0, 24)
        }
        val labelView = TextView(this).apply {
            text = label
            setTextColor(getColor(R.color.white))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val switch = Switch(this).apply {
            isChecked = visible
            setOnCheckedChangeListener { _, checked -> onVisibleChanged(checked) }
        }
        row.addView(labelView)
        row.addView(switch)
        if (onDelete != null) {
            val delete = Button(this).apply {
                text = getString(R.string.key_settings_delete)
                setOnClickListener { onDelete() }
            }
            row.addView(delete)
        }
        container.addView(row)
    }

    private fun setVisible(key: String, visible: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            val transforms = LayoutStore.load(applicationContext).toMutableMap()
            val current = transforms[key] ?: ControlTransform()
            transforms[key] = current.copy(visible = visible)
            LayoutStore.save(applicationContext, transforms)
            notifyOverlayIfRunning()
        }
    }

    private fun deleteCustomKey(id: String) {
        CoroutineScope(Dispatchers.Main).launch {
            val defs = LayoutStore.loadCustomKeys(applicationContext).filterNot { it.id == id }
            LayoutStore.saveCustomKeys(applicationContext, defs)
            val transforms = LayoutStore.load(applicationContext).toMutableMap()
            transforms.remove(ControlCatalog.CUSTOM_KEY_PREFIX + id)
            LayoutStore.save(applicationContext, transforms)
            notifyOverlayIfRunning()
            loadAndRender()
        }
    }

    private fun showAddKeyDialog() {
        val labels = KEY_OPTIONS.map { it.first }.toTypedArray()
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@KeySettingsActivity, android.R.layout.simple_spinner_dropdown_item, labels)
        }
        val labelInput = EditText(this).apply {
            hint = getString(R.string.key_settings_label_hint)
        }
        val dialogLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 0)
            addView(spinner)
            addView(labelInput)
        }
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (labelInput.text.isBlank()) labelInput.setText(KEY_OPTIONS[position].first)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.key_settings_pick_key)
            .setView(dialogLayout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val vkCode = KEY_OPTIONS[spinner.selectedItemPosition].second
                val label = labelInput.text.toString().ifBlank { KEY_OPTIONS[spinner.selectedItemPosition].first }
                addCustomKey(vkCode, label)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun addCustomKey(vkCode: Int, label: String) {
        CoroutineScope(Dispatchers.Main).launch {
            val defs = LayoutStore.loadCustomKeys(applicationContext).toMutableList()
            defs.add(CustomKeyDef(id = UUID.randomUUID().toString(), vkCode = vkCode, label = label))
            LayoutStore.saveCustomKeys(applicationContext, defs)
            notifyOverlayIfRunning()
            loadAndRender()
        }
    }

    private fun notifyOverlayIfRunning() {
        if (OverlayService.isRunning) {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_REFRESH_LAYOUT))
        }
    }

    private companion object {
        val KEY_OPTIONS: List<Pair<String, Int>> = buildList {
            for (c in 'A'..'Z') add(c.toString() to c.code)
            for (c in '0'..'9') add(c.toString() to c.code)
            add("Space" to 0x20)
            add("Tab" to 0x09)
            add("Enter" to 0x0D)
            add("Esc" to 0x1B)
            add("Shift" to 0x10)
            add("Ctrl" to 0x11)
            add("Alt" to 0x12)
            for (i in 1..12) add("F$i" to 0x70 + (i - 1))
        }
    }
}
