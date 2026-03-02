package com.example.motortuning

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
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
    application: Application,
    private val comm: MotorComm
) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val gson = Gson()
    
    // 发送数据的回调函数，由外部设置
    var onSendData: ((ByteArray) -> Unit)? = null

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

    private var removeListener: (() -> Unit)? = null

    init {
        loadAndMergeParams()

        initPosition()

        removeListener = comm.listenForDataUpdates { data ->
            processBluetoothData(data)
        }
    }

    /** ==================== 主加载函数 ==================== **/
    private fun loadAndMergeParams() = runBlocking {
        val config = loadParamConfig()
        val merged = mergeParams(config)
        _paramDef.value = config.params
        _params.value = merged
    }

    override fun onCleared() {
        removeListener?.invoke()
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

    /** 发送参数 */
    private fun sendParams(params: Map<String, Int>) {
        // 构建发送数据
        val data = buildSendData(params)
        // 通过回调发送数据
        comm.send(data)
    }

    /** 构建发送数据 */
    private fun buildSendData(params: Map<String, Int>): ByteArray {
        // 构建蓝牙数据，格式：Byte0: CMD=0x11, Byte1..: TLV blocks
        val dataList = mutableListOf<Byte>()
        // 添加 CMD=0x11 (SET_MULTI)
        dataList.add(0x11.toByte())
        
        // 添加 TLV blocks
        params.forEach { (key, value) ->
            // 从 _paramDef 中查找对应的 MotorParam 对象
            val motorParam = _paramDef.value.find { it.key == key }
            if (motorParam != null) {
                // 解析 paramId 字符串（十六进制值，例如 "0x01"）
                val paramId = motorParam.paramId.let {
                    if (it.startsWith("0x", ignoreCase = true)) {
                        it.substring(2).toIntOrNull(16) ?: 0
                    } else {
                        it.toIntOrNull() ?: 0
                    }
                }
                // 使用 MotorParam 中的 length 字段
                val length = motorParam.length
                
                // 添加 paramId
                dataList.add(paramId.toByte())
                // 添加 length
                dataList.add(length.toByte())
                
                // 根据 length 添加 value 的字节表示（小端序）
                when (length) {
                    1 -> dataList.add((value and 0xFF).toByte())
                    2 -> {
                        dataList.add((value and 0xFF).toByte())
                        dataList.add((value shr 8 and 0xFF).toByte())
                    }
                    4 -> {
                        dataList.add((value and 0xFF).toByte())
                        dataList.add((value shr 8 and 0xFF).toByte())
                        dataList.add((value shr 16 and 0xFF).toByte())
                        dataList.add((value shr 24 and 0xFF).toByte())
                    }
                    8 -> {
                        // 发送 8 字节长度的值（小端序）
                        dataList.add((value and 0xFF).toByte())
                        dataList.add((value shr 8 and 0xFF).toByte())
                        dataList.add((value shr 16 and 0xFF).toByte())
                        dataList.add((value shr 24 and 0xFF).toByte())
                        dataList.add((value shr 32 and 0xFF).toByte())
                        dataList.add((value shr 40 and 0xFF).toByte())
                        dataList.add((value shr 48 and 0xFF).toByte())
                        dataList.add((value shr 56 and 0xFF).toByte())
                    }
                    // 其他长度可以根据需要添加
                }
            }
        }
        
        return dataList.toByteArray()
    }

    /** 处理接收到的蓝牙数据 */
    fun processBluetoothData(data: ByteArray) {
        // 处理接收到的蓝牙数据，按照 TLV 协议解析
        if (data.isNotEmpty()) {
            val cmd = data[0] // 第一个字节是 CMD
            
            // 跳过 CMD，开始解析 TLV blocks
            var offset = 1
            while (offset < data.size) {
                // 确保有足够的字节读取 TLV block 的头部
                if (offset + 2 <= data.size) {
                    val paramId = data[offset].toInt() and 0xFF
                    val length = data[offset + 1].toInt() and 0xFF
                    
                    // 确保有足够的字节读取 value
                    if (offset + 2 + length <= data.size) {
                        // 根据 length 解析 value
                        val value = when (length) {
                            1 -> data[offset + 2].toInt() and 0xFF
                            2 -> {
                                (data[offset + 2].toInt() and 0xFF) or
                                ((data[offset + 3].toInt() and 0xFF) shl 8)
                            }
                            4 -> {
                                (data[offset + 2].toInt() and 0xFF) or
                                ((data[offset + 3].toInt() and 0xFF) shl 8) or
                                ((data[offset + 4].toInt() and 0xFF) shl 16) or
                                ((data[offset + 5].toInt() and 0xFF) shl 24)
                            }
                            8 -> {
                                // 8 字节长度的值，这里只取低 32 位
                                (data[offset + 2].toInt() and 0xFF) or
                                ((data[offset + 3].toInt() and 0xFF) shl 8) or
                                ((data[offset + 4].toInt() and 0xFF) shl 16) or
                                ((data[offset + 5].toInt() and 0xFF) shl 24)
                            }
                            else -> 0
                        }
                        
                        // 根据 paramId 查找对应的 key
                        val motorParam = _paramDef.value.find { param ->
                            // 解析 paramId 字符串
                            val id = param.paramId.let {
                                if (it.startsWith("0x", ignoreCase = true)) {
                                    it.substring(2).toIntOrNull(16) ?: 0
                                } else {
                                    it.toIntOrNull() ?: 0
                                }
                            }
                            id == paramId
                        }
                        
                        // 更新实时值
                        motorParam?.key?.let {
                            updateRealtimeValue(it, value)
                        }
                        
                        // 移动到下一个 TLV block
                        offset += 2 + length
                    } else {
                        // 数据不完整，退出循环
                        break
                    }
                } else {
                    // 数据不完整，退出循环
                    break
                }
            }
        }
    }

    fun saveParamsToJson(context: Context, newParams: Map<String, Int>) {
        try {
            val file = File(context.filesDir, "motor_params.json")

            // 更新可序列化的列表
            val paramList = _paramDef.value.map { param ->
                val newValue = newParams[param.key]
                if (newValue != null) {
                    param.copy(default = newValue)
                } else {
                    param
                }
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

class MotorViewModelFactory(
    private val application: Application,
    private val comm: MotorComm
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MotorViewModel::class.java)) {
            return MotorViewModel(application, comm) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}