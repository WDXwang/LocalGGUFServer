package com.workbuddy.localmodel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.workbuddy.localmodel.LocalModelApp
import com.workbuddy.localmodel.llm.ChatMessage
import com.workbuddy.localmodel.llm.EngineState
import com.workbuddy.localmodel.llm.GenParams
import com.workbuddy.localmodel.llm.LlamaEngine

@Composable
fun ChatScreen(modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as LocalModelApp
    val engineState by LlamaEngine.state.collectAsStateWithLifecycle()
    val messages by ChatStore.messages.collectAsStateWithLifecycle()
    val streaming by ChatStore.streaming.collectAsStateWithLifecycle()
    val generating by ChatStore.generating.collectAsStateWithLifecycle()
    val error by ChatStore.error.collectAsStateWithLifecycle()

    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    val ready = engineState is EngineState.Ready
    val itemCount = messages.size + if (generating) 1 else 0

    LaunchedEffect(itemCount, streaming.length) {
        if (itemCount > 0) {
            // 流式输出时每个 chunk 都会触发本 effect，用 scrollToItem 直接定位，
            // 避免 animateScrollToItem 被反复打断导致列表抖动
            listState.scrollToItem(itemCount - 1)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (!ready) {
            Text(
                "尚未加载模型，请先到「模型」页导入并加载 GGUF 文件。",
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(messages) { _, message ->
                MessageBubble(message)
            }
            if (generating) {
                item {
                    MessageBubble(
                        ChatMessage("assistant", streaming.ifEmpty { "正在思考..." }),
                        streaming = true
                    )
                }
            }
        }

        error?.let { text ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                TextButton(onClick = { ChatStore.clearError() }) {
                    Text("关闭")
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入消息，使用本地模型回答") },
                maxLines = 4
            )
            if (generating) {
                OutlinedButton(onClick = { ChatStore.cancel() }) {
                    Text("停止")
                }
            } else {
                Button(
                    onClick = {
                        val text = input
                        input = ""
                        ChatStore.send(
                            text,
                            GenParams(
                                maxTokens = app.settings.maxTokens,
                                temperature = app.settings.temperature
                            )
                        )
                    },
                    enabled = ready && input.isNotBlank()
                ) {
                    Text("发送")
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "上下文 ${messages.size} 条消息",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { ChatStore.clear() }, enabled = !generating) {
                Text("清空对话")
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, streaming: Boolean = false) {
    val isUser = message.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(Modifier.padding(10.dp)) {
                Text(
                    if (isUser) "你" else "助手",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                Text(
                    message.content.ifEmpty { if (streaming) "..." else "" },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
