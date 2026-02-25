package com.example.motortuning

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class BluetoothViewModel(
    private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow<BluetoothState>(BluetoothState.Idle)
    val state: StateFlow<BluetoothState> = _state

    private var reconnectJob: Job? = null

    val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    private val _devices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val devices: StateFlow<List<BluetoothDevice>> = _devices

    private var currentDevice: BluetoothDevice? = null

    private val _connectedDevice = MutableStateFlow<BluetoothDevice?>(null)
    val connectedDevice: StateFlow<BluetoothDevice?> = _connectedDevice

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting

    private val _realtimeData = MutableStateFlow<Map<String, Int>>(emptyMap())
    val realtimeData: StateFlow<Map<String, Int>> = _realtimeData

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    private val _uiEvent = MutableSharedFlow<BluetoothUiEvent>()
    val uiEvent = _uiEvent.asSharedFlow()

    // SPP UUID for ESP32
    private val sppUUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    /** 扫描已配对设备 */
    @SuppressLint("MissingPermission")
    fun scanDevicesSafe() {
        _devices.value = bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
    }

    /** 连接设备 */
    fun connect(device: BluetoothDevice) {
        currentDevice = device
        _state.value = BluetoothState.Connecting(device)

        viewModelScope.launch {
            val success = connectInternal(device)
            if (success) {
                _state.value = BluetoothState.Connected(device)
            } else {
                _state.value = BluetoothState.Disconnected

                // 👇 关键：通知 UI
                @SuppressLint("MissingPermission")
                _uiEvent.emit(
                    BluetoothUiEvent.ShowError(
                        "连接 ${device.name ?: "设备"} 失败"
                    )
                )
            }
        }
    }

    private suspend fun connectInternal(device: BluetoothDevice): Boolean =
        withContext(Dispatchers.IO) {
            try {
                _isConnecting.emit(true)

                val tmpSocket =
                    device.createRfcommSocketToServiceRecord(sppUUID)
                tmpSocket.connect() // ✅ IO 线程，阻塞没问题

                socket = tmpSocket
                input = socket?.inputStream
                output = socket?.outputStream
                _connectedDevice.emit(device)

                listenRealtimeData() // 内部自己 launch IO

                true
            } catch (e: Exception) {
                e.printStackTrace()
                disconnect()
                false
            } finally {
                _isConnecting.emit(false)
            }
        }

    fun onConnectionLost() {
        startReconnect()
    }

    private fun startReconnect() {
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            repeat(5) { retry ->
                _state.value = BluetoothState.Reconnecting(retry + 1)
                delay(2000)
                if (tryReconnect()) return@launch
            }
            _state.value = BluetoothState.Disconnected
        }
    }

    private suspend fun tryReconnect(): Boolean {
        val device = currentDevice ?: return false

        val ok = connectInternal(device)
        if (ok) {
            _state.value = BluetoothState.Connected(device)
        }
        return ok
    }

    /** 断开连接 */
    fun disconnect() {
        viewModelScope.launch {
            try {
                input?.close()
                output?.close()
                socket?.close()
            } catch (_: Exception) {}
            socket = null
            input = null
            output = null
            _connectedDevice.value = null
            _state.value = BluetoothState.Disconnected
        }
    }

    fun disconnectByUser() {
        currentDevice = null
        _state.value = BluetoothState.Disconnected
    }

    /** 循环读取 ESP32 数据 */
    private fun listenRealtimeData() {
        viewModelScope.launch {
            val buffer = ByteArray(1024)
            while (socket?.isConnected == true) {
                try {
                    val bytesRead = input?.read(buffer) ?: 0
                    if (bytesRead > 0) {
                        val msg = String(buffer, 0, bytesRead).trim()
                        // 假设 ESP32 发送 JSON：{"position":120,"speed":50}
                        try {
                            val map = msg.split(",").associate {
                                val (k,v) = it.split(":")
                                k.trim() to v.trim().toInt()
                            }
                            _realtimeData.value = map
                        } catch (_: Exception) {}
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    disconnect()
                    break
                }
            }
        }
    }

    /** 发送控制指令到 ESP32 */
    fun sendCommand(cmd: String) {
        viewModelScope.launch {
            try {
                output?.write(cmd.toByteArray())
                output?.flush()
            } catch (e: Exception) {
                e.printStackTrace()
                disconnect()
            }
        }
    }
}

class BluetoothViewModelFactory(
    private val context: Context
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BluetoothViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return BluetoothViewModel(context.applicationContext) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}