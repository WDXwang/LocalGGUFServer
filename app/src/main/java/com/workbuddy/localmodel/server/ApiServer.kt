package com.workbuddy.localmodel.server

import com.workbuddy.localmodel.llm.ChatMessage
import com.workbuddy.localmodel.llm.EngineBusyException
import com.workbuddy.localmodel.llm.EngineThermalException
import com.workbuddy.localmodel.llm.GenParams
import com.workbuddy.localmodel.llm.GenResult
import com.workbuddy.localmodel.llm.LlamaEngine
import com.workbuddy.localmodel.util.ThermalMonitor
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.InputStream
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue

/**
 * OpenAI 兼容的本地推理 HTTP 服务。
 *
 * 支持的接口：
 *  - GET  /health
 *  - GET  /v1/models
 *  - POST /v1/chat/completions （支持 SSE 流式）
 *  - POST /v1/completions      （支持 SSE 流式）
 */
class ApiServer(
    port: Int,
    private val apiKey: String,
    private val defaultMaxTokens: Int = 512,
    private val defaultTemperature: Float = 0.7f
) : NanoHTTPD(port) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun serve(session: IHTTPSession): Response {
        if (session.method == Method.OPTIONS) {
            return withCors(NanoHTTPD.newFixedLengthResponse(Response.Status.NO_CONTENT, NanoHTTPD.MIME_PLAINTEXT, ""))
        }

        val path = normalizePath(session.uri)
        if (path == "/health") {
            return withCors(handleHealth())
        }

        if (!isAuthorized(session)) {
            return withCors(
                json(
                    Response.Status.UNAUTHORIZED,
                    errorBody("无效的 API Key，请在请求头携带 Authorization: Bearer <key>", "authentication_error")
                )
            )
        }

        return try {
            when {
                path == "/" -> withCors(handleRoot())
                path == "/v1/models" && session.method == Method.GET -> withCors(handleModels())
                path == "/v1/chat/completions" && session.method == Method.POST ->
                    withCors(handleChatCompletions(session))
                path == "/v1/completions" && session.method == Method.POST ->
                    withCors(handleCompletions(session))
                else -> withCors(
                    json(Response.Status.NOT_FOUND, errorBody("接口不存在: $path", "invalid_request_error"))
                )
            }
        } catch (e: JSONException) {
            withCors(
                json(
                    Response.Status.BAD_REQUEST,
                    errorBody("请求体不是合法的 JSON：${e.message}", "invalid_request_error")
                )
            )
        } catch (e: IllegalArgumentException) {
            withCors(
                json(
                    Response.Status.BAD_REQUEST,
                    errorBody(e.message ?: "请求不合法", "invalid_request_error")
                )
            )
        } catch (t: Throwable) {
            withCors(
                json(
                    Response.Status.INTERNAL_ERROR,
                    errorBody(t.message ?: "服务内部错误", "server_error")
                )
            )
        }
    }

    override fun stop() {
        scope.cancel()
        super.stop()
    }

    // ---------------- 端点实现 ----------------

    private fun handleHealth(): Response {
        val info = LlamaEngine.currentInfo
        val stats = LlamaEngine.lastStats
        val body = JSONObject().apply {
            put("status", "ok")
            put("loaded", LlamaEngine.isReady)
            put("model", LlamaEngine.modelId)
            put("object", "local-model.health")
            put("busy", LlamaEngine.isBusy)
            put("thermal_level", ThermalMonitor.status.value)
            put("context_size", info?.contextSize ?: 0)
            put("context_limit", if (info != null) contextLimit(info.contextSize) else 0)
            put("gpu_layers_effective", info?.gpuLayersEffective ?: 0)
            put("gpu_layers_total", info?.layerCount ?: 0)
            put("flash_attn", info?.flashAttn ?: "")
            put("kv_type", info?.kvType ?: "")
            put("threads", info?.decodeThreads ?: 0)
            put("threads_batch", info?.prefillThreads ?: 0)
            put("last", JSONObject().apply {
                put("prompt_tokens", stats.promptTokens)
                put("kv_reuse_tokens", stats.kvReuseTokens)
                put("kv_reuse_ratio", String.format("%.3f", stats.kvReuseRatio))
                put("prefill_ms", stats.prefillMs)
                put("decode_ms", stats.decodeMs)
                put("generated_tokens", stats.generatedTokens)
                put("tokens_per_second",
                    String.format("%.2f", if (stats.decodeMs > 0) stats.generatedTokens * 1000.0 / stats.decodeMs else 0.0))
            })
        }
        return json(Response.Status.OK, body)
    }

    /** /health 上暴露的实际上限口径，和 LlamaEngine.promptLimit 保持一致 */
    private fun contextLimit(ctx: Int): Int = (ctx * 9 / 10 - defaultMaxTokens.coerceIn(0, 1024)).coerceAtLeast(256)

    /**
     * 请求里的 model 字段校验。
     *
     * 旧实现完全忽略 model，客户端传任何名字都当成功，容易让用户以为"换模型了但没生效"。
     * 现在只有不匹配才报 404 model_not_found（OpenAI 的语义），不传则照旧兼容。
     */
    private fun modelNotFound(request: JSONObject): Response? {
        val requested = request.optString("model", "")
        if (requested.isBlank()) return null
        val actual = LlamaEngine.modelId
        if (requested == actual) return null
        return json(
            Response.Status.NOT_FOUND,
            errorBody(
                "模型不存在: $requested（当前加载的是 $actual）",
                "model_not_found"
            )
        )
    }

    private fun busyResponse(): Response =
        withRetryAfter(
            json(
                STATUS_TOO_MANY_REQUESTS,
                errorBody("已有生成任务在进行，请稍后重试（当前模型单并发）", "rate_limit_error")
            ),
            seconds = 3
        )

    /**
     * 引擎错误 → HTTP 状态码。
     *
     * 429 与 503 的语义必须区分开，客户端才能做不同的重试策略：
     *   429 = 忙（马上会好，3 秒后重试）
     *   503 = 热（要冷却，等多久不确定，给 5 秒起步）
     * 旧实现把两者都当 500 INTERNAL_ERROR，客户端只会无脑重试直到驱动超时。
     */
    private fun engineError(error: Throwable): Response = when (error) {
        is EngineThermalException -> withRetryAfter(
            json(
                Response.Status.SERVICE_UNAVAILABLE,
                errorBody(error.message ?: "设备过热，已暂停生成", "thermal_throttled")
            ),
            seconds = 5
        )
        is EngineBusyException -> withRetryAfter(
            json(
                STATUS_TOO_MANY_REQUESTS,
                errorBody(error.message ?: "已有生成任务在进行", "rate_limit_error")
            ),
            seconds = 3
        )
        else -> json(
            Response.Status.INTERNAL_ERROR,
            errorBody(error.message ?: "推理失败", "server_error")
        )
    }

    private fun withRetryAfter(response: Response, seconds: Int): Response {
        response.addHeader("Retry-After", seconds.toString())
        return response
    }

    private fun handleRoot(): Response {
        val body = JSONObject().apply {
            put("service", "Local GGUF Server")
            put("loaded", LlamaEngine.isReady)
            put("model", LlamaEngine.modelId)
            put("endpoints", JSONArray().apply {
                put("/health")
                put("/v1/models")
                put("/v1/chat/completions")
                put("/v1/completions")
            })
        }
        return json(Response.Status.OK, body)
    }

    private fun handleModels(): Response {
        val created = System.currentTimeMillis() / 1000
        val model = JSONObject().apply {
            put("id", LlamaEngine.modelId)
            put("object", "model")
            put("created", created)
            put("owned_by", "local")
        }
        val body = JSONObject().apply {
            put("object", "list")
            put("data", JSONArray().put(model))
        }
        return json(Response.Status.OK, body)
    }

    private fun handleChatCompletions(session: IHTTPSession): Response {
        val request = readJsonBody(session)
        if (!LlamaEngine.isReady) {
            return json(
                Response.Status.SERVICE_UNAVAILABLE,
                errorBody("尚未加载 GGUF 模型，请先在 App 内加载模型", "model_not_loaded")
            )
        }

        val messagesJson = request.optJSONArray("messages")
            ?: return json(
                Response.Status.BAD_REQUEST,
                errorBody("缺少 messages 字段", "invalid_request_error")
            )

        modelNotFound(request)?.let { return it }
        if (LlamaEngine.isBusy) return busyResponse()

        val messages = mutableListOf<ChatMessage>()
        for (i in 0 until messagesJson.length()) {
            val item = messagesJson.optJSONObject(i) ?: continue
            val role = item.optString("role", "user").ifBlank { "user" }
            val content = contentToText(item.opt("content"))
            if (content.isNotEmpty() || role == "assistant") {
                messages += ChatMessage(role, content)
            }
        }
        if (messages.isEmpty()) {
            return json(
                Response.Status.BAD_REQUEST,
                errorBody("messages 不能为空", "invalid_request_error")
            )
        }

        val params = readParams(request)
        val stream = request.optBoolean("stream", false)
        val id = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").take(24)
        val requestId = "req-" + UUID.randomUUID().toString().replace("-", "").take(16)
        val created = System.currentTimeMillis() / 1000

        return if (stream) {
            streamChat(id, created, messages, params, requestId)
        } else {
            val result = runBlocking { LlamaEngine.chat(messages, params, requestId = requestId, owner = "api") }
            result.fold(
                onSuccess = { gen -> chatCompletionResponse(id, created, gen) },
                onFailure = { error -> engineError(error) }
            )
        }
    }

    private fun handleCompletions(session: IHTTPSession): Response {
        val request = readJsonBody(session)
        if (!LlamaEngine.isReady) {
            return json(
                Response.Status.SERVICE_UNAVAILABLE,
                errorBody("尚未加载 GGUF 模型，请先在 App 内加载模型", "model_not_loaded")
            )
        }

        val prompt = when (val value = request.opt("prompt")) {
            is String -> value
            is JSONArray -> if (value.length() > 0) value.optString(0) else ""
            else -> ""
        }
        if (prompt.isBlank()) {
            return json(
                Response.Status.BAD_REQUEST,
                errorBody("prompt 不能为空", "invalid_request_error")
            )
        }

        val params = readParams(request)
        val stream = request.optBoolean("stream", false)
        val id = "cmpl-" + UUID.randomUUID().toString().replace("-", "").take(24)
        val requestId = "req-" + UUID.randomUUID().toString().replace("-", "").take(16)
        val created = System.currentTimeMillis() / 1000

        modelNotFound(request)?.let { return it }
        if (LlamaEngine.isBusy) return busyResponse()

        return if (stream) {
            streamCompletion(id, created, prompt, params, requestId)
        } else {
            val result = runBlocking { LlamaEngine.complete(prompt, params, requestId = requestId, owner = "api") }
            result.fold(
                onSuccess = { gen ->
                    val body = JSONObject().apply {
                        put("id", id)
                        put("object", "text_completion")
                        put("created", created)
                        put("model", LlamaEngine.modelId)
                        put("choices", JSONArray().put(JSONObject().apply {
                            put("index", 0)
                            put("text", gen.text)
                            put("finish_reason", finishReason(gen))
                        }))
                        put("usage", usage(gen))
                    }
                    json(Response.Status.OK, body)
                },
                onFailure = { error -> engineError(error) }
            )
        }
    }

    // ---------------- 流式实现 ----------------

    private fun streamChat(
        id: String,
        created: Long,
        messages: List<ChatMessage>,
        params: GenParams,
        requestId: String
    ): Response {
        // 客户端断开（队列写满或被 NanoHTTPD close）→ 按 requestId 取消生成，
        // 只停自己这一条，不会误杀别的请求
        val queue = QueueInputStream(onDisconnect = { LlamaEngine.cancelRequest(requestId) })
        scope.launch {
            // 首字延迟在真机上可能很长（烫机时 prefill 约 180ms/token，152 token 的 prompt
            // 就要 28 秒才出第一个字）。这段时间一个字节都不发，不少客户端会当成超时或卡死。
            // 每 5 秒推一行 SSE 注释行（": ping"）保活，客户端会忽略注释内容，但连接和
            // "还在处理" 的状态是通的。
            val heartbeat = launch {
                while (true) {
                    delay(5000)
                    if (queue.isClosed) break
                    queue.push(HEARTBEAT)
                }
            }
            try {
                queue.push(sse(chatChunk(id, created, null, null, roleOnly = true)))
                // 注意：必须用具名参数写 onToken。
                // chat() 有 4 个参数（结尾是 onNotice），尾部 lambda 会绑到最后一个参数上，
                // 写成 chat(messages, params) { text -> ... } 实际是把 text 当"提示"回调，
                // onToken 仍是默认空实现 —— 表现就是流式只回 role 和 finish，正文一个字都没有。
                val result = LlamaEngine.chat(messages, params, requestId = requestId, owner = "api", onToken = { text ->
                    if (!queue.isClosed) {
                        queue.push(sse(chatChunk(id, created, text, null)))
                    }
                })
                result.fold(
                    onSuccess = { gen ->
                        queue.push(sse(chatChunk(id, created, null, finishReason(gen))))
                    },
                    onFailure = { error ->
                        queue.push(sse(errorBody(error.message ?: "推理失败", "server_error")))
                    }
                )
            } finally {
                heartbeat.cancel()
                queue.push(DONE)
                queue.closeStream()
            }
        }
        return sseResponse(queue)
    }

    private fun streamCompletion(
        id: String,
        created: Long,
        prompt: String,
        params: GenParams,
        requestId: String
    ): Response {
        val queue = QueueInputStream(onDisconnect = { LlamaEngine.cancelRequest(requestId) })
        scope.launch {
            try {
                val result = LlamaEngine.complete(prompt, params, requestId = requestId, owner = "api") { text ->
                    if (!queue.isClosed) {
                        queue.push(sse(completionChunk(id, created, text, null)))
                    }
                }
                result.fold(
                    onSuccess = { gen ->
                        queue.push(sse(completionChunk(id, created, "", finishReason(gen))))
                    },
                    onFailure = { error ->
                        queue.push(sse(errorBody(error.message ?: "推理失败", "server_error")))
                    }
                )
            } finally {
                queue.push(DONE)
                queue.closeStream()
            }
        }
        return sseResponse(queue)
    }

    private fun sseResponse(stream: InputStream): Response {
        val response = NanoHTTPD.newChunkedResponse(Response.Status.OK, "text/event-stream; charset=utf-8", stream)
        response.addHeader("Cache-Control", "no-cache")
        response.addHeader("Connection", "keep-alive")
        response.addHeader("X-Accel-Buffering", "no")
        return response
    }

    private fun chatChunk(
        id: String,
        created: Long,
        text: String?,
        finishReason: String?,
        roleOnly: Boolean = false
    ): JSONObject {
        val delta = JSONObject()
        if (roleOnly) {
            delta.put("role", "assistant")
        }
        if (text != null) {
            delta.put("content", text)
        }
        val choice = JSONObject().apply {
            put("index", 0)
            put("delta", delta)
            put("finish_reason", finishReason ?: JSONObject.NULL)
        }
        return JSONObject().apply {
            put("id", id)
            put("object", "chat.completion.chunk")
            put("created", created)
            put("model", LlamaEngine.modelId)
            put("choices", JSONArray().put(choice))
        }
    }

    private fun completionChunk(id: String, created: Long, text: String, finishReason: String?): JSONObject {
        val choice = JSONObject().apply {
            put("index", 0)
            put("text", text)
            put("finish_reason", finishReason ?: JSONObject.NULL)
        }
        return JSONObject().apply {
            put("id", id)
            put("object", "text_completion")
            put("created", created)
            put("model", LlamaEngine.modelId)
            put("choices", JSONArray().put(choice))
        }
    }

    // ---------------- 响应构造 ----------------

    private fun chatCompletionResponse(id: String, created: Long, gen: GenResult): Response {
        val body = JSONObject().apply {
            put("id", id)
            put("object", "chat.completion")
            put("created", created)
            put("model", LlamaEngine.modelId)
            put("choices", JSONArray().put(JSONObject().apply {
                put("index", 0)
                put("message", JSONObject().apply {
                    put("role", "assistant")
                    put("content", gen.text)
                })
                put("finish_reason", finishReason(gen))
            }))
            put("usage", usage(gen))
        }
        return json(Response.Status.OK, body)
    }

    private fun usage(gen: GenResult): JSONObject {
        val completion = gen.generatedTokens
        val prompt = gen.prefillTokens.coerceAtLeast(0)
        return JSONObject().apply {
            put("prompt_tokens", prompt)
            put("completion_tokens", completion)
            put("total_tokens", prompt + completion)
        }
    }

    private fun finishReason(gen: GenResult): String = when (gen.finishReason) {
        1 -> "length"
        else -> "stop"
    }

    private fun errorBody(message: String, type: String): JSONObject =
        JSONObject().put("error", JSONObject().apply {
            put("message", message)
            put("type", type)
            put("code", type)
        })

    private fun json(status: Response.IStatus, body: JSONObject): Response =
        NanoHTTPD.newFixedLengthResponse(status, "application/json; charset=utf-8", body.toString())

    private fun sse(body: JSONObject): ByteArray = "data: $body\n\n".toByteArray(Charsets.UTF_8)

    private fun withCors(response: Response): Response {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Headers", "Authorization, Content-Type, X-Api-Key")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Max-Age", "86400")
        return response
    }

    // ---------------- 请求解析 ----------------

    private fun readJsonBody(session: IHTTPSession): JSONObject {
        val raw = readRawBody(session)
        return if (raw.isBlank()) JSONObject() else JSONObject(raw)
    }

    /**
     * 直接读取原始请求体，按 UTF-8 解码。
     *
     * NanoHTTPD 2.3.1 的 parseBody() 会用 Content-Type 中的 charset 解码请求体，
     * 而多数 OpenAI 客户端发送的是不带 charset 的 `application/json`，
     * 此时 NanoHTTPD 会回退到 US-ASCII，中文等非 ASCII 字符会被不可逆地破坏。
     *
     * 旧实现只认 Content-Length，两个后果：
     *   1) 用 `Transfer-Encoding: chunked` 的客户端拿到空 body，表现为"messages 不能为空"；
     *   2) 没有大小上限，一个畸形大包就能把进程吃掉。
     * 现在两条路径都支持，并统一限制 16MB。
     */
    private fun readRawBody(session: IHTTPSession): String {
        val chunked = session.headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true
        val input = session.inputStream
        val out = java.io.ByteArrayOutputStream()
        if (chunked) {
            while (true) {
                val sizeLine = readLine(input)?.trim() ?: break
                val sizeHex = sizeLine.substringBefore(';').trim()
                val size = sizeHex.toLongOrNull(16) ?: break
                if (size <= 0L) break
                if (out.size() + size > MAX_BODY_BYTES) {
                    throw IllegalArgumentException("请求体过大（上限 ${MAX_BODY_BYTES / 1024 / 1024}MB）")
                }
                val chunk = ByteArray(size.toInt())
                var read = 0
                while (read < size) {
                    val n = input.read(chunk, read, size.toInt() - read)
                    if (n <= 0) break
                    read += n
                }
                out.write(chunk, 0, read)
                // 每个 chunk 后面的 CRLF
                readLine(input)
            }
        } else {
            val length = session.headers["content-length"]?.trim()?.toLongOrNull() ?: 0L
            if (length <= 0L) return ""
            if (length > MAX_BODY_BYTES) {
                throw IllegalArgumentException("请求体过大（上限 ${MAX_BODY_BYTES / 1024 / 1024}MB）")
            }
            val buffer = ByteArray(length.toInt())
            var read = 0
            while (read < length) {
                val n = input.read(buffer, read, length.toInt() - read)
                if (n <= 0) break
                read += n
            }
            out.write(buffer, 0, read)
        }
        return out.toString("UTF-8")
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var c = input.read()
        if (c < 0) return null
        while (c >= 0 && c != '\n'.code) {
            if (c != '\r'.code) sb.append(c.toChar())
            c = input.read()
        }
        return sb.toString()
    }

    private fun readParams(request: JSONObject): GenParams {
        val maxTokens = request.optInt(
            "max_tokens",
            request.optInt("max_completion_tokens", defaultMaxTokens)
        ).coerceIn(1, 8192)

        return GenParams(
            maxTokens = maxTokens,
            temperature = request.optDouble("temperature", defaultTemperature.toDouble()).toFloat(),
            topP = request.optDouble("top_p", 0.95).toFloat(),
            topK = request.optInt("top_k", 40),
            minP = request.optDouble("min_p", 0.05).toFloat(),
            repeatPenalty = request.optDouble("repeat_penalty", 1.1).toFloat(),
            seed = request.optInt("seed", -1)
        )
    }

    private fun contentToText(value: Any?): String = when (value) {
        null -> ""
        is String -> value
        is JSONArray -> buildString {
            for (i in 0 until value.length()) {
                val part = value.optJSONObject(i) ?: continue
                if (part.optString("type") == "text") {
                    append(part.optString("text"))
                }
            }
        }
        else -> value.toString()
    }

    private fun isAuthorized(session: IHTTPSession): Boolean {
        if (apiKey.isBlank()) return true
        val header = session.headers["authorization"]?.trim()
            ?: session.headers["x-api-key"]?.trim()
            ?: return false
        val token = if (header.startsWith("Bearer ", ignoreCase = true)) {
            header.substring(7).trim()
        } else {
            header
        }
        if (token.isEmpty()) return false
        return MessageDigest.isEqual(
            token.toByteArray(Charsets.UTF_8),
            apiKey.toByteArray(Charsets.UTF_8)
        )
    }

    private fun normalizePath(uri: String): String {
        val decoded = try {
            URLDecoder.decode(uri, "UTF-8")
        } catch (_: Throwable) {
            uri
        }
        return decoded.trimEnd('/').ifEmpty { "/" }
    }

    /** 阻塞队列包装的 InputStream，用于 SSE 边生成边输出 */
    private class QueueInputStream(private val onDisconnect: () -> Unit = {}) : InputStream() {
        // 注意：Java 的 LinkedBlockingQueue 不允许 null 元素，offer(null) 直接抛
        // NullPointerException。之前写成 LinkedBlockingQueue<ByteArray?>() 只是骗过了
        // Kotlin 编译器，运行时照样炸（crash: closeStream -> offer(null)）。
        // 现在用 0 长度数组当"输入结束"哨兵，真实数据块不可能是空数组。
        //
        // 容量必须有限：旧实现是无界队列，客户端一断，生成线程还在往里塞，
        // 队列无限长、内存无限涨、GPU 继续烧电（真机上表现为"关了客户端机器还很烫"）。
        // 256 个 chunk 大约是几 KB 的正文，超过说明消费端已经不在了。
        private val queue = LinkedBlockingQueue<ByteArray>(QUEUE_CAPACITY)
        private var current: ByteArray? = null
        private var position = 0

        @Volatile
        var isClosed: Boolean = false
            private set

        /** 队列满 = 消费端长时间不读，等同断连；只触发一次 */
        private var disconnectNotified = false

        /** 消费端最后一次取走数据的时间；用于识别"客户端已经走了但 socket 还没报错"的卡死 */
        @Volatile
        private var lastReadMs = System.currentTimeMillis()

        fun push(bytes: ByteArray) {
            if (isClosed) return
            if (!queue.offer(bytes)) {
                notifyDisconnect()
                return
            }
            // 队列已经积压（消费端一秒钟都没来取）说明写端卡住了——真机上客户端被 kill、
            // 断网、切后台时，TCP 往往要几十秒才报错，只靠队列满要等上百个 token。
            // 这里用"积压 + 1 秒无读取"提前判定，断连后 1 秒内就能停下生成。
            if (queue.size >= 2 && System.currentTimeMillis() - lastReadMs > 1000L) {
                notifyDisconnect()
            }
        }

        fun closeStream() {
            if (isClosed) return
            isClosed = true
            // 队列可能已满，用非阻塞 offer + 清空保证 EOS 一定能被读到
            while (!queue.offer(EOS)) {
                queue.poll()
            }
        }

        private fun notifyDisconnect() {
            if (disconnectNotified) return
            disconnectNotified = true
            isClosed = true
            runCatching { onDisconnect() }
        }

        override fun read(): Int {
            if (!ensure()) return -1
            return current!![position++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!ensure()) return -1
            val data = current!!
            val count = minOf(len, data.size - position)
            System.arraycopy(data, position, b, off, count)
            position += count
            return count
        }

        private fun ensure(): Boolean {
            while (current == null || position >= current!!.size) {
                val next = queue.take()
                if (next === EOS) return false
                current = next
                position = 0
                lastReadMs = System.currentTimeMillis()
            }
            lastReadMs = System.currentTimeMillis()
            return true
        }

        // NanoHTTPD 在响应结束时也会调用 close()，两条路径都必须走同一套幂等逻辑。
        // 关键：如果不是正常结束（我们没主动 closeStream），说明是客户端先断开，
        // 这里立刻把对应的 infer 请求取消掉，否则 native 会继续为空连接生成到 max_tokens。
        override fun close() {
            if (!isClosed) notifyDisconnect() else closeStream()
        }

        companion object {
            private const val QUEUE_CAPACITY = 64
        }
    }

    private companion object {
        /**
         * 429 Too Many Requests。
         *
         * NanoHTTPD 2.3.1 的 Status 枚举里没有 429，构造函数又是 private（枚举也不能反射造），
         * 但 Response.IStatus 是接口 —— 自己实现一个就能拿到真正的 429 状态码，
         * 客户端（含 OpenAI SDK）才会按"限流，可重试"处理，而不是按 5xx 当成服务崩了。
         */
        /**
         * 429 自定义状态。
         * NanoHTTPD 2.3.1 的 Status 枚举里没有 429，且构造私有，只能自己实现 IStatus。
         * 注意：NanoHTTPD 写状态行时只用 getDescription()（"HTTP/1.1 " + description），
         * 内置枚举的描述本身带状态码（如 "200 OK"）。所以这里的 description 必须写成
         * "429 Too Many Requests"，否则客户端拿到的是 "HTTP/1.1 Too Many Requests"——
         * 没有数字码，.NET/OkHttp 会直接报 invalid status code。
         */
        val STATUS_TOO_MANY_REQUESTS: Response.IStatus = object : Response.IStatus {
            override fun getRequestStatus(): Int = 429
            override fun getDescription(): String = "429 Too Many Requests"
        }
        /** 请求体上限：8~16MB 足够容纳超长 prompt，同时挡住恶意大包耗尽内存 */
        const val MAX_BODY_BYTES = 16L * 1024 * 1024
        val DONE: ByteArray = "data: [DONE]\n\n".toByteArray(Charsets.UTF_8)
        // SSE 注释行：客户端会忽略内容，只用来在长时间 prefill 期间保持连接不被判超时。
        val HEARTBEAT: ByteArray = ": ping\n\n".toByteArray(Charsets.UTF_8)
        val EOS: ByteArray = ByteArray(0)
    }
}
