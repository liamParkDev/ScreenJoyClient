package com.example.screenjoyclient

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

/** 컨트롤 하나의 편집 상태: 배율, 표시 여부, 그리고 (편집으로 옮긴 적 있다면) 위치.
 *
 * 위치는 "가장 가까운 화면 가장자리로부터 중심까지의 거리(px)"로 저장한다 — computeBaseGeometry()의
 * 기본 위치도 정확히 같은 방식(가장자리에서 고정 거리)이라, 편집한 컨트롤도 기본 컨트롤과 똑같이
 * 폴드/언폴드를 따라간다. 화면 비율(0~1)로 저장하면 가장자리 기준인 기본 위치들과 서로 다른 비율로
 * 움직여서 배치가 어긋난다.
 *
 * offsetX/offsetY가 null이면 아직 옮긴 적 없다는 뜻으로 computeBaseGeometry()의 기준 위치를 그대로 쓴다. */
data class ControlTransform(
    val offsetX: Float? = null,
    val offsetY: Float? = null,
    val anchorRight: Boolean = false,
    val anchorBottom: Boolean = false,
    val scale: Float = 1f,
    val visible: Boolean = true,
    // 예전 포맷 마이그레이션용(px 오프셋 → 화면 비율 오프셋 → 화면 비율 절대좌표 순으로 바뀌어 왔다).
    // OverlayService가 로드 시 한 번 현재 화면 기준으로 가장자리 거리로 변환해 되저장하고, 그 뒤엔
    // 저장되지 않아 자연히 사라진다(transformsToJson이 안 씀).
    val legacyCx: Float? = null,
    val legacyCy: Float? = null,
    val legacyDx: Float? = null,
    val legacyDy: Float? = null
)

/** 사용자가 추가한 커스텀 키보드 키 버튼 하나의 정의(어떤 키/라벨). 위치·배율·표시여부는
 * ControlTransform 맵을 이 id로 그대로 재사용한다. */
data class CustomKeyDef(
    val id: String,
    val vkCode: Int,
    val label: String
)

private val Context.layoutDataStore by preferencesDataStore(name = "screenjoy_layout")

/** 컨트롤 레이아웃(위치/크기/표시여부)과 커스텀 키 목록을 DataStore에 JSON으로 저장/복원 */
object LayoutStore {
    private val KEY_LAYOUT = stringPreferencesKey("control_layout_json")
    private val KEY_CUSTOM_KEYS = stringPreferencesKey("custom_keys_json")
    private val KEY_PROFILES = stringPreferencesKey("profiles_json")

    suspend fun save(context: Context, transforms: Map<String, ControlTransform>) {
        context.layoutDataStore.edit { it[KEY_LAYOUT] = transformsToJson(transforms).toString() }
    }

    suspend fun load(context: Context): Map<String, ControlTransform> {
        val raw = context.layoutDataStore.data.map { it[KEY_LAYOUT] }.first() ?: return emptyMap()
        return jsonToTransforms(JSONObject(raw))
    }

    suspend fun clear(context: Context) {
        context.layoutDataStore.edit { it.remove(KEY_LAYOUT) }
    }

    suspend fun saveCustomKeys(context: Context, defs: List<CustomKeyDef>) {
        context.layoutDataStore.edit { it[KEY_CUSTOM_KEYS] = customKeysToJson(defs).toString() }
    }

    suspend fun loadCustomKeys(context: Context): List<CustomKeyDef> {
        val raw = context.layoutDataStore.data.map { it[KEY_CUSTOM_KEYS] }.first() ?: return emptyList()
        return jsonToCustomKeys(org.json.JSONArray(raw))
    }

    // ---------------- 프로필(세팅별 저장) ----------------

