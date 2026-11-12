package com.workbuddy.localmodel.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.workbuddy.localmodel.LocalModelApp
import com.workbuddy.localmodel.llm.EngineState
import com.workbuddy.localmodel.llm.LlamaEngine
import com.workbuddy.localmodel.server.ServerController
import com.workbuddy.localmodel.service.ApiServerService
import com.workbuddy.localmodel.util.NetworkUtils

@Composable
fun ServerScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as LocalModelApp
    val settings = app.settings
    val clipboard = LocalClipboardManager.current

    val running by ServerController.running.collectAsStateWithLifecycle()
    val serverError by ServerController.error.collectAsStateWithLifecycle()
    val engineState by LlamaEngine.state.collectAsStateWithLifecycle()

    var portText by rememberSaveable { mutableStateOf(settings.port.toString()) }
    var apiKeyText by rememberSaveable { mutableStateOf(settings.apiKey) }
    var addresses by remember { mutableStateOf(NetworkUtils.localIpv4Addresses()) }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 用户拒绝也不影响前台服务运行，只是不显示通知 */ }

    LaunchedEffect(Unit) {
        addresses = NetworkUtils.localIpv4Addresses()
    }

    val port = portText.toIntOrNull()?.takeIf { it in 1024..65535 } ?: settings.port
    val baseUrl = "http://${addresses.firstOrNull() ?: "<手机IP>"}:$port/v1"

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("HTTP 服务", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (running) "运行中 · 端口 $port" else "已停止",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                )
                if (engineState !is EngineState.Ready) {
                    Text(
                        "当前没有已加载的模型，接口会返回 503，请先到「模型」页加载模型。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                        label = { Text("端口") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        enabled = !running
                    )
                    OutlinedTextField(
                        value = apiKeyText,
                        onValueChange = { apiKeyText = it },
                        label = { Text("API Key") },
                        singleLine = true,
                        modifier = Modifier.weight(1.4f),
                        enabled = !running
                    )
                }
                Text(
                    "API Key 留空表示不鉴权；设置后客户端需带 Authorization: Bearer <key>。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (running) {
                        Button(onClick = {
                            ApiServerService.stop(context)
                        }) {
                            Text("停止服务")
                        }
                    } else {
                        Button(onClick = {
                            settings.port = port
                            settings.apiKey = apiKeyText
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.POST_NOTIFICATIONS
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            ApiServerService.start(
                                context,
                                port,
                                apiKeyText,
                                settings.maxTokens,
                                settings.temperature
                            )
                        }) {
                            Text("启动服务")
                        }
                    }
                    OutlinedButton(onClick = { addresses = NetworkUtils.localIpv4Addresses() }) {
                        Text("刷新 IP")
                    }
                }

                serverError?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("局域网地址", style = MaterialTheme.typography.titleMedium)
                if (addresses.isEmpty()) {
                    Text(
                        "未检测到局域网 IPv4 地址，请确认已连接 Wi-Fi 或热点。",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    addresses.forEach { ip ->
                        Text(
                            "http://$ip:$port/v1",
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(baseUrl))
                }) {
                    Text("复制 Base URL")
                }
                Text(
                    "PC 与手机需处于同一局域网。若 PC 无法连接，请检查手机热点/路由器的客户端隔离设置与系统防火墙。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("WorkBuddy / OpenAI SDK 配置", style = MaterialTheme.typography.titleMedium)
                Text("Base URL：$baseUrl", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                Text(
                    "API Key：${if (apiKeyText.isBlank()) "（留空）" else apiKeyText}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    "Model：${LlamaEngine.modelId}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                HorizontalDivider()
                Text("支持的接口", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "GET  /health\n" +
                            "GET  /v1/models\n" +
                            "POST /v1/chat/completions（支持 stream=true）\n" +
                            "POST /v1/completions（支持 stream=true）",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                HorizontalDivider()
                Text("curl 示例", style = MaterialTheme.typography.bodyMedium)
                val curl = buildString {
                    append("curl $baseUrl/chat/completions \\\n")
                    append("  -H \"Content-Type: application/json\" \\\n")
                    if (apiKeyText.isNotBlank()) {
                        append("  -H \"Authorization: Bearer $apiKeyText\" \\\n")
                    }
                    append("  -d '{\"model\":\"${LlamaEngine.modelId}\",\"messages\":[{\"role\":\"user\",\"content\":\"你好\"}],\"stream\":false}'")
                }
                Text(
                    curl,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                TextButton(onClick = { clipboard.setText(AnnotatedString(curl)) }) {
                    Text("复制 curl 命令")
                }
            }
        }
    }
}
