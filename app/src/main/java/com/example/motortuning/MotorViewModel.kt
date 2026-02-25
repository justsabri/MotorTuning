package com.example.motortuning

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

class MotorViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val gson = Gson()

    /** ================= 参数定义（来自 JSON） ================= */
    private val _paramDef = MutableStateFlow<List<MotorParam>>(emptyList())
    val paramDef: StateFlow<List<MotorParam>> = _paramDef

    private val _params = MutableStateFlow<Map<String, Int>>(emptyMap())
    val params: StateFlow<Map<String, Int>> = _params

    /** ================= 参数当前值（来自蓝牙） ================= */
    private val _realtimeValues = MutableStateFlow<Map<String, Int?>>(emptyMap())
    val realtimeValues: StateFlow<Map<String, Int?>> = _realtimeValues

    /** ================= UI 状态 ================= */

    private val _uiState = MutableStateFlow(MotorUiState())
    val uiState: StateFlow<MotorUiState> = _uiState

    init {
        loadAndMergeParams()

        initPosition()
    }

    /** ==================== 主加载函数 ==================== **/
    private fun loadAndMergeParams() = runBlocking {
        val config = loadParamConfig()
        val merged = mergeParams(config)
        _paramDef.value = config.params
        _params.value = merged
    }

    /** ==================== 1️⃣ 加载 JSON 文件 ==================== **/
    private fun loadParamConfig(): MotorParamConfig {
        val file = File(context.filesDir, "motor_params.json")

        if (!file.exists()) {
            // 复制 assets 到内部存储
            context.assets.open("motor_params.json").use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }

        val json = file.readText()
        // 如果 JSON 是数组形式，则包装成对象
        return if (json.trim().startsWith("[")) {
            val type = object : TypeToken<List<MotorParam>>() {}.type
            val list: List<MotorParam> = gson.fromJson(json, type)
            MotorParamConfig(version = 1, params = list)
        } else {
            gson.fromJson(json, MotorParamConfig::class.java)
        }
    }

    /** ==================== 2️⃣ 合并 DataStore 保存参数 ==================== **/
    private suspend fun mergeParams(config: MotorParamConfig): Map<String, Int> {
        val prefs = context.motorParamDataStore.data.first()
        val savedVersion = prefs[MotorParamKeys.CONFIG_VERSION] ?: -1
        val result = mutableMapOf<String, Int>()

        // 先用已保存的值，没有用 JSON 默认值
        config.params.forEach { def ->
            val key = intPreferencesKey(def.key)
            result[def.key] = prefs[key] ?: def.default
        }

        // 如果版本不一样，升级
        if (savedVersion != config.version) {
            upgradeParams(prefs, result, config)
        }

        return result
    }

    /** ==================== 3️⃣ 升级 DataStore 参数 ==================== **/
    private suspend fun upgradeParams(
        prefs: Preferences,
        values: Map<String, Int>,
        config: MotorParamConfig
    ) {
        context.motorParamDataStore.edit { edit ->
            // 更新版本号
            edit[MotorParamKeys.CONFIG_VERSION] = config.version

            // 删除已不存在的参数
            prefs.asMap().keys
                .filterIsInstance<Preferences.Key<Int>>()
                .filter { key ->
                    key.name != MotorParamKeys.CONFIG_VERSION.name &&
                            config.params.none { it.key == key.name }
                }
                .forEach { edit.remove(it) }

            // 写入新参数
            values.forEach { (k, v) ->
                edit[intPreferencesKey(k)] = v
            }
        }
    }

    /** ================= 参数保存（Dialog 调用） ================= */

    fun saveParams(newParams: Map<String, Int>) {
        viewModelScope.launch {

            // 1️⃣ 持久化（DataStore）
            context.motorParamDataStore.edit { edit ->
                newParams.forEach { (k, v) ->
                    edit[intPreferencesKey(k)] = v
                }
            }

            // 2️⃣ 更新内存态
            _params.value = newParams

            // 3️⃣ 下发给电机（可失败、可重试）
            sendParams(newParams)

            // 4️⃣ 备份到 JSON（IO）
            withContext(Dispatchers.IO) {
                saveParamsToJson(context, newParams)
            }
        }
    }

    private fun sendParams(params: Map<String, Int>) {
        params.forEach { (key, value) ->
            updateRealtimeValue(key, value)
        }
    }

    fun saveParamsToJson(context: Context, newParams: Map<String, Int>) {
        try {
            val file = File(context.filesDir, "motor_params.json")

            // 构建可序列化的列表
            val paramList = _paramDef.value.map { param ->
                MotorParam(
                    key = param.key,
                    name = param.name,
                    default = newParams[param.key] ?: param.default,
                    unit = param.unit
                )
            }

            val json = Gson().toJson(paramList)
            file.writeText(json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** ================= 蓝牙实时更新显示数据 ================= */
    fun updateRealtimeValue(key: String, value: Int?) {
        _realtimeValues.value = _realtimeValues.value.toMutableMap().apply {
            put(key, value)
        }

        if (key == "position") {
            updateRealtimePosition(value)
        }
    }

    /** 初始化 position/previewAngle **/
    private fun initPosition() {
        val reals = _realtimeValues.value
        val defaultPos = reals["position"] ?: 0
        _uiState.value = _uiState.value.copy(
            position = defaultPos,
            previewAngle = defaultPos
        )
    }

    /** 蓝牙收到实时值 **/
    fun updateRealtimePosition(pos: Int?) {
        val newPos = pos ?: _uiState.value.position
        _uiState.value = _uiState.value.copy(
            position = newPos,
            previewAngle = newPos
        )
    }

    /** ================= 显示参数（给 UI 用） ================= */

    fun buildDisplayParams(): List<DisplayParam> {
        val reals = _realtimeValues.value
        val defParams = _paramDef.value
        return defParams.map { param ->
            DisplayParam(
                name = param.name,
                value = reals[param.key]?.toString() ?: "null"
            )
        }
    }

    /** ================= Intent 处理 ================= */

    fun handleIntent(intent: MotorIntent) {
        when (intent) {
            is MotorIntent.Step -> {
                _uiState.value =
                    _uiState.value.copy(position = _uiState.value.position + intent.delta)
            }
            is MotorIntent.StartContinuous -> {
                _uiState.value = _uiState.value.copy(previewAngle = intent.direction * 30)
            }
            MotorIntent.StopContinuous -> {
                _uiState.value = _uiState.value.copy(previewAngle = 0)
            }
            else -> Unit
        }
    }
}