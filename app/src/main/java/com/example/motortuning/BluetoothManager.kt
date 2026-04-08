package com.example.motortuning

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

sealed class BtDevice {
    abstract val name: String?
    abstract val address: String
    abstract val type: BluetoothType  // 增加设备类型信息

    data class Classic(val device: BluetoothDevice) : BtDevice() {
        @SuppressLint("MissingPermission")
        override val name = device.name
        override val address = device.address
        override val type = BluetoothType.Classic
    }

    data class Ble(val device: BluetoothDevice) : BtDevice() {
        @SuppressLint("MissingPermission")
        override val name = device.name
        override val address = device.address
        override val type = BluetoothType.Ble
    }
}

enum class BluetoothType {
    Classic, Ble
}

/** 蓝牙基类接口/抽象类 */
abstract class BaseBluetoothManager {

    /** 当前状态 */
//    abstract val state: StateFlow<BluetoothState>

    /** 扫描到的设备列表 */
//    abstract val devices: StateFlow<List<BtDevice>>

    /** UI 事件或错误事件 */
//    abstract val uiEvent: SharedFlow<BluetoothUiEvent>

    /** 开始扫描 */
    abstract fun startScan(onDeviceFound: (BluetoothDevice) -> Unit)

    /** 停止扫描 */
    abstract fun stopScan()

    /** 连接设备 */
    abstract suspend fun connect(device: BluetoothDevice): Boolean

    /** 断开连接 */
    abstract fun disconnect()

    /** 发送数据 */
    abstract fun send(data: ByteArray): Int

    /** 开启数据接收 */
    abstract fun listenForDataUpdates(onDataReceived: (ByteArray) -> Unit)

    /** 可选：接收数据 Flow */
    abstract val receivedData: SharedFlow<ByteArray>

    var onDataReceived: ((ByteArray) -> Unit)? = null
    var onConnectionStateChanged: ((Boolean) -> Unit)? = null
}

