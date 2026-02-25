package com.example.motortuning

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavHostController

@Composable
fun BluetoothScreenSelection(
    classicViewModel: BluetoothViewModel,
    bleViewModel: BluetoothLeViewModel,
    navController: NavHostController
) {
    val btState by classicViewModel.state.collectAsState()
    val bleState by bleViewModel.state.collectAsState()

    Column(Modifier.fillMaxSize().padding(16.dp)) {

        Text("经典蓝牙设备", style = MaterialTheme.typography.titleMedium)
        BluetoothDeviceList(classicViewModel.devices.collectAsState().value) { device ->
            classicViewModel.connect(device)
        }

        Spacer(Modifier.height(16.dp))

        Text("BLE 设备", style = MaterialTheme.typography.titleMedium)
        BluetoothDeviceList(bleViewModel.devices.collectAsState().value) { device ->
            bleViewModel.connect(device)
        }
    }

    // 监听状态跳转页面
    LaunchedEffect(btState, bleState) {
        when {
            btState is BluetoothState.Connected -> navController.navigate("motor") {
                popUpTo("bluetooth") { inclusive = true }
            }
            bleState is BluetoothState.Connected -> navController.navigate("motor_ble") {
                popUpTo("bluetooth") { inclusive = true }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun BluetoothDeviceList(
    devices: List<BluetoothDevice>,
    onClick: (BluetoothDevice) -> Unit
) {
    LazyColumn {
        items(devices) { device ->
            Text(
                "${device.name ?: "未知设备"} (${device.address})",
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onClick(device) }
                    .padding(12.dp)
            )
        }
    }
}

@Composable
fun BluetoothScreen(
    viewModel: BluetoothViewModel,
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
        BluetoothScanButton(viewModel)

        Spacer(Modifier.height(12.dp))

        DeviceList(
            devices = devices,
            onDeviceClick = { device ->
                viewModel.connect(device)
            }
        )
    }
}

@Composable
fun BluetoothScanButton(viewModel: BluetoothViewModel) {
    val context = LocalContext.current
    val activity = context as Activity

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.all { it }
        if (granted) {
            viewModel.scanDevicesSafe()
        }
    }

    Button(onClick = {
        if (hasBluetoothPermission(context)) {
            viewModel.scanDevicesSafe()
        } else {
            permissionLauncher.launch(bluetoothPermissions())
        }
    }) {
        Text("扫描设备")
    }
}

@Composable
fun DeviceList(
    devices: List<BluetoothDevice>,
    onDeviceClick: (BluetoothDevice) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth()
    ) {
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
    device: BluetoothDevice,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
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