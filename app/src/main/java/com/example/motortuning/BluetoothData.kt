package com.example.motortuning

import android.bluetooth.BluetoothDevice

sealed interface BluetoothState {
    object Idle : BluetoothState
    object Scanning : BluetoothState
    data class Connecting(val device: BtDevice) : BluetoothState
    data class Connected(val device: BtDevice) : BluetoothState
    data class Reconnecting(val retry: Int) : BluetoothState
    object Disconnected : BluetoothState
}

sealed interface ClassicBluetoothState {
    object Idle : ClassicBluetoothState
    object Scanning : ClassicBluetoothState
    data class Connecting(val device: BluetoothDevice) : ClassicBluetoothState
    data class Connected(val device: BluetoothDevice) : ClassicBluetoothState
    data class Reconnecting(val retry: Int) : ClassicBluetoothState
    object Disconnected : ClassicBluetoothState
}