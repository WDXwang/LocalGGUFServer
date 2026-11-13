package com.workbuddy.localmodel.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
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
    var mlockOn by rememberSaveable { mutableStateOf(settings.useMlock) }
    var importing by remember { mutableStateOf(false) }
    var importProgress by remember { mutableFloatStateOf(0f) }
    var message by remember { mutableStateOf<String?>(null) }

    // 提示词预设相关状态，与 SettingsStore 双向同步
    var presetName by rememberSaveable { mutableStateOf(settings.presetName) }
    var systemText by rememberSaveable { mutableStateOf(settings.systemPrompt) }
    var tempText by rememberSaveable { mutableStateOf(settings.temperature.toString()) }
    var topPText by rememberSaveable { mutableStateOf(settings.topP.toString()) }
    var repeatText by rememberSaveable { mutableStateOf(settings.repeatPenalty.toString()) }
    var presetMenuOpen by remember { mutableStateOf(false) }

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

    // 预设组按「选中的模型」取，未选中时回落到当前已加载的模型
    val presetModelName: String? = selectedModel?.name ?: LlamaEngine.currentInfo?.name
    val presets: List<PromptPreset> = PromptPresets.forModel(presetModelName)

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
                        // GPU 回显：这一行是判断 Vulkan 到底有没有生效的唯一依据。
                        // 只有 .so 里真的注册了 Vulkan 后端，设备列表里才会出现 GPU 设备
                        val gpu = state.info
                        Text(
                            when {
                                !gpu.gpuOffloadSupported ->
                                    "GPU：本版未编译 GPU 后端，当前纯 CPU 推理（多线程 + i8mm）"
                                gpu.gpuActive ->
                                    "GPU：已卸载 ${gpu.gpuLayersEffective}/${gpu.layerCount} 层" +
                                        "（请求 ${gpu.gpuLayersRequested}）· ${gpu.gpuDevices}"
                                else ->
                                    "GPU：可用（${gpu.gpuDevices}），当前 GPU 层数为 0，未启用 GPU"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (gpu.gpuActive) Color(0xFF2E7D32) else MaterialTheme.colorScheme.secondary
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
                                contextSize = contextText.toIntOrNull()?.coerceIn(512, 131072) ?: 8192,
                                threads = threadText.toIntOrNull()?.coerceIn(1, 32) ?: settings.threads,
                                batchSize = batchText.toIntOrNull()?.coerceIn(32, 2048) ?: 256,
                                gpuLayers = gpuText.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                                useMlock = mlockOn
                            )
                            settings.contextSize = config.contextSize
                            settings.threads = config.threads
                            settings.batchSize = config.batchSize
                            settings.gpuLayers = config.gpuLayers
                            settings.useMlock = config.useMlock
                            scope.launch {
                                message = null
                                // 记录加载前的模型，用于判断这次是"换模型"还是"重载同一个"
                                val previousPath = LlamaEngine.currentInfo?.path
                                LlamaEngine.load(model.file, config)
                                    .onSuccess { loaded ->
                                        settings.lastModelPath = model.file.absolutePath
                                        // 记下模型层数，供「自动推荐」在未加载状态下给出全量卸载值
                                        if (loaded.layerCount > 0) {
                                            settings.lastLayerCount = loaded.layerCount
                                        }
                                        var note = "已加载 ${loaded.name}"
                                        if (previousPath != null && previousPath != loaded.path) {
                                            // 换了模型：旧对话对新模型没有意义，直接重置
                                            ChatStore.resetAfterModelSwitch(loaded.name)
                                            note = "模型已切换，对话已重置"
                                        }
                                        // 第一次为这个模型家族加载时，自动套用该家族的第一条预设
                                        val family = PromptPresets.forModel(loaded.name)
                                        if (family.isNotEmpty() && presetName != PromptPresets.CUSTOM_NAME &&
                                            family.none { it.name == presetName }
                                        ) {
                                            val p = family.first()
                                            presetName = p.name
                                            systemText = p.system
                                            tempText = p.temperature.toString()
                                            topPText = p.topP.toString()
                                            repeatText = p.repeatPenalty.toString()
                                            settings.presetName = p.name
                                            settings.systemPrompt = p.system
                                            settings.temperature = p.temperature
                                            settings.topP = p.topP
                                            settings.repeatPenalty = p.repeatPenalty
                                            note += "，已套用预设「${p.name}」"
                                        }
                                        message = note
                                        // 预热：加载后立刻跑一次 1 token 的空推理，
                                        // 把 Vulkan 首轮的 pipeline 编译 / 建图开销提前吃掉，
                                        // 用户感知到的第一句话就不会慢得离谱
                                        val warm = LlamaEngine.warmup()
                                        warm.onSuccess { cost ->
                                            message = "$note，已预热 ${cost}ms"
                                        }.onFailure { error ->
                                            message = "$note（预热失败：${error.message ?: "未知错误"}）"
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
                    TextButton(
                        onClick = {
                            // 实测结论：Mali-G720 上全量卸载最快，部分卸载反而因为算子反复在
                            // GPU/CPU 之间切分（splits 从 19 涨到 275）而变慢，所以推荐值直接取模型层数。
                            val info = LlamaEngine.currentInfo
                            val supported = info?.gpuOffloadSupported ?: true
                            val loadedLayers = info?.layerCount ?: 0
                            val layers = when {
                                !supported -> 0
                                loadedLayers > 0 -> loadedLayers
                                settings.lastLayerCount > 0 -> settings.lastLayerCount
                                else -> 32
                            }
                            gpuText = layers.toString()
                            settings.gpuLayers = layers
                            message = if (layers == 0) {
                                "本版未编译 GPU 后端，保持纯 CPU 推理"
                            } else {
                                "已填入推荐层数 $layers（全量卸载），加载后可用 t/s 验证"
                            }
                        }
                    ) {
                        Text("自动推荐")
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("锁定权重内存 (mlock)", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "长时间推理时防止权重页被系统回收导致中途退出；内存不足会自动回退",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    Switch(
                        checked = mlockOn,
                        onCheckedChange = {
                            mlockOn = it
                            settings.useMlock = it
                        }
                    )
                }
                Text(
                    "提示：上下文越大越占内存，Batch 越大 prefill 越快但内存峰值和发热也越高。" +
                        "默认上下文 8192（4096 时自动裁剪只留约 3700 token，一轮就把历史丢光、每轮全量重算）；" +
                        "GPU 层数建议直接点「自动推荐」用全量卸载——真机实测（Qwen3.5-4B / Mali-G720）" +
                        "0 层 2.7 t/s、16 层 3.0、24 层 3.5、全量 32 层 6.0 t/s，prefill 也从 9.4s 降到 2.3s。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "提示词预设",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        PromptPresets.familyLabel(presetModelName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box {
                        OutlinedButton(onClick = { presetMenuOpen = true }) {
                            Text(presetName.ifBlank { "选择预设" })
                        }
                        DropdownMenu(
                            expanded = presetMenuOpen,
                            onDismissRequest = { presetMenuOpen = false }
                        ) {
                            presets.forEach { p ->
                                DropdownMenuItem(
                                    text = { Text(p.name) },
                                    onClick = {
                                        presetMenuOpen = false
                                        presetName = p.name
                                        systemText = p.system
                                        tempText = p.temperature.toString()
                                        topPText = p.topP.toString()
                                        repeatText = p.repeatPenalty.toString()
                                        settings.presetName = p.name
                                        settings.systemPrompt = p.system
                                        settings.temperature = p.temperature
                                        settings.topP = p.topP
                                        settings.repeatPenalty = p.repeatPenalty
                                        message = "已套用预设「${p.name}」"
                                    }
                                )
                            }
                            if (presets.isNotEmpty()) HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(PromptPresets.CUSTOM_NAME) },
                                onClick = {
                                    presetMenuOpen = false
                                    presetName = PromptPresets.CUSTOM_NAME
                                    settings.presetName = PromptPresets.CUSTOM_NAME
                                    message = "已切换到自定义，可手动编辑提示词与参数"
                                }
                            )
                        }
                    }
                    Text(
                        "共 ${presets.size} 条预设",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                if (presets.isEmpty()) {
                    Text(
                        "未识别该模型家族，暂无预设；可直接用下方输入框写自己的系统提示词。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                OutlinedTextField(
                    value = systemText,
                    onValueChange = { v ->
                        systemText = v
                        settings.systemPrompt = v
                        // 手动改过提示词就不再声称是某条预设
                        if (presetName.isNotBlank() && presetName != PromptPresets.CUSTOM_NAME) {
                            presetName = PromptPresets.CUSTOM_NAME
                            settings.presetName = PromptPresets.CUSTOM_NAME
                        }
                    },
                    label = { Text("系统提示词") },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = tempText,
                        onValueChange = { v ->
                            val text = v.filter { ch -> ch.isDigit() || ch == '.' }
                            tempText = text
                            text.toFloatOrNull()?.let { if (it in 0f..2f) settings.temperature = it }
                        },
                        label = { Text("温度") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = topPText,
                        onValueChange = { v ->
                            val text = v.filter { ch -> ch.isDigit() || ch == '.' }
                            topPText = text
                            text.toFloatOrNull()?.let { if (it in 0.05f..1f) settings.topP = it }
                        },
                        label = { Text("top_p") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = repeatText,
                    onValueChange = { v ->
                        val text = v.filter { ch -> ch.isDigit() || ch == '.' }
                        repeatText = text
                        text.toFloatOrNull()?.let { if (it in 0.5f..2f) settings.repeatPenalty = it }
                    },
                    label = { Text("重复惩罚") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "说明：选中预设会同时写入系统提示词与温度 / top_p / 重复惩罚三项参数；" +
                            "手动修改提示词会自动切换到「自定义」，参数仍可继续微调。",
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
