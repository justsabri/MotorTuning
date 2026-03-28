package com.example.motortuning

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.reflect.Type

class MotorViewModel(
    application: Application,
    private val comm: MotorComm
) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val gson = GsonBuilder()
        .registerTypeAdapter(ParamDataType::class.java, object : JsonDeserializer<ParamDataType> {
            override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): ParamDataType {
                val typeString = json.asString
                return ParamDataType.fromString(typeString)
            }
        })
        .create()
    
    // 发送数据的回调函数，由外部设置
    var onSendData: ((ByteArray) -> Unit)? = null

    /** ================= 参数定义（来自 JSON） ================= */
    private val _paramDef = MutableStateFlow<List<MotorParam>>(emptyList())
    val paramDef: StateFlow<List<MotorParam>> = _paramDef

    private val _params = MutableStateFlow<Map<String, Any>>(emptyMap())
    val params: StateFlow<Map<String, Any>> = _params

    /** ================= 显示参数（给 UI 用） ================= */
    private val _displayParams = MutableStateFlow<List<DisplayParam>>(emptyList())
    val displayParams: StateFlow<List<DisplayParam>> = _displayParams

    /** ================= 参数当前值（来自蓝牙） ================= */
    private val _realtimeValues = MutableStateFlow<Map<String, Any?>>(emptyMap())
    val realtimeValues: StateFlow<Map<String, Any?>> = _realtimeValues

    /** ================= UI 状态 ================= */

    private val _uiState = MutableStateFlow(MotorUiState())
    val uiState: StateFlow<MotorUiState> = _uiState

    private val _canIdList = MutableStateFlow<Set<Int>>(emptySet())
    val canIdList: StateFlow<Set<Int>> = _canIdList

    private var removeListener: (() -> Unit)? = null
    private var lastUpdateTime: Long = 0L // 跟踪最后更新时间

    init {
        loadAndMergeParams()

        initPosition()
        lastUpdateTime = System.currentTimeMillis() // 初始化最后更新时间

        // 监听 realtimeValues 和 paramDef 的变化，自动更新 displayParams
        viewModelScope.launch {
            combine(
                realtimeValues,
                paramDef
            ) { reals, defs ->
                defs
                    .filter { it.visible }  // 只保留 visible = true 的参数
                    .map { param ->
                        val value = reals[param.key]
                        val handler = ParamTypeHandlerFactory.getHandler(param.type)
                        val displayValue = if (value != null) {
                            handler.formatValue(value, param.resolution)
                        } else {
                            "null"
                        }
                        DisplayParam(
                            name = param.name,
                            value = displayValue
                        )
                    }
            }.collect {
                _displayParams.value = it
            }
        }

        removeListener = comm.listenForDataUpdates { data ->
            processBluetoothData(data)
        }

        // 启动连接超时检查协程
        viewModelScope.launch {
            while (true) {
                delay(1000) // 每秒检查一次
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastUpdateTime > 3000) { // 超过3秒没有更新
                    _uiState.value = _uiState.value.copy(
                        connect = false // 置为未连接
                    )
                }
            }
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
    private suspend fun mergeParams(config: MotorParamConfig): Map<String, Any> {
        val prefs = context.motorParamDataStore.data.first()
        val savedVersion = prefs[MotorParamKeys.CONFIG_VERSION] ?: -1
        val result = mutableMapOf<String, Any>()

        // 先用已保存的值，没有用 JSON 默认值
        config.params.forEach { def ->
            val handler = ParamTypeHandlerFactory.getHandler(def.type)
            result[def.key] = handler.readFromDataStore(prefs, def.key, def.default)
            Log.i("MotorViewModel", "Loaded param: ${def.type} ${def.key} = ${result[def.key]}")
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
        values: Map<String, Any>,
        config: MotorParamConfig
    ) {
        context.motorParamDataStore.edit { edit ->
            // 更新版本号
            edit[MotorParamKeys.CONFIG_VERSION] = config.version

            // 删除已不存在的参数
            prefs.asMap().keys
                .filter { key ->
                    key.name != MotorParamKeys.CONFIG_VERSION.name &&
                            config.params.none { it.key == key.name }
                }
                .forEach { edit.remove(it) }

            // 写入新参数
            config.params.forEach { def ->
                val value = values[def.key]
                value?.let {
                    val handler = ParamTypeHandlerFactory.getHandler(def.type)
                    handler.writeToDataStore(edit, def.key, it)
                }
            }
        }
    }

    /** ================= 参数保存（Dialog 调用） ================= */

    fun saveParams(newParams: Map<String, Any>) {
        viewModelScope.launch {

            // 1️⃣ 持久化（DataStore）
            context.motorParamDataStore.edit { edit ->
                _paramDef.value.forEach { def ->
                    val value = newParams[def.key]
                    value?.let {
                        val handler = ParamTypeHandlerFactory.getHandler(def.type)
                        handler.writeToDataStore(edit, def.key, it)
                    }
                }
            }

            // 2️⃣ 更新内存态
            _params.value = newParams

            // 3️⃣ 下发给电机（可失败、可重试）
            sendParams(ParamType.SET, newParams)

            // 4️⃣ 备份到 JSON（IO）
            withContext(Dispatchers.IO) {
                saveParamsToJson(context, newParams)
            }
        }
    }

    /** 发送参数 */
    private fun sendParams(type: ParamType, params: Map<String, Any>) {
        // 构建发送数据
        val data = buildSendData(type, params)
        // 通过回调发送数据
        comm.send(data)
    }

    /** 构建发送数据 */
    private fun buildSendData(type: ParamType, params: Map<String, Any>): ByteArray {
        // 构建蓝牙数据，格式：Byte0: CMD=0x11, Byte1..: TLV blocks
        val dataList = mutableListOf<Byte>()
        // 添加 CMD=0x01 (SET) 0x02 (GET) 0x03(GET_CONTINUOUS)
        dataList.add(type.type)
        // 添加can_id
        val canId = (params["current_can_id"] as? Float)?.toInt() ?: -1
        Log.i("MotorViewModel", "canId: $canId")
        dataList.add((canId and 0xFF).toByte())
        
        // 添加 TLV blocks
        params.forEach { (key, value) ->
            // 从 _paramDef 中查找对应的 MotorParam 对象
            val motorParam = _paramDef.value.filter { it.editable }.find { it.key == key }
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
                
                // 使用参数类型处理器获取整数表示
                val handler = ParamTypeHandlerFactory.getHandler(motorParam.type)
                val intValue = handler.valueToInt(value, motorParam.resolution)
                
                when (length) {
                    1 -> dataList.add((intValue and 0xFF).toByte())
                    2 -> {
                        dataList.add((intValue and 0xFF).toByte())
                        dataList.add((intValue shr 8 and 0xFF).toByte())
                    }
                    4 -> {
                        dataList.add((intValue and 0xFF).toByte())
                        dataList.add((intValue shr 8 and 0xFF).toByte())
                        dataList.add((intValue shr 16 and 0xFF).toByte())
                        dataList.add((intValue shr 24 and 0xFF).toByte())
                    }
                    8 -> {
                        // 发送 8 字节长度的值（小端序）
                        dataList.add((intValue and 0xFF).toByte())
                        dataList.add((intValue shr 8 and 0xFF).toByte())
                        dataList.add((intValue shr 16 and 0xFF).toByte())
                        dataList.add((intValue shr 24 and 0xFF).toByte())
                        dataList.add((intValue shr 32 and 0xFF).toByte())
                        dataList.add((intValue shr 40 and 0xFF).toByte())
                        dataList.add((intValue shr 48 and 0xFF).toByte())
                        dataList.add((intValue shr 56 and 0xFF).toByte())
                    }
                    // 其他长度可以根据需要添加
                }
            }
        }
        
        return dataList.toByteArray()
    }

    /** 获取参数值 */
    private fun getParams(type: ParamType, can_id:Int, params: List<Int>) {
        // 构建发送数据
        val dataList = mutableListOf<Byte>()
        dataList.add(type.type)
        dataList.add((can_id and 0xFF).toByte())
        params.forEach {
            dataList.add((it and 0xFF).toByte())
        }

        // 通过回调发送数据
        comm.send(dataList.toByteArray())
    }
    /** 处理接收到的蓝牙数据 */
    fun processBluetoothData(data: ByteArray) {
        // 处理接收到的蓝牙数据，按照 TLV 协议解析
        if (data.isNotEmpty()) {
            val cmd = data[0] // 第一个字节是 CMD
            val can_id = data[1] // 第二个字节是can_id
            
            // 跳过 CMD，开始解析 TLV blocks
            var offset = 2
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
                        
                        // 使用参数类型处理器转换值
                        val typedValue: Any = if (motorParam != null) {
                            val handler = ParamTypeHandlerFactory.getHandler(motorParam.type)
                            handler.intToValue(value, motorParam.resolution)
                        } else {
                            value
                        }
                        
                        // 更新实时值
                        motorParam?.key?.let {
                            updateRealtimeValue(it, typedValue)
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

    fun saveParamsToJson(context: Context, newParams: Map<String, Any>) {
        try {
            val file = File(context.filesDir, "motor_params.json")

            // 更新可序列化的列表
            val paramList = _paramDef.value.map { param ->
                val newValue = newParams[param.key]
                if (newValue != null) {
                    // 根据参数类型转换为适当的默认值类型
                    val handler = ParamTypeHandlerFactory.getHandler(param.type)
                    val default = handler.toJsonDefaultValue(newValue)
                    param.copy(default = default)
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
    fun updateRealtimeValue(key: String, value: Any?) {
        _realtimeValues.value = _realtimeValues.value.toMutableMap().apply {
            put(key, value)
        }

        if (key == "position") {
            // 将 value 转换为 Int 用于更新位置
            val intValue = when (value) {
                is Int -> value
                is Float -> value.toInt()
                is Boolean -> if (value) 1 else 0
                else -> 0
            }
            updateRealtimePosition(intValue)
        }
        if (key == "current_can_id") {
            val intValue = when (value) {
                is Int -> value
                is Float -> value.toInt()
                is Boolean -> if (value) 1 else 0
                else -> 0
            }
            _canIdList.value = _canIdList.value + intValue
        }
    }

    /** 初始化 position/previewAngle **/
    private fun initPosition() {
        val reals = _realtimeValues.value
        val positionValue = reals["position"]
        // 将 positionValue 转换为 Int
        val defaultPos = when (positionValue) {
            is Int -> positionValue
            is Float -> positionValue.toInt()
            is Boolean -> if (positionValue) 1 else 0
            else -> 0
        }
        _uiState.value = _uiState.value.copy(
            position = defaultPos,
            previewAngle = defaultPos
        )
    }

    /** 蓝牙收到实时值 **/
    fun updateRealtimePosition(pos: Int?) {
        val newPos = pos ?: _uiState.value.position
        lastUpdateTime = System.currentTimeMillis() // 更新最后更新时间
        _uiState.value = _uiState.value.copy(
            position = newPos,
            previewAngle = newPos,
            connect = true // 置为已连接
        )
    }

    /** ================= 显示参数（给 UI 用） ================= */

    fun buildDisplayParams(): List<DisplayParam> {
        val reals = _realtimeValues.value
        val defParams = _paramDef.value
        return defParams
            .filter { it.visible }  // 只保留 visible = true 的参数
            .map { param ->
                val value = reals[param.key]
                val displayValue = if (value != null) {
                    val handler = ParamTypeHandlerFactory.getHandler(param.type)
                    handler.formatValue(value, param.resolution)
                } else {
                    "null"
                }
                Log.i("MotorViewModel", "Display param ${param.key}, ${value}, ${param.type}: ${param.name} = $displayValue")
                DisplayParam(
                    name = param.name,
                    value = displayValue
                )
            }
    }

    /** ================= Intent 处理 ================= */

    fun handleIntent(intent: MotorIntent) {
        when (intent) {
            is MotorIntent.Step -> {
                _uiState.value =
                    _uiState.value.copy(position = _uiState.value.position + intent.delta)
                sendPosition(intent.can_id, _uiState.value.position)
            }
            is MotorIntent.StartContinuous -> {
                _uiState.value = _uiState.value.copy(previewAngle = intent.direction * 30)
            }
            is MotorIntent.StopContinuous -> {
                _uiState.value = _uiState.value.copy(previewAngle = 0)
            }
            else -> Unit
        }
    }

    fun sendPosition(can_id: Int?, position: Int) {
        if (_params.value.contains("position")) {
            val positionMap = mutableMapOf<String, Any>()
            positionMap["current_can_id"] = can_id as Any
            positionMap["position"] = position
            sendParams(ParamType.SET, positionMap)
        }
    }

    fun updateCanIdList() {
        _canIdList.value = emptySet()
        val getCanIdList = mutableListOf<Int>()
        val defParams = _paramDef.value
//        Log.w("MotorViewModel", "defParams size: ${defParams.size}")
//        defParams.forEach { param ->
//            Log.w("MotorViewModel", "Param key: ${param.key}, paramId: ${param.paramId}")
//        }
        val paramIdString = defParams.find { it.key == "current_can_id" }?.paramId
//        Log.w("MotorViewModel", "paramIdString is ${paramIdString}")
        val paramId: Int? = paramIdString?.let {
            if (it.startsWith("0x", ignoreCase = true)) {
                it.substring(2).toIntOrNull(16) ?: 0
            } else {
                it.toIntOrNull() ?: 0
            }
        }
        if (paramId == null) {
            Log.w("MotorViewModel", "paramId is null")
            return
        }
        getCanIdList.add(paramId)
        getParams(ParamType.GET, 0, getCanIdList)
    }

    fun initCanId(can_id: Int) {
        // GET_CONTINUOUS
        val dataList = mutableListOf<Int>()
        val defParams = _paramDef.value
        defParams.filter { it.visible }.forEach { motorParam ->
            // 解析 paramId 字符串（十六进制值，例如 "0x01"）
            val paramId = motorParam.paramId.let {
                if (it.startsWith("0x", ignoreCase = true)) {
                    it.substring(2).toIntOrNull(16) ?: 0
                } else {
                    it.toIntOrNull() ?: 0
                }
            }
            dataList.add(paramId)
        }

        getParams(ParamType.GET_CONTINUOUS, can_id, dataList)
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