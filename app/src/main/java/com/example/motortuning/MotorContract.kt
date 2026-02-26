package com.example.motortuning

// 电机 UI 状态
data class MotorUiState(
    val position: Int = 0,
    val connect: Boolean = false,
    val error: String? = null,
    val previewAngle: Int = 0 // 用于显示预计角度（动画）
)

// 电机操作意图
sealed interface MotorIntent {
    data class SetMaxPos(val value: Int) : MotorIntent
    data class SetSpeed(val value: Int) : MotorIntent
    data class SetTemperature(val value: Float) : MotorIntent
    data class Step(val delta: Int) : MotorIntent
    data class StartContinuous(val direction: Int) : MotorIntent
    object StopContinuous : MotorIntent
}

data class DisplayParam(
    val name: String,
    val value: String
)

data class MotorParam(
    val key: String,
    val name: String,
    val default: Int,
    val unit: String = "",
    val editable: Boolean = true,
    val min: Int? = null,
    val max: Int? = null
)



