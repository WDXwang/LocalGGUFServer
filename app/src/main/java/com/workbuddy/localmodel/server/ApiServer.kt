package com.workbuddy.localmodel.server

import com.workbuddy.localmodel.llm.ChatMessage
import com.workbuddy.localmodel.llm.GenParams
import com.workbuddy.localmodel.llm.GenResult
import com.workbuddy.localmodel.llm.LlamaEngine
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
        val body = JSONObject().apply {
            put("status", "ok")
            put("loaded", LlamaEngine.isReady)
            put("model", LlamaEngine.modelId)
            put("object", "local-model.health")
        }
        return json(Response.Status.OK, body)
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
        val created = System.currentTimeMillis() / 1000

        return if (stream) {
            streamChat(id, created, messages, params)
        } else {
            val result = runBlocking { LlamaEngine.chat(messages, params) }
            result.fold(
                onSuccess = { gen -> chatCompletionResponse(id, created, gen) },
                onFailure = { error ->
                    json(Response.Status.INTERNAL_ERROR, errorBody(error.message ?: "推理失败", "server_error"))
                }
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
        val created = System.currentTimeMillis() / 1000

        return if (stream) {
            streamCompletion(id, created, prompt, params)
        } else {
            val result = runBlocking { LlamaEngine.complete(prompt, params) }
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
                onFailure = { error ->
                    json(Response.Status.INTERNAL_ERROR, errorBody(error.message ?: "推理失败", "server_error"))
                }
            )
        }
    }

    // ---------------- 流式实现 ----------------

    private fun streamChat(
        id: String,
        created: Long,
        messages: List<ChatMessage>,
        params: GenParams
    ): Response {
        val queue = QueueInputStream()
        scope.launch {
            try {
                queue.push(sse(chatChunk(id, created, null, null, roleOnly = true)))
                val result = LlamaEngine.chat(messages, params) { text ->
                    if (!queue.isClosed) {
                        queue.push(sse(chatChunk(id, created, text, null)))
                    }
                }
                result.fold(
                    onSuccess = { gen ->
                        queue.push(sse(chatChunk(id, created, null, finishReason(gen))))
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

    private fun streamCompletion(
        id: String,
        created: Long,
        prompt: String,
        params: GenParams
    ): Response {
        val queue = QueueInputStream()
        scope.launch {
            try {
                val result = LlamaEngine.complete(prompt, params) { text ->
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

    private fun json(status: Response.Status, body: JSONObject): Response =
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
     * 直接读取 Content-Length 指定的原始请求体，按 UTF-8 解码。
     *
     * NanoHTTPD 2.3.1 的 parseBody() 会用 Content-Type 中的 charset 解码请求体，
     * 而多数 OpenAI 客户端发送的是不带 charset 的 `application/json`，
     * 此时 NanoHTTPD 会回退到 US-ASCII，中文等非 ASCII 字符会被不可逆地破坏。
     */
    private fun readRawBody(session: IHTTPSession): String {
        val length = session.headers["content-length"]?.trim()?.toIntOrNull() ?: 0
        if (length <= 0) return ""
        val buffer = ByteArray(length)
        var read = 0
        val input = session.inputStream
        while (read < length) {
            val n = input.read(buffer, read, length - read)
            if (n <= 0) break
            read += n
        }
        return String(buffer, 0, read, Charsets.UTF_8)
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
    private class QueueInputStream : InputStream() {
        // 注意：Java 的 LinkedBlockingQueue 不允许 null 元素，offer(null) 直接抛
        // NullPointerException。之前写成 LinkedBlockingQueue<ByteArray?>() 只是骗过了
        // Kotlin 编译器，运行时照样炸（crash: closeStream -> offer(null)）。
        // 现在用 0 长度数组当"输入结束"哨兵，真实数据块不可能是空数组。
        private val queue = LinkedBlockingQueue<ByteArray>()
        private var current: ByteArray? = null
        private var position = 0

        @Volatile
        var isClosed: Boolean = false
            private set

        fun push(bytes: ByteArray) {
            if (!isClosed) {
                queue.offer(bytes)
            }
        }

        fun closeStream() {
            if (isClosed) return
            isClosed = true
            queue.offer(EOS)
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
            }
            return true
        }

        // NanoHTTPD 在响应结束时也会调用 close()，两条路径都必须走同一套幂等逻辑
        override fun close() {
            closeStream()
        }
    }

    private companion object {
        val DONE: ByteArray = "data: [DONE]\n\n".toByteArray(Charsets.UTF_8)
        val EOS: ByteArray = ByteArray(0)
    }
}
