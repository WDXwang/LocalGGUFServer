package com.workbuddy.localmodel.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.workbuddy.localmodel.LocalModelApp
import com.workbuddy.localmodel.llm.EngineState
import com.workbuddy.localmodel.llm.LlamaEngine
import com.workbuddy.localmodel.llm.LoadConfig
import com.workbuddy.localmodel.model.ModelFile
import com.workbuddy.localmodel.util.NetworkUtils
import kotlinx.coroutines.launch

@Composable
fun ModelsScreen(modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as LocalModelApp
    val repository = app.modelRepository
    val settings = app.settings
    val scope = rememberCoroutineScope()

    val models by repository.models.collectAsStateWithLifecycle()
    val engineState by LlamaEngine.state.collectAsStateWithLifecycle()

    var selectedPath by rememberSaveable { mutableStateOf(settings.lastModelPath.orEmpty()) }
    var contextText by rememberSaveable { mutableStateOf(settings.contextSize.toString()) }
    var threadText by rememberSaveable { mutableStateOf(settings.threads.toString()) }
    var batchText by rememberSaveable { mutableStateOf(settings.batchSize.toString()) }
    var gpuText by rememberSaveable { mutableStateOf(settings.gpuLayers.toString()) }
    var importing by remember { mutableStateOf(false) }
    var importProgress by remember { mutableFloatStateOf(0f) }
    var message by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importing = true
            importProgress = 0f
            message = null
            val result = repository.import(uri) { importProgress = it }
            importing = false
            result.onSuccess { file ->
                selectedPath = file.absolutePath
                message = "已导入 ${file.name}"
            }
            result.onFailure { error ->
                message = "导入失败：${error.message ?: error.javaClass.simpleName}"
            }
        }
    }

    val selectedModel: ModelFile? = models.firstOrNull { it.file.absolutePath == selectedPath }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("推理引擎", style = MaterialTheme.typography.titleMedium)
                when (val state = engineState) {
                    is EngineState.Idle -> Text("未加载模型", style = MaterialTheme.typography.bodyMedium)
                    is EngineState.Loading -> {
                        Text("正在加载 ${state.name} ...", style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }

                    is EngineState.Ready -> {
                        Text(
                            NetworkUtils.modelTitle(state.info),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "参数量 ${NetworkUtils.formatParams(state.info.parameters)} · " +
                                    "文件 ${NetworkUtils.formatBytes(state.info.sizeBytes)} · " +
                                    "上下文 ${state.info.contextSize}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            if (state.info.hasChatTemplate) {
                                "已识别模型内置对话模板"
                            } else {
                                "未识别对话模板，将使用 ChatML 回退格式"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }

                    is EngineState.Failed -> Text(
                        state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { picker.launch(arrayOf("*/*")) },
                        enabled = !importing
                    ) {
                        Text("导入 GGUF")
                    }
                    Button(
                        onClick = {
                            val model = selectedModel
                            if (model == null) {
                                message = "请先导入并选择一个 GGUF 模型"
                                return@Button
                            }
                            val config = LoadConfig(
                                contextSize = contextText.toIntOrNull()?.coerceIn(512, 131072) ?: 4096,
                                threads = threadText.toIntOrNull()?.coerceIn(1, 32) ?: settings.threads,
                                batchSize = batchText.toIntOrNull()?.coerceIn(32, 2048) ?: 512,
                                gpuLayers = gpuText.toIntOrNull()?.coerceAtLeast(0) ?: 0
                            )
                            settings.contextSize = config.contextSize
                            settings.threads = config.threads
                            settings.batchSize = config.batchSize
                            settings.gpuLayers = config.gpuLayers
                            scope.launch {
                                message = null
                                // 记录加载前的模型，用于判断这次是"换模型"还是"重载同一个"
                                val previousPath = LlamaEngine.currentInfo?.path
                                LlamaEngine.load(model.file, config)
                                    .onSuccess { loaded ->
                                        settings.lastModelPath = model.file.absolutePath
                                        if (previousPath != null && previousPath != loaded.path) {
                                            // 换了模型：旧对话对新模型没有意义，直接重置
                                            ChatStore.resetAfterModelSwitch(loaded.name)
                                            message = "模型已切换，对话已重置"
                                        } else {
                                            message = "已加载 ${loaded.name}"
                                        }
                                    }
                                    .onFailure { message = it.message }
                            }
                        },
                        enabled = engineState !is EngineState.Loading && selectedModel != null
                    ) {
                        Text("加载模型")
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                LlamaEngine.unload()
                                message = "模型已卸载"
                            }
                        },
                        enabled = engineState is EngineState.Ready
                    ) {
                        Text("卸载")
                    }
                }

                if (importing) {
                    LinearProgressIndicator(
                        progress = { importProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "正在复制模型文件 ${(importProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                message?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("加载参数", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = contextText,
                        onValueChange = { contextText = it.filter(Char::isDigit) },
                        label = { Text("上下文") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = threadText,
                        onValueChange = { threadText = it.filter(Char::isDigit) },
                        label = { Text("线程") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = batchText,
                        onValueChange = { batchText = it.filter(Char::isDigit) },
                        label = { Text("Batch") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = gpuText,
                        onValueChange = { gpuText = it.filter(Char::isDigit) },
                        label = { Text("GPU 层数") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    "提示：上下文越大越占内存。当前构建仅启用 CPU 推理，GPU 层数可保持 0。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("模型文件", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text("${models.size} 个", style = MaterialTheme.typography.bodySmall)
                }
                if (models.isEmpty()) {
                    Text(
                        "还没有模型。点击「导入 GGUF」从文件管理器选择 .gguf 文件，文件会复制到应用私有目录。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                models.forEachIndexed { index, model ->
                    if (index > 0) HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = model.file.absolutePath == selectedPath,
                            onClick = { selectedPath = model.file.absolutePath }
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                model.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                NetworkUtils.formatBytes(model.sizeBytes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                        TextButton(
                            onClick = {
                                scope.launch {
                                    if (LlamaEngine.currentInfo?.path == model.file.absolutePath) {
                                        LlamaEngine.unload()
                                    }
                                    repository.delete(model.file)
                                    if (selectedPath == model.file.absolutePath) {
                                        selectedPath = ""
                                    }
                                }
                            }
                        ) {
                            Text("删除")
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "模型目录：${repository.modelsDir.absolutePath}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}
