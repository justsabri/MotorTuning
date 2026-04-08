package com.example.motortuning

import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.StateFlow

interface MotorComm {

    /** 是否已连接 */
    val connected: StateFlow<Boolean>

    /** 监听来自设备的数据 */
    fun listenForDataUpdates(
        onDataReceived: (ByteArray) -> Unit
    ): () -> Unit

    /** 发送数据到设备 */
    fun send(data: ByteArray)
}

// 电机 UI 状态
data class MotorUiState(
    val position: Int = 0,
    val connect: Boolean = false,
    val error: String? = null,
    val previewAngle: Int = 0, // 用于显示预计角度（动画）
    val previewChange: Boolean = false
)

// 电机操作意图
sealed interface MotorIntent {
    data class SetMaxPos(val value: Int) : MotorIntent
    data class SetSpeed(val value: Int) : MotorIntent
    data class SetTemperature(val value: Float) : MotorIntent
    data class Step(val can_id: Int?, val delta: Int) : MotorIntent
    data class StartContinuous(val can_id: Int?, val direction: Int) : MotorIntent
    class StopContinuous(val can_id: Int?) : MotorIntent
}

data class DisplayParam(
    val name: String,
    val value: String,
    val unit: String
)

// 参数类型处理器接口（整合了原有的 ParamTypeHandler 和 ParamTypeStorageHandler）
interface ParamTypeHandler {
    /**
     * 将值转换为整数表示（用于字节转换）
     */
    fun valueToInt(value: Any, resolution: Float): Int
    
    /**
     * 将整数转换为对应类型的值
     */
    fun intToValue(intValue: Int, resolution: Float): Any
    
    /**
     * 格式化值用于显示
     */
    fun formatValue(value: Any, resolution: Float): String
    
    /**
     * 从 DataStore 中读取值
     */
    fun readFromDataStore(prefs: Preferences, key: String, default: Int): Any
    
    /**
     * 写入值到 DataStore
     */
    fun writeToDataStore(edit: androidx.datastore.preferences.core.MutablePreferences, key: String, value: Any)
    
    /**
     * 将值转换为 JSON 默认值类型（Int）
     */
    fun toJsonDefaultValue(value: Any): Int
}

// 参数数据类型枚举
enum class ParamDataType {
    INT,
    FLOAT,
    BOOLEAN;

    companion object {
        fun fromString(value: String): ParamDataType {
            return when (value.uppercase()) {
                "INT" -> INT
                "FLOAT" -> FLOAT
                "BOOLEAN" -> BOOLEAN
                else -> INT
            }
        }
    }
}

// 整数类型处理器
class IntParamTypeHandler : ParamTypeHandler {
    override fun valueToInt(value: Any, resolution: Float): Int {
        val intValue = when (value) {
            is Int -> value
            is Float -> value.toInt()
            is Boolean -> if (value) 1 else 0
            else -> 0
        }
        return intValue
    }

    override fun intToValue(intValue: Int, resolution: Float): Any {
        return intValue
    }

    override fun formatValue(value: Any, resolution: Float): String {
        return value.toString()
    }

    override fun readFromDataStore(prefs: Preferences, key: String, default: Int): Any {
        val prefKey = androidx.datastore.preferences.core.intPreferencesKey(key)
        return prefs[prefKey] ?: default
    }

    override fun writeToDataStore(edit: androidx.datastore.preferences.core.MutablePreferences, key: String, value: Any) {
        val prefKey = androidx.datastore.preferences.core.intPreferencesKey(key)
        val intValue = when (value) {
            is Int -> value
            is Float -> value.toInt()
            is Boolean -> if (value) 1 else 0
            else -> 0
        }
        edit[prefKey] = intValue
    }

    override fun toJsonDefaultValue(value: Any): Int {
        return when (value) {
            is Int -> value
            is Float -> value.toInt()
            is Boolean -> if (value) 1 else 0
            else -> 0
        }
    }
}

// 浮点数类型处理器
class FloatParamTypeHandler : ParamTypeHandler {
    override fun valueToInt(value: Any, resolution: Float): Int {
        val floatValue = when (value) {
            is Float -> value
            is Int -> value.toFloat()
            is Boolean -> if (value) 1f else 0f
            else -> 0f
        }
        return (floatValue / resolution).toInt()
    }

    override fun intToValue(intValue: Int, resolution: Float): Any {
        return Float.fromBits(intValue)
    }

    override fun formatValue(value: Any, resolution: Float): String {
        val floatValue = when (value) {
            is Float -> value
            is Int -> value.toFloat()
            is Boolean -> if (value) 1f else 0f
            else -> 0f
        }
        val decimalPlaces = when (resolution) {
            0.01f -> 2
            0.1f -> 1
            else -> 0
        }
        return "%.${decimalPlaces}f".format(floatValue)
    }

