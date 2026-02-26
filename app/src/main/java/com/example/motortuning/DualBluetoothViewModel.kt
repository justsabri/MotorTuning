package com.example.motortuning

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class DualBluetoothViewModel(
    private val context: Context
) : ViewModel() {

    private val classic = ClassicBluetoothManager(context)
    private val ble = BleBluetoothManager(context)

    private val _devices = MutableStateFlow<List<BtDevice>>(emptyList())
    val devices: StateFlow<List<BtDevice>> = _devices

    private val _state = MutableStateFlow<BluetoothState>(BluetoothState.Idle)
    val state: StateFlow<BluetoothState> = _state

    private val _uiEvent = MutableSharedFlow<BluetoothUiEvent>()
    val uiEvent: SharedFlow<BluetoothUiEvent> = _uiEvent

    fun scanDevicesSafe() {
        _state.value = BluetoothState.Scanning

        classic.startScan { device ->
            addDevice(BtDevice.Classic(device))
        }

        ble.startScan { device ->
            addDevice(BtDevice.Ble(device))
        }
    }

    private fun addDevice(device: BtDevice) {
        val list = _devices.value.toMutableList()
        if (list.none { it.address == device.address }) {
            list.add(device)
            _devices.value = list
        }
    }

    fun connect(device: BtDevice) {
        _state.value = BluetoothState.Connecting(device)

        viewModelScope.launch {
            val success = when (device) {
                is BtDevice.Classic ->
                    classic.connect(device.device)

                is BtDevice.Ble ->
                    ble.connect(device.device)
            }

            if (success) {
                _state.value = BluetoothState.Connected(device)
            } else {
                _state.value = BluetoothState.Disconnected
                _uiEvent.emit(
                    BluetoothUiEvent.ShowError(
                        "连接 ${device.name ?: "设备"} 失败"
                    )
                )
            }
        }
    }

    fun disconnect() {
        classic.disconnect()
        ble.disconnect()
        _state.value = BluetoothState.Disconnected
    }
}

class DualBluetoothViewModelFactory(
    private val context: Context
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DualBluetoothViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return DualBluetoothViewModel(context.applicationContext) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}