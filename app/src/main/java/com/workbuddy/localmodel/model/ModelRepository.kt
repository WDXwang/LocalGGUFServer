package com.workbuddy.localmodel.model

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class ModelFile(
    val file: File,
    val name: String,
    val sizeBytes: Long
)

class ModelRepository(private val context: Context) {

    val modelsDir: File = File(context.filesDir, "models").apply { mkdirs() }

    private val _models = MutableStateFlow(scan())
    val models: StateFlow<List<ModelFile>> = _models.asStateFlow()

    fun refresh() {
        _models.value = scan()
    }

    fun delete(file: File): Boolean {
        val ok = file.delete()
        refresh()
        return ok
    }

    suspend fun import(uri: Uri, onProgress: (Float) -> Unit = {}): Result<File> =
        withContext(Dispatchers.IO) {
            var target: File? = null
            try {
                val displayName = queryDisplayName(uri) ?: "model-${System.currentTimeMillis()}.gguf"
                val safeName = sanitize(displayName)
                val dest = uniqueTarget(safeName)
                target = dest
                val total = querySize(uri)

                context.contentResolver.openInputStream(uri).use { input ->
                    if (input == null) {
                        return@withContext Result.failure(IllegalStateException("无法读取所选文件"))
                    }
                    FileOutputStream(dest).use { output ->
                        val buffer = ByteArray(1024 * 1024)
                        var copied = 0L
                        var lastReport = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            if (total > 0 && copied - lastReport > 8L * 1024 * 1024) {
                                lastReport = copied
                                onProgress((copied.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                        output.flush()
                    }
                }

                // 早期校验文件头，避免把改名的普通文件当成模型导入后白白占用空间
                if (!looksLikeGguf(dest)) {
                    dest.delete()
                    target = null
                    return@withContext Result.failure(
                        IllegalArgumentException("所选文件不是 GGUF 模型（文件头缺少 GGUF 标识）")
                    )
                }

                onProgress(1f)
                refresh()
                Result.success(dest)
            } catch (t: Throwable) {
                // 复制中断时清理半成品文件，避免磁盘残留
                target?.delete()
                Result.failure(t)
            }
        }

    private fun looksLikeGguf(file: File): Boolean = try {
        file.inputStream().use { input ->
            val head = ByteArray(4)
            var read = 0
            while (read < 4) {
                val n = input.read(head, read, 4 - read)
                if (n <= 0) break
                read += n
            }
            read == 4 &&
                    head[0] == 'G'.code.toByte() &&
                    head[1] == 'G'.code.toByte() &&
                    head[2] == 'U'.code.toByte() &&
                    head[3] == 'F'.code.toByte()
        }
    } catch (_: Throwable) {
        false
    }

    private fun scan(): List<ModelFile> =
        modelsDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".gguf", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?.map { ModelFile(it, it.name, it.length()) }
            ?: emptyList()

    private fun queryDisplayName(uri: Uri): String? {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                return cursor.getString(index)
            }
        }
        return uri.lastPathSegment
    }

    private fun querySize(uri: Uri): Long {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                return cursor.getLong(index)
            }
        }
        return 0
    }

    private fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return if (cleaned.endsWith(".gguf", ignoreCase = true)) cleaned else "$cleaned.gguf"
    }

    private fun uniqueTarget(name: String): File {
        var target = File(modelsDir, name)
        if (!target.exists()) return target
        val base = name.substringBeforeLast('.')
        var index = 1
        while (target.exists()) {
            target = File(modelsDir, "$base-$index.gguf")
            index++
        }
        return target
    }
}
