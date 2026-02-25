package com.example.motortuning

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class BluetoothLeViewModel(
    private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow<BluetoothState>(BluetoothState.Disconnected)
    val state: StateFlow<BluetoothState> = _state

    private val _devices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val devices: StateFlow<List<BluetoothDevice>> = _devices

    private val _uiEvent = MutableSharedFlow<BluetoothUiEvent>()
    val uiEvent: SharedFlow<BluetoothUiEvent> = _uiEvent

    private var bluetoothAdapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
    private var gatt: BluetoothGatt? = null

    private val scanResults = mutableMapOf<String, BluetoothDevice>()
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.device?.let { device ->
                scanResults[device.address] = device
                _devices.value = scanResults.values.toList()
            }
        }

        override fun onScanFailed(errorCode: Int) {
            viewModelScope.launch {
                _uiEvent.emit(BluetoothUiEvent.ShowError("BLE 扫描失败: $errorCode"))
            }
        }
    }

    /** 扫描 BLE 设备 */
    @SuppressLint("MissingPermission")
    fun scanDevices() {
        bluetoothAdapter?.isEnabled?.let {
            if (!it == true) {
                viewModelScope.launch { _uiEvent.emit(BluetoothUiEvent.ShowError("请打开蓝牙")) }
                return
            }
        }
        scanResults.clear()
        _devices.value = emptyList()
        bluetoothLeScanner?.startScan(scanCallback)
    }

    /** 停止扫描 */
    @SuppressLint("MissingPermission")
    fun stopScan() {
        bluetoothLeScanner?.stopScan(scanCallback)
    }

    /** 连接 BLE 设备 */
    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        stopScan()
        _state.value = BluetoothState.Connecting(device)

        gatt?.close() // 关闭旧连接
        gatt = device.connectGatt(context, false, gattCallback)
    }

    /** BLE 连接回调 */
    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                viewModelScope.launch {
                    _state.value = BluetoothState.Connected(gatt.device)
                    gatt.discoverServices()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                viewModelScope.launch {
                    _state.value = BluetoothState.Disconnected
                    _uiEvent.emit(BluetoothUiEvent.ShowError("连接断开"))
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            // 发现服务后可以订阅特征
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            // 这里接收 BLE notify 数据
        }
    }

    /** 断开连接 */
    @SuppressLint("MissingPermission")
    fun disconnect() {
        gatt?.close()
        gatt = null
        _state.value = BluetoothState.Disconnected
    }

    override fun onCleared() {
        super.onCleared()
        stopScan()
        disconnect()
    }
}