    /** 현재 "활성" 레이아웃/커스텀키와 별개로, 이름 붙여 통째로 스냅샷 저장해두는 목록.
     * 게임마다 버튼 배치·숨김·커스텀키 구성이 달라 여러 벌을 오가며 쓰기 위함. */
    suspend fun listProfileNames(context: Context): List<String> =
        loadProfilesJson(context).keys().asSequence().sorted().toList()

    suspend fun saveCurrentAsProfile(context: Context, name: String) {
        val profiles = loadProfilesJson(context)
        profiles.put(name, JSONObject().apply {
            put("transforms", transformsToJson(load(context)))
            put("customKeys", customKeysToJson(loadCustomKeys(context)))
        })
        context.layoutDataStore.edit { it[KEY_PROFILES] = profiles.toString() }
    }

    suspend fun loadProfileIntoCurrent(context: Context, name: String): Boolean {
        val entry = loadProfilesJson(context).optJSONObject(name) ?: return false
        save(context, jsonToTransforms(entry.getJSONObject("transforms")))
        saveCustomKeys(context, jsonToCustomKeys(entry.getJSONArray("customKeys")))
        return true
    }

    suspend fun deleteProfile(context: Context, name: String) {
        val profiles = loadProfilesJson(context)
        profiles.remove(name)
        context.layoutDataStore.edit { it[KEY_PROFILES] = profiles.toString() }
    }

    private suspend fun loadProfilesJson(context: Context): JSONObject {
        val raw = context.layoutDataStore.data.map { it[KEY_PROFILES] }.first() ?: return JSONObject()
        return JSONObject(raw)
    }

    // ---------------- JSON 변환 ----------------

    private fun transformsToJson(transforms: Map<String, ControlTransform>): JSONObject {
        val json = JSONObject()
        transforms.forEach { (key, t) ->
            json.put(key, JSONObject().apply {
                if (t.offsetX != null) put("offsetX", t.offsetX)
                if (t.offsetY != null) put("offsetY", t.offsetY)
                put("anchorRight", t.anchorRight)
                put("anchorBottom", t.anchorBottom)
                put("scale", t.scale)
                put("visible", t.visible)
            })
        }
        return json
    }

    private fun jsonToTransforms(obj: JSONObject): Map<String, ControlTransform> =
        obj.keys().asSequence().associateWith { key ->
            val t = obj.getJSONObject(key)
            val hasCurrent = t.has("offsetX") || t.has("offsetY")
            ControlTransform(
                offsetX = if (t.has("offsetX")) t.getDouble("offsetX").toFloat() else null,
                offsetY = if (t.has("offsetY")) t.getDouble("offsetY").toFloat() else null,
                anchorRight = t.optBoolean("anchorRight", false),
                anchorBottom = t.optBoolean("anchorBottom", false),
                scale = t.getDouble("scale").toFloat(),
                // 기존에 저장된 레이아웃엔 "visible" 키가 없을 수 있음 — 없으면 true(기존 동작과 동일)
                visible = t.optBoolean("visible", true),
                legacyCx = if (!hasCurrent && t.has("cx")) t.getDouble("cx").toFloat() else null,
                legacyCy = if (!hasCurrent && t.has("cy")) t.getDouble("cy").toFloat() else null,
                legacyDx = if (!hasCurrent && !t.has("cx") && t.has("dx")) t.getDouble("dx").toFloat() else null,
                legacyDy = if (!hasCurrent && !t.has("cy") && t.has("dy")) t.getDouble("dy").toFloat() else null
            )
        }

    private fun customKeysToJson(defs: List<CustomKeyDef>): org.json.JSONArray {
        val array = org.json.JSONArray()
        defs.forEach { def ->
            array.put(JSONObject().apply {
                put("id", def.id)
                put("vkCode", def.vkCode)
                put("label", def.label)
            })
        }
        return array
    }

    private fun jsonToCustomKeys(array: org.json.JSONArray): List<CustomKeyDef> =
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            CustomKeyDef(id = o.getString("id"), vkCode = o.getInt("vkCode"), label = o.getString("label"))
        }
}