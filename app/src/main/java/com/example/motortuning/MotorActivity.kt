package com.example.motortuning

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun MotorControlScreen(
    viewModel: MotorViewModel,
    modifier: Modifier = Modifier
) {
    MotorControlContent(
        viewModel = viewModel,
        modifier = modifier
    )
}

@Composable
fun MotorControlContent(
    viewModel: MotorViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val uiParams by viewModel.params.collectAsState()
    val initParamKeys by viewModel.paramDef.collectAsState()
    val realtimeValues by viewModel.realtimeValues.collectAsState()
    val onIntent = viewModel::handleIntent
    var showSettings by remember { mutableStateOf(false) }
    var showCanIdDialog by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("电机控制", style = MaterialTheme.typography.titleLarge)

            // CAN 状态指示灯 + 文字
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .background(
                            color = if (uiState.connect) Color.Green else Color.Red,
                            shape = androidx.compose.foundation.shape.CircleShape
                        )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (uiState.connect) "Motor CAN Connected" else "Motor CAN Disconnected",
                    color = if (uiState.connect) Color.Green else Color.Red,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // CAN ID 下拉框和更新按钮
        val canIdList by viewModel.canIdList.collectAsState()
        var selectedCanId by remember { mutableStateOf<Int?>(null) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("CAN ID:", style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                var expanded by remember { mutableStateOf(false) }
                Box(modifier = Modifier.width(100.dp)) {
                    TextButton(
                        onClick = { expanded = true }
                    ) {
                        Text(selectedCanId?.toString() ?: "选择 CAN ID")
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        canIdList.forEach { canId ->
                            DropdownMenuItem(
                                text = { Text(canId.toString()) },
                                onClick = {
                                    selectedCanId = canId
                                    viewModel.initCanId(canId)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { viewModel.updateCanIdList() }
                ) {
                    Text("更新")
                }
            }
        }

        MotorAnglePreview(angle = uiState.previewAngle)

        // 只读参数显示
        val displayParams by viewModel.displayParams.collectAsState()
        MotorInfoCard(
            params = displayParams,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            MotorContinuousButton(
                text = "◀ 反转",
                onStep = { onIntent(MotorIntent.Step(selectedCanId, -1)) },
                onLongPress = {
                    // 长按时，计算旋转角度并累计
                    onIntent(MotorIntent.StartContinuous(selectedCanId, -1))
                },
                onLongPressEnd = { onIntent(MotorIntent.StopContinuous(selectedCanId)) }
            )

            MotorContinuousButton(
                text = "正转 ▶",
                onStep = { onIntent(MotorIntent.Step(selectedCanId, 1)) },
                onLongPress = {
                    // 长按时，计算旋转角度并累计
                    onIntent(MotorIntent.StartContinuous(selectedCanId, 1))
                },
                onLongPressEnd = { onIntent(MotorIntent.StopContinuous(selectedCanId)) }
            )
        }

        // 新增“打开设置”按钮
        Button(
            onClick = { showSettings = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("设置参数")
        }

        // 新增“设置can_id”按钮
        Button(
            onClick = { showCanIdDialog = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("设置can_id")
        }

        // 新增“设置当前位置为零点”按钮
        Button(
            onClick = {
                val current_canId = selectedCanId
                viewModel.setZero(current_canId)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("设置当前位置为零点")
        }

        if (showSettings) {
            // 将 uiParams 从 Map<String, Any> 转换为 Map<String, String>
            val stringValues = uiParams.mapValues { it.value.toString() }
            MotorSettingsDialog(
                paramDef = initParamKeys,
                initialValues = stringValues,
                selectedCanId = selectedCanId,
                onSave = { newParams ->
                    // 将 newParams 从 Map<String, Float> 转换为 Map<String, Any>
                    val anyValues = newParams.mapValues { it.value as Any }
                    viewModel.saveParams(anyValues)
                    showSettings = false  // 关闭 Dialog
                },
                onDismiss = { showSettings = false }
            )
        }

        if (showCanIdDialog) {
            var canIdText by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showCanIdDialog = false },
                title = { Text("设置 CAN ID") },
                text = {
                    Column {
                        Text("请输入 CAN ID (1-4):")
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = canIdText,
                            onValueChange = { canIdText = it },
                            label = { Text("CAN ID") },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number
                            )
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val canId = canIdText.toIntOrNull()
                            val current_canId = selectedCanId
                            if (canId != null && canId in 1..4) {
                                viewModel.setCanId(canId, current_canId)
                                showCanIdDialog = false
                            }
                        }
                    ) {
                        Text("设置")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showCanIdDialog = false
                        }
                    ) {
                        Text("取消")
                    }
                }
            )
        }
    }
}


@Composable
fun MotorContinuousButton(
    text: String,
    onStep: () -> Unit,
    onLongPress: () -> Unit, // 长按时传递累计的角度
    onLongPressEnd: () -> Unit // 长按结束时调用
) {
//    Button(
//        modifier = Modifier
//            .width(140.dp)
//            .height(70.dp)
//            .pointerInput(Unit) {
//                detectTapGestures(
//                    onTap = { onStep() }, // 短按
//                    onPress = { // 长按
//                        val startTime = System.currentTimeMillis()
//                        // 等待松手事件
//                        tryAwaitRelease()
//                        val endTime = System.currentTimeMillis()
//                        val duration = endTime - startTime
//                        // 计算角度：1度/100毫秒
//                        val angle = (duration / 100).toInt()
//                        onLongPress(angle)
//                    }
//                )
//            },
//        onClick = { Log.i("MotorContinuousButton", "点击了 $text") }
//    ) {
//        Text(text)
//    }

    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .width(140.dp)
            .height(70.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        null //onStep()  // 短按事件
                    },
                    onPress = {
                        coroutineScope {
                            val startTime = System.currentTimeMillis()
                            val job = launch {
                                while (true) {
                                    onLongPress()
                                    delay(100)
                                }
                            }

                            tryAwaitRelease()  // 等待手指抬起
                            job.cancel()
                            val duration = System.currentTimeMillis() - startTime
                            job.cancel()
                            if (duration < 300) onStep() // 短按
                            else onLongPressEnd() // 长按抬起
                        }
                    }
                )
            }
            .clip(RoundedCornerShape(16.dp))  // 设置圆角矩形，角度为16dp
            .background(MaterialTheme.colorScheme.primary)
            .indication(
                interactionSource = interactionSource,
                indication = LocalIndication.current
            )
    ) {
        Text(text, modifier = Modifier.align(Alignment.Center))  // 按钮文本居中显示
    }
}