    override fun readFromDataStore(prefs: Preferences, key: String, default: Int): Any {
        val prefKey = androidx.datastore.preferences.core.floatPreferencesKey(key)
        return prefs[prefKey] ?: default.toFloat()
    }

    override fun writeToDataStore(edit: androidx.datastore.preferences.core.MutablePreferences, key: String, value: Any) {
        val prefKey = androidx.datastore.preferences.core.floatPreferencesKey(key)
        val floatValue = when (value) {
            is Float -> value
            is Int -> value.toFloat()
            is Boolean -> if (value) 1f else 0f
            else -> 0f
        }
        edit[prefKey] = floatValue
    }

    override fun toJsonDefaultValue(value: Any): Int {
        val floatValue = when (value) {
            is Float -> value
            is Int -> value.toFloat()
            is Boolean -> if (value) 1f else 0f
            else -> 0f
        }
        return floatValue.toInt()
    }
}

// 布尔类型处理器
class BooleanParamTypeHandler : ParamTypeHandler {
    override fun valueToInt(value: Any, resolution: Float): Int {
        val booleanValue = when (value) {
            is Boolean -> value
            is Int -> value != 0
            is Float -> value != 0f
            else -> false
        }
        return if (booleanValue) 1 else 0
    }

    override fun intToValue(intValue: Int, resolution: Float): Any {
        return intValue != 0
    }

    override fun formatValue(value: Any, resolution: Float): String {
        val booleanValue = when (value) {
            is Boolean -> value
            is Int -> value != 0
            is Float -> value != 0f
            else -> false
        }
        return if (booleanValue) "true" else "false"
    }

    override fun readFromDataStore(prefs: Preferences, key: String, default: Int): Any {
        val prefKey = androidx.datastore.preferences.core.booleanPreferencesKey(key)
        return prefs[prefKey] ?: (default != 0)
    }

    override fun writeToDataStore(edit: androidx.datastore.preferences.core.MutablePreferences, key: String, value: Any) {
        val prefKey = androidx.datastore.preferences.core.booleanPreferencesKey(key)
        val booleanValue = when (value) {
            is Boolean -> value
            is Int -> value != 0
            is Float -> value != 0f
            else -> false
        }
        edit[prefKey] = booleanValue
    }

    override fun toJsonDefaultValue(value: Any): Int {
        val booleanValue = when (value) {
            is Boolean -> value
            is Int -> value != 0
            is Float -> value != 0f
            else -> false
        }
        return if (booleanValue) 1 else 0
    }
}

// 参数类型处理器工厂
object ParamTypeHandlerFactory {
    private val handlers = mutableMapOf<ParamDataType, ParamTypeHandler>()
    
    init {
        // 注册默认处理器
        registerHandler(ParamDataType.INT, IntParamTypeHandler())
        registerHandler(ParamDataType.FLOAT, FloatParamTypeHandler())
        registerHandler(ParamDataType.BOOLEAN, BooleanParamTypeHandler())
    }
    
    /**
     * 注册参数类型处理器
     */
    fun registerHandler(type: ParamDataType, handler: ParamTypeHandler) {
        handlers[type] = handler
    }
    
    /**
     * 获取参数类型处理器
     */
    fun getHandler(type: ParamDataType?): ParamTypeHandler {
        return handlers[type ?: ParamDataType.INT] ?: IntParamTypeHandler() // 默认返回整数处理器
    }
}

data class MotorParam(
    val key: String,
    val name: String,
    val default: Int,
    val unit: String = "",
    val visible: Boolean = true,
    val editable: Boolean = true,
    val min: Int? = null,
    val max: Int? = null,
    val paramId: String,
    val length: Int,
    val resolution: Float,
    val type: ParamDataType = ParamDataType.INT
) {
    // 用于 Gson 反序列化
    constructor(
        key: String,
        name: String,
        default: Int,
        unit: String = "",
        visible: Boolean = true,
        editable: Boolean = true,
        min: Int? = null,
        max: Int? = null,
        paramId: String,
        length: Int,
        resolution: Float,
        type: String = "INT"
    ) : this(
        key,
        name,
        default,
        unit,
        visible,
        editable,
        min,
        max,
        paramId,
        length,
        resolution,
        ParamDataType.fromString(type)
    )
}

enum class ParamType(val type: Byte) {
    SET(0x01.toByte()),
    GET(0x02.toByte()),
    GET_CONTINUOUS(0x03.toByte());

    companion object {
        fun fromId(type: Byte): ParamType? {
            return entries.find { it.type == type }
        }
    }

    fun buildCmdWithFreq(type: ParamType, freqLevel: Int): Byte {
        // freqLevel 只能 0..15
        require(freqLevel in 0..15) { "freqLevel must be 0..15" }

        // 低4位 = 命令, 高4位 = 频率
        return ((freqLevel shl 4) or (type.type.toInt() and 0x0F)).toByte()
    }
}



