package com.example.screenjoyclient

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "screenjoy_settings")

/** 패킷을 보낼 PC 주소처럼 사용자 환경마다 다른 값을 저장한다. 소스에 하드코딩하면 안 되는 값들. */
object SettingsStore {
    private val KEY_PC_IP = stringPreferencesKey("pc_ip")

    /** 사설망에서 흔히 쓰는 주소 예시일 뿐, 실제 값은 사용자가 앱에서 입력한다. */
    const val DEFAULT_PC_IP = "192.168.0.2"

    suspend fun loadPcIp(context: Context): String =
        context.settingsDataStore.data.map { it[KEY_PC_IP] }.first() ?: DEFAULT_PC_IP

    suspend fun savePcIp(context: Context, ip: String) {
        context.settingsDataStore.edit { it[KEY_PC_IP] = ip }
    }
}