@Composable
fun MotorInfoCard(
    params: List<DisplayParam>,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("电机状态", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            params.forEach {
                Text("${it.name}：${it.value} ${it.unit}")
            }
        }
    }
}

@Composable
fun MotorAnglePreview(
    angle: Int,
    modifier: Modifier = Modifier
) {
    val animatedAngle by animateFloatAsState(
        targetValue = angle.toFloat(),
        label = "angleAnim"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(160.dp),
        contentAlignment = Alignment.Center
    ) {

        Canvas(modifier = Modifier.size(120.dp)) {
            drawArc(
                color = Color(0xFF1976D2),
                startAngle = -90f,
                sweepAngle = animatedAngle * 2f, // 视觉放大
                useCenter = false,
                style = Stroke(width = 12f)
            )
        }

        Text(
            text = "${animatedAngle.toInt()}°",
            style = MaterialTheme.typography.headlineMedium
        )
    }
}

@Composable
fun MotorSettingsDialog(
    paramDef: List<MotorParam>,
    initialValues: Map<String, String>,
    selectedCanId: Int?,
    onDismiss: () -> Unit,
    onSave: (Map<String, Float>) -> Unit
) {
    // 本地可编辑状态（String，便于输入）
    val values = remember {
        mutableStateMapOf<String, String>().apply {
            paramDef.filter { it.editable }.forEach { def ->
                this[def.key] =
                    initialValues[def.key]
                        ?: def.default.toString()
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("电机参数设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

                // 只显示可编辑参数
                paramDef
                    .filter { it.editable }
                    .forEach { def ->

                        OutlinedTextField(
                            value = values[def.key] ?: "",
                            onValueChange = { new ->
                                // 允许空，或者匹配数字+可选小数点
                                if (new.isEmpty() || new.matches(Regex("^-?\\d*\\.?\\d*\$"))) {
                                    values[def.key] = new
                                }
                            },
                            label = { Text(def.name) },
                            suffix = { Text(def.unit) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Decimal
                            )
                        )
                    }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val result = values.mapValues {
                        it.value.toFloatOrNull() ?: 0f
                    } + ("current_can_id" to (selectedCanId?.toFloat() ?: -1f))
                    onSave(result)
                }
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

//@Composable
//fun MotorControlContentPreview(
//    uiState: MotorUiState,
//    displayParams: List<DisplayParam>,
//    onIntent: (MotorIntent) -> Unit,
//    modifier: Modifier = Modifier
//) {
//    var showSettings by remember { mutableStateOf(false) }
//
//    Column(
//        modifier = modifier
//            .fillMaxSize()
//            .padding(16.dp),
//        verticalArrangement = Arrangement.spacedBy(16.dp)
//    ) {
//
//        MotorAnglePreview(angle = uiState.previewAngle)
//
//        MotorInfoCard(
//            params = displayParams,
//            modifier = Modifier.fillMaxWidth()
//        )
//
//        Row(
//            modifier = Modifier.fillMaxWidth(),
//            horizontalArrangement = Arrangement.SpaceEvenly
//        ) {
//            MotorContinuousButton(
//                text = "◀ 反转",
//                onStep = { onIntent(MotorIntent.Step(-1)) },
//                onStart = { onIntent(MotorIntent.StartContinuous(-1)) },
//                onStop = { onIntent(MotorIntent.StopContinuous) }
//            )
//
//            MotorContinuousButton(
//                text = "正转 ▶",
//                onStep = { onIntent(MotorIntent.Step(1)) },
//                onStart = { onIntent(MotorIntent.StartContinuous(1)) },
//                onStop = { onIntent(MotorIntent.StopContinuous) }
//            )
//        }
//
//        if (showSettings) {
//            // 预览中不需要 DataStore，可直接模拟参数
//            MotorSettingsDialog(
//                paramDef = listOf(
//                    MotorParam("min_pos", "最小位置", 0, "°"),
//                    MotorParam("max_pos", "最大位置", 180, "°"),
//                    MotorParam("speed", "转动速度", 50, "%")
//                ),
//                initialValues = mapOf(
//                    "min_pos" to 0,
//                    "max_pos" to 180,
//                    "speed" to 50
//                ),
//                onSave = { showSettings = false },
//                onDismiss = { showSettings = false }
//            )
//        }
//    }
//}
//
//@Preview(showBackground = true)
//@Composable
//fun MotorControlPreview() {
//    // 模拟 UI 状态
//    val fakeUiState = MotorUiState(
//        previewAngle = 45
//    )
//
//    // 模拟参数列表（与 JSON 保持一致）
//    val fakeParams = listOf(
//        DisplayParam("最小位置", "0°"),
//        DisplayParam("最大位置", "180°"),
//        DisplayParam("转动速度", "50%")
//    )
//
//    // 模拟 intent，不做任何动作
//    val fakeOnIntent: (MotorIntent) -> Unit = {}
//
//    MotorTuningTheme {
//        MotorControlContentPreview(
//            uiState = fakeUiState,
//            displayParams = fakeParams,
//            onIntent = fakeOnIntent
//        )
//    }
//}