class ClassicBluetoothManager(
    private val context: Context
)  : BaseBluetoothManager() {

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
    override fun startScan(onDeviceFound: (BluetoothDevice) -> Unit) {
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
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            TODO("VERSION.SDK_INT < TIRAMISU")
                        }
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
    override fun stopScan() {
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

    override suspend fun connect(device: BluetoothDevice): Boolean = 
        withContext(Dispatchers.IO) {
            try {
                stopScan()

                val tmp =
                    device.createRfcommSocketToServiceRecord(sppUUID)
                tmp.connect()

                socket = tmp
                input = tmp.inputStream
                output = tmp.outputStream
                onConnectionStateChanged?.invoke(true)
                true
            } catch (e: Exception) {
                e.printStackTrace()
                disconnect()
                onConnectionStateChanged?.invoke(false)
                false
            }
        }

    override fun disconnect() {
        try {
            input?.close()
            output?.close()
            socket?.close()
        } catch (_: Exception) {
        }
        input = null
        output = null
        socket = null
        onConnectionStateChanged?.invoke(false)
    }

    /** ================= 发送 ================= */
    override fun send(data: ByteArray): Int {
        try {
            output?.write(data)
            output?.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return 0
    }

    override val receivedData: SharedFlow<ByteArray>
        get() = TODO("Not yet implemented")

    /** ================= 接收 ================= */
    override fun listenForDataUpdates(onDataReceived: (ByteArray) -> Unit) {
        Thread {
            try {
                val buffer = ByteArray(1024)
                var bytes: Int

                while (true) {
                    bytes = input?.read(buffer) ?: -1
                    if (bytes > 0) {
                        val data = buffer.copyOf(bytes)
                        onDataReceived(data)  // 处理接收到的数据
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }
}

class BleBluetoothManager(
    private val context: Context
)  : BaseBluetoothManager() {

    private val adapter =
        (context.getSystemService(Context.BLUETOOTH_SERVICE)
                as BluetoothManager).adapter

    private val scanner = adapter.bluetoothLeScanner

    private var gatt: BluetoothGatt? = null

    /** 根据 ESP32 固件改 */
    private val serviceUUID =
        UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
    private val writeCharUUID =
        UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")

    private var writeChar: BluetoothGattCharacteristic? = null

    private val readCharUUID =
        UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a9")

    private var readChar: BluetoothGattCharacteristic? = null
    
    // 发送队列，避免数据丢失
    private val dataQueue = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
    private var isSending = false
    
    // 接收数据的 SharedFlow
    private val _receivedData = kotlinx.coroutines.flow.MutableSharedFlow<ByteArray>()

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
    val SCAN_PERIOD: Long = 10000 // 10 秒
    val handler = Handler(Looper.getMainLooper())

    @SuppressLint("MissingPermission")
    override fun startScan(onDeviceFound: (BluetoothDevice) -> Unit) {
        Log.d("BLE", "Start scanning...")
        this@BleBluetoothManager.onDeviceFound = onDeviceFound
        scanner.startScan(scanCallback)
        handler.postDelayed({
            scanner.stopScan(scanCallback)
        }, SCAN_PERIOD)
    }

    @SuppressLint("MissingPermission")
    override fun stopScan() {
        scanner.stopScan(scanCallback)
        onDeviceFound = null
    }

    /** ================= 连接 ================= */

    @SuppressLint("MissingPermission")
    override suspend fun connect(device: BluetoothDevice): Boolean =
        suspendCancellableCoroutine { cont ->
            Log.d("BLE", "Connecting to ${device.address}")
            scanner.stopScan(scanCallback)
            gatt = device.connectGatt(
                context,
                false,
                object : BluetoothGattCallback() {

                    override fun onConnectionStateChange(
                        gatt: BluetoothGatt,
                        status: Int,
                        newState: Int
                    ) {
                        Log.d("BLE", "Connection state changed: $status to $newState")

                        if (newState == BluetoothProfile.STATE_CONNECTED) {
                            gatt.discoverServices()
                            onConnectionStateChanged?.invoke(true)
                        } else {
                            // 取消时的处理逻辑，optional
                            onConnectionStateChanged?.invoke(false)
//                            cont.resume(false) { cause, _, _ -> // 取消时的处理逻辑，optional
//                                // 取消时的处理逻辑，optional
//                                Log.e("BLE", "Coroutine was cancelled: $cause")
//                            }
                        }
                    }

                    override fun onServicesDiscovered(
                        gatt: BluetoothGatt,
                        status: Int
                    ) {
                        Log.d("BLE", "Services discovered!")
                        val service = gatt.getService(serviceUUID)
                        writeChar =
                            service?.getCharacteristic(writeCharUUID)
                        readChar =
                            service?.getCharacteristic(readCharUUID)

                        // 取消时的处理逻辑，optional
                        cont.resume(writeChar != null && readChar != null) { cause, _, _ -> // 取消时的处理逻辑，optional
                            // 取消时的处理逻辑，optional
                            Log.e("BLE", "Coroutine was cancelled: $cause")
                        }
                    }

                    // 成功写入数据
                    override fun onCharacteristicWrite(
                        gatt: BluetoothGatt?,
                        characteristic: BluetoothGattCharacteristic?,
                        status: Int
                    ) {
                        synchronized(this) {
                            isSending = false
                            if (status == BluetoothGatt.GATT_SUCCESS) {
                                // 写入成功，执行相关逻辑
                                Log.d("BLE", "Write successful!")
                            } else {
                                // 写入失败，执行错误处理
                                Log.e("BLE", "Write failed with status: $status")
                            }
                            // 处理下一个数据
                            processNextData()
                        }
                    }

                    override fun onCharacteristicChanged(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        value: ByteArray
                    ) {
                        super.onCharacteristicChanged(gatt, characteristic, value)
                        // 处理接收到的数据
                        Log.d("BLE", "Data received: ${value.contentToString()}")
                        Log.d("BLE", "Data received cb: ${onDataReceived}")
                        onDataReceived?.invoke(value)
                        // 发送到 SharedFlow
//                        kotlinx.coroutines.GlobalScope.launch {
//                            _receivedData.emit(value)
//                        }
                    }

                    override fun onDescriptorWrite(
                        gatt: BluetoothGatt?,
                        descriptor: BluetoothGattDescriptor?,
                        status: Int
                    ) {
                        super.onDescriptorWrite(gatt, descriptor, status)
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            Log.d("BLE", "通知订阅成功")
                        }
                    }
                }
            )
        }

    @SuppressLint("MissingPermission")
    override fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        writeChar = null
        readChar = null
        dataQueue.clear()
        isSending = false
    }

    /** ================= 发送 ================= */
    @SuppressLint("MissingPermission")
    override fun send(data: ByteArray): Int {
        synchronized(dataQueue) {
            val mtu = 20 // 典型的 BLE MTU 大小，实际值可能需要根据设备调整
            
            // 检查数据长度，如果不超过 MTU 限制，直接发送
            if (data.size <= mtu) {
                dataQueue.offer(data)
            } else {
                // 设计切片逻辑，确保每个切片包含尽可能多的完整 TLV blocks
                if (data.isNotEmpty()) {
                    val cmd = data[0] // 第一个字节是 CMD
                    val can_id = data[1] // 第二个字节是can_id

                    // 跳过 CMD和can_id，开始解析 TLV blocks
                    var offset = 2
                    while (offset < data.size) {
                        // 尝试在一个切片中包含尽可能多的完整 TLV blocks
                        var currentLength = 2 // 1 字节 CMD + 1字节 can_id
                        val chunkTLVData = mutableListOf<Byte>()
                        
                        // 循环添加 TLV blocks，直到达到 MTU 限制
                        while (offset < data.size) {
                            // 确保有足够的字节读取 TLV block 的头部
                            if (offset + 2 <= data.size) {
                                val paramId = data[offset]
                                val length = data[offset + 1].toInt() and 0xFF
                                
                                // 计算这个 TLV block 的长度
                                val tlvBlockLength = 2 + length // 2 字节头部 + length 字节值
                                
                                // 检查添加这个 TLV block 后是否会超过 MTU
                                if (currentLength + tlvBlockLength <= mtu) {
                                    // 添加这个 TLV block
                                    chunkTLVData.add(paramId)
                                    chunkTLVData.add(length.toByte())
                                    // 添加 value 部分
                                    for (i in 0 until length) {
                                        if (offset + 2 + i < data.size) {
                                            chunkTLVData.add(data[offset + 2 + i])
                                        }
                                    }
                                    currentLength += tlvBlockLength
                                    offset += tlvBlockLength
                                } else {
                                    // 超过 MTU 限制，停止添加
                                    break
                                }
                            } else {
                                // 数据不完整，停止添加
                                break
                            }
                        }
                        
                        // 创建并发送这个切片
                        if (chunkTLVData.isNotEmpty()) {
                            val chunk = ByteArray(currentLength)
                            chunk[0] = cmd // 设置 CMD
                            chunk[1] = can_id // 设置 can_id
                            // 复制 TLV blocks
                            for (i in 0 until chunkTLVData.size) {
                                chunk[i + 2] = chunkTLVData[i]
                            }
                            Log.i("MotorViewModel", "chunk: ${chunk.toHexString()}")
                            dataQueue.offer(chunk)
                        } else {
                            // 如果没有添加任何 TLV block，退出循环
                            break
                        }
                    }
                }
            }
            
            processNextData()
            return data.size
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun processNextData() {
        synchronized(dataQueue) {
            if (dataQueue.isNotEmpty() && !isSending) {
                isSending = true
                val data = dataQueue.poll()
                Log.i("MotorViewModel", "processNextData: ${data?.toHexString()}")
                data?.let {
                    writeData(it)
                }
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun writeData(data: ByteArray) {
        val gatt = gatt ?: run {
            synchronized(dataQueue) {
                isSending = false
            }
            return
        }
        val char = writeChar ?: run {
            synchronized(dataQueue) {
                isSending = false
            }
            return
        }

        val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // 在 Android 13+ 中，writeCharacteristic 返回 int
            val result = gatt.writeCharacteristic(
                char,
                data,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
            result == BluetoothGatt.GATT_SUCCESS
        } else {
            // 在 Android 12 及以下版本中，writeCharacteristic 返回 boolean
            char.value = data
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            gatt.writeCharacteristic(char)
        }

        if (!success) {
            synchronized(dataQueue) {
                isSending = false
                processNextData()
            }
        }
    }

    override val receivedData: SharedFlow<ByteArray>
        get() = _receivedData.asSharedFlow()


    /** ================= 接收 ================= */
    @SuppressLint("MissingPermission")
    override fun listenForDataUpdates(onDataReceived: (ByteArray) -> Unit) {
//        gatt?.setCharacteristicNotification(readChar, true)
//        readChar?.let { characteristic ->
//            gatt?.setCharacteristicNotification(characteristic, true)
//
//            gatt?.readCharacteristic(characteristic)
//            // 回调数据通过 BluetoothGattCallback 中的 onCharacteristicChanged 来处理
//        }

        val characteristic = readChar ?: return
        val gatt = gatt ?: return

        // 1. 本地开启通知
        gatt.setCharacteristicNotification(characteristic, true)

        // 2. 写CCCD
        val descriptor = characteristic.getDescriptor(
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        )

        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        gatt.writeDescriptor(descriptor)

        this.onDataReceived = onDataReceived
    }
}