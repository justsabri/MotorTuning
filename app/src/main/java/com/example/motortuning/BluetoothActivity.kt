package com.example.motortuning

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

//@Composable
//fun BluetoothScreenSelection(
//    classicViewModel: BluetoothViewModel,
//    bleViewModel: BluetoothLeViewModel,
//    navController: NavHostController
//) {
//    val btState by classicViewModel.state.collectAsState()
//    val bleState by bleViewModel.state.collectAsState()
//
//    Column(Modifier.fillMaxSize().padding(16.dp)) {
//
//        Text("经典蓝牙设备", style = MaterialTheme.typography.titleMedium)
//        BluetoothDeviceList(classicViewModel.devices.collectAsState().value) { device ->
//            classicViewModel.connect(device)
//        }
//
//        Spacer(Modifier.height(16.dp))
//
//        Text("BLE 设备", style = MaterialTheme.typography.titleMedium)
//        BluetoothDeviceList(bleViewModel.devices.collectAsState().value) { device ->
//            bleViewModel.connect(device)
//        }
//    }
//
//    // 监听状态跳转页面
//    LaunchedEffect(btState, bleState) {
//        when {
//            btState is BluetoothState.Connected -> navController.navigate("motor") {
//                popUpTo("bluetooth") { inclusive = true }
//            }
//            bleState is BluetoothState.Connected -> navController.navigate("motor_ble") {
//                popUpTo("bluetooth") { inclusive = true }
//            }
//        }
//    }
//}
//
//@SuppressLint("MissingPermission")
//@Composable
//fun BluetoothDeviceList(
//    devices: List<BluetoothDevice>,
//    onClick: (BluetoothDevice) -> Unit
//) {
//    LazyColumn {
//        items(devices) { device ->
//            Text(
//                "${device.name ?: "未知设备"} (${device.address})",
//                modifier = Modifier
//                    .fillMaxWidth()
//                    .clickable { onClick(device) }
//                    .padding(12.dp)
//            )
//        }
//    }
//}

@Composable
fun BluetoothScreen(
    viewModel: DualBluetoothViewModel,
    activity: Activity,
    navController: NavController,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier
) {
    val devices by viewModel.devices.collectAsState()
    val btState by viewModel.state.collectAsState()

    LaunchedEffect(btState) {
        if (btState is BluetoothState.Disconnected) {
            snackbarHostState.showSnackbar("蓝牙连接失败或已断开")
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        BluetoothScanButton(viewModel, activity)

        Spacer(Modifier.height(12.dp))

        DeviceList(
            devices = devices,
            onDeviceClick = { device ->
                viewModel.connect(device) // 自动走对应协议
            }
        )
    }
}

@Composable
fun BluetoothScanButton(
    viewModel: DualBluetoothViewModel,
    activity: Activity
) {
    val context = LocalContext.current

    // 结合系统定位开关和App权限判断
    val locationEnabled = remember { mutableStateOf(isLocationAvailable(context)) }

    // Launcher 打开系统定位设置
    val locationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        // 用户返回后重新检查 GPS 和权限
        locationEnabled.value = isLocationAvailable(context)
        if (locationEnabled.value) {
            viewModel.scanDevicesSafe()
        } else {
            Toast.makeText(context, "请开启定位并授权位置权限以扫描蓝牙设备", Toast.LENGTH_SHORT).show()
        }
    }

    // Permissions launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.all { it }
        locationEnabled.value = isLocationAvailable(context)
        if (granted && locationEnabled.value) {
            viewModel.scanDevicesSafe()
        } else {
            Toast.makeText(context, "请开启定位并授权位置权限以扫描蓝牙设备", Toast.LENGTH_SHORT).show()
        }
    }

    Button(onClick = {
        val isLocationEnabled = isLocationEnabled(context)
        Log.d("BluetoothScreen", "isLocationEnabled=$isLocationEnabled")
        when {
            !hasBluetoothPermission(context) -> {
                // 请求蓝牙相关权限
                permissionLauncher.launch(bluetoothPermissions())
            }
            !isLocationAvailable(context) -> {
                // 如果系统位置未开启或权限没给，先请求权限
                if (!hasLocationPermission(context)) {
                    permissionLauncher.launch(arrayOf(
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION
                    ))
                } else {
                    // 权限有了，但系统位置没开，跳转设置

                    activity.let {
                        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                        locationLauncher.launch(intent)
                    }
                }
            }
            else -> {
                // 权限和位置都OK，直接扫描
                viewModel.scanDevicesSafe()
            }
        }
    }) {
        Text("扫描设备")
    }
}

@Composable
fun DeviceList(
    devices: List<BtDevice>,
    onDeviceClick: (BtDevice) -> Unit
) {
    LazyColumn {
        items(devices) { device ->
            DeviceItem(
                device = device,
                onClick = { onDeviceClick(device) }
            )
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun DeviceItem(
    device: BtDevice,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {

        ProtocolDot(device)

        Spacer(Modifier.width(8.dp))

        Column {
            Text(
                text = device.name ?: "未知设备",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = device.address,
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
        }
    }
}

@Composable
fun ProtocolDot(device: BtDevice) {
    val color = when (device) {
        is BtDevice.Ble -> Color(0xFF1976D2)   // 蓝色
        is BtDevice.Classic -> Color.Gray
    }

    Box(
        modifier = Modifier
            .size(10.dp)
            .background(color, CircleShape)
    )
}