package com.example.motortuning

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DualBluetoothViewModel(
    private val context: Context
) : ViewModel(), MotorComm {

    private val classic: BaseBluetoothManager  = ClassicBluetoothManager(context)
    private val ble: BaseBluetoothManager  = BleBluetoothManager(context)

    private val _devices = MutableStateFlow<List<BtDevice>>(emptyList())
    val devices: StateFlow<List<BtDevice>> = _devices

    private var activeManager: BaseBluetoothManager? = null

    private val _state = MutableStateFlow<BluetoothState>(BluetoothState.Idle)
    val state: StateFlow<BluetoothState> = _state

    private val _uiEvent = MutableSharedFlow<BluetoothUiEvent>()
    val uiEvent: SharedFlow<BluetoothUiEvent> = _uiEvent

    override val connected: StateFlow<Boolean> =
        state.map { it is BluetoothState.Connected }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private val dataListeners =
        mutableSetOf<(ByteArray) -> Unit>()

//    init {
//        classic.onDataReceived = { data ->
//            dataListeners.forEach { it(data) }
//        }
//
//        ble.onDataReceived = { data ->
//            dataListeners.forEach { it(data) }
//        }
//    }

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
                activeManager =
                    if (device is BtDevice.Classic) classic else ble
                activeManager?.listenForDataUpdates { data ->
                    dataListeners.forEach { it(data) }
                }
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

    /** 发送数据 */
    override fun send(data: ByteArray) {
        activeManager?.send(data)
    }

    override fun listenForDataUpdates(
        onDataReceived: (ByteArray) -> Unit
    ):() -> Unit {
        dataListeners += onDataReceived
        return {
            dataListeners -= onDataReceived
        }
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