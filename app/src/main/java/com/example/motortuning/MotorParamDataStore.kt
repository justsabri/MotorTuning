package com.example.motortuning

import android.content.Context
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

val Context.motorParamDataStore by preferencesDataStore(
    name = "motor_params"
)

object MotorParamKeys {
    val CONFIG_VERSION = intPreferencesKey("config_version")
}