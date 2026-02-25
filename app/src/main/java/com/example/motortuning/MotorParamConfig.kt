package com.example.motortuning

/**
 * 对应 motor_params.json 的根结构
 */
data class MotorParamConfig(
    val version: Int,
    val params: List<MotorParam>
)