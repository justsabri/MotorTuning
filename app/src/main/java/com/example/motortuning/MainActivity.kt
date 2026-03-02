package com.example.motortuning

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.motortuning.ui.theme.MotorTuningTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MotorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d("MainActivity", "MainActivity is created!")  // 确认Activity是否启动

        setContent {
            MotorTuningTheme {
                val navController = rememberNavController()
                val context = LocalContext.current

                val btViewModel: DualBluetoothViewModel = viewModel(
                    factory = DualBluetoothViewModelFactory(context)
                )

                val motorViewModel: MotorViewModel by viewModels {
                    MotorViewModelFactory(
                        application = application,
                        comm = btViewModel   // DualBluetoothViewModel 实现了 MotorComm
                    )
                }

                // SnackbarHostState 放在 Scaffold 层
                val snackbarHostState = remember { SnackbarHostState() }

                Scaffold(
                    snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = "bluetooth",
                        modifier = Modifier.padding(innerPadding)
                    ) {
                        composable("bluetooth") {
                            BluetoothScreen(
                                viewModel = btViewModel,
                                activity = this@MainActivity,
                                navController = navController,       // 传递 NavController，如果需要
                                snackbarHostState = snackbarHostState // 传递 SnackbarHostState
                            )
                        }
                        composable("motor") {
                            MotorControlScreen(
                                viewModel = motorViewModel,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }

                // 保留全局导航逻辑
                val btState by btViewModel.state.collectAsState()
                LaunchedEffect(btState) {
                    when (btState) {
                        is BluetoothState.Connected -> {
                            if (navController.currentDestination?.route != "motor") {
                                navController.navigate("motor") {
                                    popUpTo("bluetooth") { inclusive = true }
                                }
                            }
                        }

                        BluetoothState.Disconnected -> {
                            if (navController.currentDestination?.route != "bluetooth") {
                                navController.navigate("bluetooth") {
                                    popUpTo("motor") { inclusive = true }
                                }
                            }
                        }

                        else -> Unit
                    }
                }
            }
        }
    }
}


