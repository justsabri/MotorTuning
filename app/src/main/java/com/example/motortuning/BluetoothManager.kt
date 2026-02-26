package com.example.motortuning

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

sealed class BtDevice {
    abstract val name: String?
    abstract val address: String

    data class Classic(val device: BluetoothDevice) : BtDevice() {
        @SuppressLint("MissingPermission")
        override val name = device.name
        override val address = device.address
    }

    data class Ble(val device: BluetoothDevice) : BtDevice() {
        @SuppressLint("MissingPermission")
        override val name = device.name
        override val address = device.address
    }
}

class ClassicBluetoothManager(
    private val context: Context
) {

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private val adapter: BluetoothAdapter? =
        bluetoothManager.adapter

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    private val sppUUID: UUID =
        UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private var receiver: BroadcastReceiver? = null

    /** ================= 扫描 ================= */

    @SuppressLint("MissingPermission")
    fun startScan(onDeviceFound: (BluetoothDevice) -> Unit) {
        if (!hasBluetoothPermission(context)) {
            Log.d("ClassicBT", "No bluetooth permission.")
            return
        }
        stopScan()
        if (adapter == null || !adapter.isEnabled) {
            Log.e("ClassicBT", "Bluetooth adapter is not enabled.")
            return
        }
        Log.d("ClassicBT", "Start scanning...")
        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (BluetoothDevice.ACTION_FOUND == intent.action) {
                    val device: BluetoothDevice? =
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    device?.let {
                        Log.d("ClassicBT", "Found device: ${it.name} ${it.address}")
                        onDeviceFound(it)
                    }
                }
            }
        }

        context.registerReceiver(
            receiver,
            IntentFilter(BluetoothDevice.ACTION_FOUND)
        )

        val isDiscovering = adapter?.isDiscovering ?: false
        if (isDiscovering) {
            adapter?.cancelDiscovery()
            Log.d("ClassicBT", "Previous scan canceled")
        }

        val started = adapter?.startDiscovery() ?: false
        Log.d("ClassicBT", "Discovery started: $started")
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        try {
            receiver?.let {
                context.unregisterReceiver(it)
            }
        } catch (_: Exception) {
        }
        receiver = null
        adapter?.cancelDiscovery()
    }

    /** ================= 连接 ================= */

    suspend fun connect(device: BluetoothDevice): Boolean =
        withContext(Dispatchers.IO) {
            try {
                stopScan()

                val tmp =
                    device.createRfcommSocketToServiceRecord(sppUUID)
                tmp.connect()

                socket = tmp
                input = tmp.inputStream
                output = tmp.outputStream
                true
            } catch (e: Exception) {
                e.printStackTrace()
                disconnect()
                false
            }
        }

    fun disconnect() {
        try {
            input?.close()
            output?.close()
            socket?.close()
        } catch (_: Exception) {
        }
        input = null
        output = null
        socket = null
    }

    /** ================= 发送 ================= */

    fun send(data: ByteArray) {
        try {
            output?.write(data)
            output?.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

class BleBluetoothManager(
    private val context: Context
) {

    private val adapter =
        (context.getSystemService(Context.BLUETOOTH_SERVICE)
                as BluetoothManager).adapter

    private val scanner = adapter.bluetoothLeScanner

    private var gatt: BluetoothGatt? = null

    /** 根据 ESP32 固件改 */
    private val serviceUUID =
        UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB")
    private val charUUID =
        UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB")

    private var writeChar: BluetoothGattCharacteristic? = null

    /** ================= 扫描 ================= */

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(
            callbackType: Int,
            result: ScanResult?
        ) {
            Log.d("BLE", "Scan result: $result")
            result?.device?.let {
                onDeviceFound?.invoke(it)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            // TODO: 这里可以 emit UI 错误，比如权限 / 蓝牙未开启
            Log.d("BLE", "Scan failed: $errorCode")
        }
    }

    private var onDeviceFound: ((BluetoothDevice) -> Unit)? = null

    private val gattCallback = object : BluetoothGattCallback() {

        // 成功写入数据
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // 写入成功，执行相关逻辑
                Log.d("BLE", "Write successful!")
            } else {
                // 写入失败，执行错误处理
                Log.e("BLE", "Write failed with status: $status")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            super.onCharacteristicChanged(gatt, characteristic, value)
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan(onFound: (BluetoothDevice) -> Unit) {
        Log.d("BLE", "Start scanning...")
        onDeviceFound = onFound
        scanner.startScan(scanCallback)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanner.stopScan(scanCallback)
        onDeviceFound = null
    }

    /** ================= 连接 ================= */

    @SuppressLint("MissingPermission")
    suspend fun connect(device: BluetoothDevice): Boolean =
        suspendCancellableCoroutine { cont ->

            gatt = device.connectGatt(
                context,
                false,
                object : BluetoothGattCallback() {

                    override fun onConnectionStateChange(
                        gatt: BluetoothGatt,
                        status: Int,
                        newState: Int
                    ) {
                        if (newState == BluetoothProfile.STATE_CONNECTED) {
                            gatt.discoverServices()
                        } else {
                            // 取消时的处理逻辑，optional
                            cont.resume(false) { cause, _, _ -> // 取消时的处理逻辑，optional
                                // 取消时的处理逻辑，optional
                                Log.e("BLE", "Coroutine was cancelled: $cause")
                            }
                        }
                    }

                    override fun onServicesDiscovered(
                        gatt: BluetoothGatt,
                        status: Int
                    ) {
                        val service = gatt.getService(serviceUUID)
                        writeChar =
                            service?.getCharacteristic(charUUID)

                        // 取消时的处理逻辑，optional
                        cont.resume(writeChar != null) { cause, _, _ -> // 取消时的处理逻辑，optional
                            // 取消时的处理逻辑，optional
                            Log.e("BLE", "Coroutine was cancelled: $cause")
                        }
                    }
                }
            )
        }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        writeChar = null
    }

    /** ================= 发送 ================= */

    @SuppressLint("MissingPermission", "NewApi")
    fun writeCharacteristicAsync(
        characteristic: BluetoothGattCharacteristic,
        data: ByteArray
    ): Any {
        val bluetoothGatt = gatt ?: run {
            Log.e("BLE", "Gatt is null")
            return false
        }

        return bluetoothGatt.writeCharacteristic(
            characteristic,
            data,
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        )
    }

    // example
//    fun writeTemp(value: Int) {
//        val characteristic = BluetoothGattCharacteristic(
//            TEMPERATURE.UUID,
//            BluetoothGattCharacteristic.PROPERTY_WRITE,
//            BluetoothGattCharacteristic.PERMISSION_WRITE
//        )
//
//        characteristic.setValue(value)
//
//        writeCharacteristicAsync(characteristic)
//    }
}