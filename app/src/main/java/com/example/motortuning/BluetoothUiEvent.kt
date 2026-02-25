package com.example.motortuning

sealed interface BluetoothUiEvent {
    data class ShowError(val message: String) : BluetoothUiEvent
}