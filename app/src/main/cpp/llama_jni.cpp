#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"
#include "ggml-backend.h"

#define LOG_TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

JavaVM *g_vm = nullptr;

struct LlamaSession {
    llama_model       *model  = nullptr;
    llama_context     *ctx    = nullptr;
    const llama_vocab *vocab  = nullptr;
    std::string        chat_template;
    std::atomic<bool>  stop{false};
    std::mutex         gen_mutex;

    // KV 复用：记录当前 KV cache 里已经解码过的 token 序列（prompt + 已生成的 token）。
    // 下次请求若 prompt 与它有公共前缀，只需 decode 新增的那一段，不必整段重新 prefill。
    // 仅在持有 gen_mutex 时访问。
    std::vector<llama_token> kv_tokens;

    // 上一次生成的耗时，供 Kotlin 侧算 t/s 与 TTFT。
    // prefill_ms 从开始 decode prompt 算到 prompt 处理完（≈首 token 延迟），
    // gen_ms 从 prompt 处理完算到最后一个 token，两者都不含 UI 侧开销。
    std::atomic<long long> last_prefill_ms{0};
    std::atomic<long long> last_gen_ms{0};
    std::atomic<int>       last_generated{0};

    // 加载时请求的 GPU 层数，用于和实际生效值一起回显
    int32_t requested_gpu_layers = 0;
};

long long now_ms() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

const char *backend_dev_type_name(enum ggml_backend_dev_type type) {
    switch (type) {
        case GGML_BACKEND_DEVICE_TYPE_CPU:   return "CPU";
        case GGML_BACKEND_DEVICE_TYPE_GPU:   return "GPU";
        case GGML_BACKEND_DEVICE_TYPE_ACCEL: return "ACCEL";
        default:                             return "?";
    }
}


enum FinishReason {
    FINISH_STOP      = 0,
    FINISH_LENGTH    = 1,
    FINISH_CANCELLED = 2,
    FINISH_ERROR     = 3,
};

// ---------------- UTF-8 / UTF-16 helpers ----------------

std::string jstring_to_utf8(JNIEnv *env, jstring s) {
    if (s == nullptr) {
        return {};
    }
    const jsize len = env->GetStringLength(s);
    const jchar *chars = env->GetStringChars(s, nullptr);
    if (chars == nullptr) {
        return {};
    }

    std::string out;
    out.reserve(static_cast<size_t>(len) * 3);
    for (jsize i = 0; i < len; ++i) {
        uint32_t cp = chars[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < len) {
            const uint32_t lo = chars[i + 1];
            if (lo >= 0xDC00 && lo <= 0xDFFF) {
                cp = 0x10000u + ((cp - 0xD800u) << 10) + (lo - 0xDC00u);
                ++i;
            }
        }
        if (cp < 0x80) {
            out.push_back(static_cast<char>(cp));
        } else if (cp < 0x800) {
            out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else if (cp < 0x10000) {
            out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else {
            out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        }
    }
    env->ReleaseStringChars(s, chars);
    return out;
}

jstring utf8_to_jstring(JNIEnv *env, const std::string &s) {
    std::vector<jchar> utf16;
    utf16.reserve(s.size());

    size_t i = 0;
    while (i < s.size()) {
        const unsigned char c = static_cast<unsigned char>(s[i]);
        uint32_t cp = 0;
        size_t extra = 0;
        if (c < 0x80) {
            cp = c;
        } else if ((c >> 5) == 0x06 && i + 1 < s.size()) {
            cp = ((c & 0x1F) << 6) | (static_cast<unsigned char>(s[i + 1]) & 0x3F);
            extra = 1;
        } else if ((c >> 4) == 0x0E && i + 2 < s.size()) {
            cp = ((c & 0x0F) << 12) |
                 ((static_cast<unsigned char>(s[i + 1]) & 0x3F) << 6) |
                 (static_cast<unsigned char>(s[i + 2]) & 0x3F);
            extra = 2;
        } else if ((c >> 3) == 0x1E && i + 3 < s.size()) {
            cp = ((c & 0x07) << 18) |
                 ((static_cast<unsigned char>(s[i + 1]) & 0x3F) << 12) |
                 ((static_cast<unsigned char>(s[i + 2]) & 0x3F) << 6) |
                 (static_cast<unsigned char>(s[i + 3]) & 0x3F);
            extra = 3;
        } else {
            ++i;
            continue;
        }
        i += extra + 1;

        if (cp < 0x10000) {
            utf16.push_back(static_cast<jchar>(cp));
        } else {
            cp -= 0x10000;
            utf16.push_back(static_cast<jchar>(0xD800 + (cp >> 10)));
            utf16.push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF)));
        }
    }
    if (utf16.empty()) {
        return env->NewStringUTF("");
    }
    return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
}

// 返回 s 中完整 UTF-8 字符的字节数，避免把多字节字符按 token 切断
size_t utf8_complete_prefix(const std::string &s) {
    size_t i = 0;
    size_t last = 0;
    while (i < s.size()) {
        const unsigned char c = static_cast<unsigned char>(s[i]);
        size_t len = 0;
        if (c < 0x80) {
            len = 1;
        } else if ((c >> 5) == 0x06) {
            len = 2;
        } else if ((c >> 4) == 0x0E) {
            len = 3;
        } else if ((c >> 3) == 0x1E) {
            len = 4;
        }
        if (len == 0) {
            ++i; // 非法起始字节，跳过
            last = i;
            continue;
        }
        if (i + len > s.size()) {
            break; // 字符尚未接收完整
        }
        i += len;
        last = i;
    }
    return last;
}

std::atomic<bool> g_verbose_logs{false};

void android_log_callback(ggml_log_level level, const char *text, void * /*user_data*/) {
    if (text == nullptr) {
        return;
    }
    // Debug 构建里 ggml 没有 NDEBUG，会按 DEBUG 级打印每个图节点的细节。
    // 逐条 __android_log_write 是同步的 logd 事务，4B 模型 decode 时能把速度拖到
    // 0.2 t/s 量级，属于实打实的性能损耗。默认只放 WARN 及以上，排障时用
    // nativeSetVerboseLogging(true) 打开 DEBUG。
    if (level == GGML_LOG_LEVEL_DEBUG && !g_verbose_logs.load(std::memory_order_relaxed)) {
        return;
    }
    int prio = ANDROID_LOG_DEBUG;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: prio = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN:  prio = ANDROID_LOG_WARN;  break;
        case GGML_LOG_LEVEL_INFO:  prio = ANDROID_LOG_INFO;  break;
        default:                   prio = ANDROID_LOG_DEBUG; break;
    }
    __android_log_write(prio, LOG_TAG, text);
}

// ---------------- 回调 ----------------

struct JavaCallback {
    jobject    callback = nullptr;
    jmethodID  on_token = nullptr;
    jmethodID  on_complete = nullptr;
    JNIEnv    *env = nullptr;
    bool       attached = false;

    bool attach() {
        if (g_vm == nullptr) {
            return false;
        }
        const jint ret = g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
        if (ret == JNI_EDETACHED) {
            // C++ 下 AttachCurrentThread 的签名是 (JNIEnv **, void *)，不能再转成 void **
            if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
                return false;
            }
            attached = true;
        } else if (ret != JNI_OK) {
            return false;
        }

        jclass cls = env->GetObjectClass(callback);
        on_token = env->GetMethodID(cls, "onToken", "([B)V");
        on_complete = env->GetMethodID(cls, "onComplete", "(II)V");
        env->DeleteLocalRef(cls);
        return on_token != nullptr && on_complete != nullptr;
    }

    void detach() {
        if (attached) {
            g_vm->DetachCurrentThread();
            attached = false;
        }
    }

    void token(const std::string &text) {
        if (env == nullptr || on_token == nullptr || text.empty()) {
            return;
        }
        jbyteArray arr = env->NewByteArray(static_cast<jsize>(text.size()));
        if (arr == nullptr) {
            return;
        }
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(text.size()),
                                reinterpret_cast<const jbyte *>(text.data()));
        env->CallVoidMethod(callback, on_token, arr);
        env->DeleteLocalRef(arr);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
    }

    void complete(int reason, int tokens) {
        if (env == nullptr || on_complete == nullptr) {
            return;
        }
        env->CallVoidMethod(callback, on_complete, reason, tokens);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
    }
};

// ---------------- 提示词构建 ----------------

std::string chatml_fallback(const std::vector<std::pair<std::string, std::string>> &msgs,
                            bool add_assistant) {
    std::string out;
    for (const auto &m : msgs) {
        out += "<|im_start|>";
        out += m.first;
        out += "\n";
        out += m.second;
        out += "<|im_end|>\n";
    }
    if (add_assistant) {
        out += "<|im_start|>assistant\n";
    }
    return out;
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void * /*reserved*/) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL
Java_com_workbuddy_localmodel_llm_LlamaBridge_nativeSetVerboseLogging(JNIEnv * /*env*/, jobject /*thiz*/, jboolean enabled) {
    g_verbose_logs.store(enabled == JNI_TRUE, std::memory_order_relaxed);
    LOGI("native verbose logging = %s", enabled == JNI_TRUE ? "on" : "off");
}

JNIEXPORT void JNICALL
Java_com_workbuddy_localmodel_llm_LlamaBridge_nativeBackendInit(JNIEnv * /*env*/, jobject /*thiz*/) {
    llama_log_set(android_log_callback, nullptr);
    llama_backend_init();
    LOGI("llama.cpp backend initialized: %s", llama_version());
}

JNIEXPORT jlong JNICALL
Java_com_workbuddy_localmodel_llm_LlamaBridge_nativeLoadModel(
        JNIEnv *env, jobject /*thiz*/, jstring path, jint n_threads, jint n_ctx,
        jint n_batch, jint n_gpu_layers, jboolean n_mlock) {

    const std::string model_path = jstring_to_utf8(env, path);
    if (model_path.empty()) {
        return 0;
    }

    auto *session = new LlamaSession();
    session->requested_gpu_layers = n_gpu_layers;

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = n_gpu_layers;

    // use_mlock 把权重锁在物理内存里，避免长时间运行被系统回收后缺页崩溃；
    // 剩余内存不足时会加载失败，所以失败后自动回退到 mmap 模式重试一次
    const bool want_mlock = (n_mlock == JNI_TRUE) && llama_supports_mlock();
    // 这一版 llama.cpp 用 load_mode 表达内存锁：MMAP_MLOCK = mmap + 锁在物理内存
    mparams.load_mode = want_mlock ? LLAMA_LOAD_MODE_MMAP_MLOCK : LLAMA_LOAD_MODE_MMAP;
    bool mlock_in_use = want_mlock;

    session->model = llama_model_load_from_file(model_path.c_str(), mparams);
    if (session->model == nullptr && want_mlock) {
        LOGW("mlock load failed (free memory may be insufficient), retrying without mlock");
        mparams.load_mode = LLAMA_LOAD_MODE_MMAP;
        mlock_in_use = false;
        session->model = llama_model_load_from_file(model_path.c_str(), mparams);
    }
    if (session->model == nullptr) {
        LOGE("failed to load model: %s", model_path.c_str());
        delete session;
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(n_ctx > 0 ? n_ctx : 4096);
    cparams.n_batch = static_cast<uint32_t>(n_batch > 0 ? n_batch : 512);
    cparams.n_ubatch = std::min<uint32_t>(cparams.n_batch, 512);
    cparams.n_threads = n_threads > 0 ? n_threads : 4;
    cparams.n_threads_batch = cparams.n_threads;
    cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
    cparams.no_perf = true;

    session->ctx = llama_init_from_model(session->model, cparams);
    if (session->ctx == nullptr) {
        LOGE("failed to create context");
        llama_model_free(session->model);
        delete session;
        return 0;
    }

    session->vocab = llama_model_get_vocab(session->model);
    const char *tmpl = llama_model_chat_template(session->model, nullptr);
    if (tmpl != nullptr) {
        session->chat_template = tmpl;
    }

    LOGI("model loaded: %s (ctx=%u, threads=%d, batch=%u, mlock=%d, mlock_supported=%d, "
         "gpu_offload_supported=%d, gpu_layers=%d, n_layer=%d)",
         model_path.c_str(), llama_n_ctx(session->ctx), cparams.n_threads,
         cparams.n_batch, mlock_in_use ? 1 : 0, llama_supports_mlock() ? 1 : 0,
         llama_supports_gpu_offload() ? 1 : 0, n_gpu_layers,
         llama_model_n_layer(session->model));
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT void JNICALL
Java_com_workbuddy_localmodel_llm_LlamaBridge_nativeFreeModel(JNIEnv * /*env*/, jobject /*thiz*/,
                                                             jlong handle) {
    auto *session = reinterpret_cast<LlamaSession *>(handle);
    if (session == nullptr) {
        return;
    }
    session->stop.store(true);
    std::lock_guard<std::mutex> lock(session->gen_mutex);
    if (session->ctx != nullptr) {
        llama_free(session->ctx);
    }
    if (session->model != nullptr) {
        llama_model_free(session->model);
    }
    delete session;
}

JNIEXPORT jstring JNICALL
Java_com_workbuddy_localmodel_llm_LlamaBridge_nativeModelDesc(JNIEnv *env, jobject /*thiz*/,
                                                             jlong handle) {
    auto *session = reinterpret_cast<LlamaSession *>(handle);
    if (session == nullptr || session->model == nullptr) {
        return utf8_to_jstring(env, "");
    }
    char buf[256] = {0};
    llama_model_desc(session->model, buf, sizeof(buf));
    return utf8_to_jstring(env, buf);
}

JNIEXPORT jlong JNICALL
Java_com_workbuddy_localmodel_llm_LlamaBridge_nativeModelParams(JNIEnv * /*env*/, jobject /*thiz*/,
                                                               jlong handle) {
    auto *session = reinterpret_cast<LlamaSession *>(handle);
    if (session == nullptr || session->model == nullptr) {
        return 0;
    }
    return static_cast<jlong>(llama_model_n_params(session->model));
}

JNIEXPORT jlong JNICALL
Java_com_workbuddy_localmodel_llm_LlamaBridge_nativeModelSize(JNIEnv * /*env*/, jobject /*thiz*/,
                                                             jlong handle) {
    auto *session = reinterpret_cast<LlamaSession *>(handle);
    if (session == nullptr || session->model == nullptr) {
        return 0;
    }
    return static_cast<jlong>(llama_model_size(session->model));
}

/**
 * GPU 后端实况：注册到的 device 名单 + 模型层数 + 请求的卸载层数。
 *
 * 这一版 llama.cpp 没有 llama_model_n_gpu_layers()，无法直接读回"实际卸载了几层"，
 * 所以这里给的是可验证的等价信息：后端是否真的注册成功（Vulkan device 出现在列表里）、
 * 设备的真实名字（Mali-G720）、模型总层数、请求值。
 * 三者一起看就能判断 -Pvulkan 到底有没有生效，而不是只看构建成功。
 * 返回 JSON 串，Kotlin 侧解析后直接显示。
 */
JNIEXPORT jstring JNICALL
Java_com_workbuddy_localmodel_llm_LlamaBridge_nativeGpuInfo(JNIEnv *env, jobject /*thiz*/,
                                                            jlong handle) {
    auto *session = reinterpret_cast<LlamaSession *>(handle);
    if (session == nullptr || session->model == nullptr) {
        return utf8_to_jstring(env, "{}");
    }

    const int32_t n_layer = llama_model_n_layer(session->model);
    const bool offload = llama_supports_gpu_offload();
    const size_t n_dev = ggml_backend_dev_count();
    // 负值表示"全部层"，回显时按总层数折算成实际生效上限
    int32_t effective = session->requested_gpu_layers;
    if (effective < 0) {
        effective = n_layer;
    } else if (effective > n_layer) {
        effective = n_layer;
    }

    std::string out = "{";
    out += "\"offload_supported\":" + std::to_string(offload ? 1 : 0);
    out += ",\"n_layer\":" + std::to_string(n_layer);
    out += ",\"requested\":" + std::to_string(session->requested_gpu_layers);
    out += ",\"effective\":" + std::to_string(effective);
    out += ",\"devices\":[";
    for (size_t i = 0; i < n_dev; ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        if (dev == nullptr) {
            continue;
        }
        if (i > 0) {
            out += ",";
        }
        std::string name = ggml_backend_dev_name(dev);
        std::string desc = ggml_backend_dev_description(dev);
        // 手写转义，名字里出现引号/反斜杠的概率极低但别让 JSON 坏掉
        auto esc = [](const std::string &s) {
            std::string r;
            for (char c : s) {
                if (c == '"' || c == '\\') r.push_back('\\');
                r.push_back(c);
            }
            return r;
        };
        out += "{\"type\":\"" + std::string(backend_dev_type_name(ggml_backend_dev_type(dev))) + "\"";
        out += ",\"name\":\"" + esc(name) + "\"";
        out += ",\"desc\":\"" + esc(desc) + "\"}";
    }
    out += "]}";

    LOGI("gpu info: %s", out.c_str());
    return utf8_to_jstring(env, out);
}

/**
 * 上一次生成的耗时（毫秒）：返回 [prefill_ms, gen_ms, generated_tokens]。
 * 有了它 Kotlin 侧才能算 t/s 与 TTFT，而不是只能看日志。
 */
JNIEXPORT jlongArray JNICALL
Java_com_workbuddy_localmodel_llm_LlamaBridge_nativeLastTiming(JNIEnv *env, jobject /*thiz*/,
                                                              jlong handle) {
    auto *session = reinterpret_cast<LlamaSession *>(handle);
    jlong values[3] = {0, 0, 0};
    if (session != nullptr) {
        values[0] = session->last_prefill_ms.load();
        values[1] = session->last_gen_ms.load();
        values[2] = session->last_generated.load();
    }
    jlongArray arr = env->NewLongArray(3);
    if (arr != nullptr) {
        env->SetLongArrayRegion(arr, 0, 3, values);
    }
    return arr;
}

JNIEXPORT jint JNICALL
Java_com_workbuddy_localmodel_llm_LlamaBridge_nativeContextSize(JNIEnv * /*env*/, jobject /*thiz*/,
                                                               jlong handle) {    auto *session = reinterpret_cast<LlamaSession *>(handle);
    if (session == nullptr || session->ctx == nullptr) {
        return 0;
    }
    return static_cast<jint>(llama_n_ctx(session->ctx));
}

JNIEXPORT jboolean JNICALL
Java_com_workbuddy_localmodel_llm_LlamaBridge_nativeHasChatTemplate(JNIEnv * /*env*/,
                                                                   jobject /*thiz*/,
                                                                   jlong handle) {
    auto *session = reinterpret_cast<LlamaSession *>(handle);
    return (session != nullptr && !session->chat_template.empty()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_workbuddy_localmodel_llm_LlamaBridge_nativeBuildPrompt(
        JNIEnv *env, jobject /*thiz*/, jlong handle, jobjectArray roles,
        jobjectArray contents, jboolean add_assistant) {

    auto *session = reinterpret_cast<LlamaSession *>(handle);
    if (session == nullptr || session->model == nullptr) {
        return utf8_to_jstring(env, "");
    }

    const jsize n = env->GetArrayLength(roles);
    std::vector<std::pair<std::string, std::string>> msgs;
    msgs.reserve(static_cast<size_t>(n));
    for (jsize i = 0; i < n; ++i) {
        auto role = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
        auto content = static_cast<jstring>(env->GetObjectArrayElement(contents, i));
        msgs.emplace_back(jstring_to_utf8(env, role), jstring_to_utf8(env, content));
        env->DeleteLocalRef(role);
        env->DeleteLocalRef(content);
    }

    std::vector<llama_chat_message> chat;
    chat.reserve(msgs.size());
    for (const auto &m : msgs) {
        chat.push_back({m.first.c_str(), m.second.c_str()});
    }

    if (!session->chat_template.empty()) {
        const int32_t needed = llama_chat_apply_template(
                session->chat_template.c_str(), chat.data(), chat.size(),
                add_assistant == JNI_TRUE, nullptr, 0);
        if (needed > 0) {
            std::string out(static_cast<size_t>(needed), '\0');
            const int32_t written = llama_chat_apply_template(
                    session->chat_template.c_str(), chat.data(), chat.size(),
                    add_assistant == JNI_TRUE, out.data(), needed);
            if (written > 0) {
                out.resize(std::min<size_t>(static_cast<size_t>(written), out.size()));
                return utf8_to_jstring(env, out);
            }
        }
        LOGI("chat template not supported by llama.cpp, fallback to ChatML");
    }

    return utf8_to_jstring(env, chatml_fallback(msgs, add_assistant == JNI_TRUE));
}

JNIEXPORT jint JNICALL
Java_com_workbuddy_localmodel_llm_LlamaBridge_nativeCountTokens(JNIEnv *env, jobject /*thiz*/,
                                                               jlong handle, jstring text) {
    auto *session = reinterpret_cast<LlamaSession *>(handle);
    if (session == nullptr || session->vocab == nullptr) {
        return 0;
    }
    const std::string utf8 = jstring_to_utf8(env, text);
    const int32_t n = llama_tokenize(session->vocab, utf8.c_str(),
                                     static_cast<int32_t>(utf8.size()),
                                     nullptr, 0, true, true);
    return static_cast<jint>(n < 0 ? -n : n);
}

JNIEXPORT void JNICALL
Java_com_workbuddy_localmodel_llm_LlamaBridge_nativeStop(JNIEnv * /*env*/, jobject /*thiz*/,
                                                        jlong handle) {
    auto *session = reinterpret_cast<LlamaSession *>(handle);
    if (session != nullptr) {
        session->stop.store(true);
    }
}

JNIEXPORT jint JNICALL
Java_com_workbuddy_localmodel_llm_LlamaBridge_nativeGenerate(
        JNIEnv *env, jobject /*thiz*/, jlong handle, jstring prompt,
        jint max_tokens, jfloat temperature, jfloat top_p, jint top_k,
        jfloat min_p, jfloat repeat_penalty, jint seed, jobject callback) {

    auto *session = reinterpret_cast<LlamaSession *>(handle);
    if (session == nullptr || session->ctx == nullptr) {
        return -1;
    }

    if (!session->gen_mutex.try_lock()) {
        return -5; // 已有生成任务
    }
    struct MutexGuard {
        std::mutex &m;
        ~MutexGuard() { m.unlock(); }
    } guard{session->gen_mutex};

    session->stop.store(false);

    JavaCallback cb;
    cb.callback = callback;
    if (!cb.attach()) {
        LOGE("failed to attach JNI callback");
        return -1;
    }

    auto finish = [&](int reason, int tokens) {
        cb.complete(reason, tokens);
        cb.detach();
    };

    // 1. tokenize
    const std::string prompt_utf8 = jstring_to_utf8(env, prompt);
    int32_t n_tokens = -llama_tokenize(session->vocab, prompt_utf8.c_str(),
                                       static_cast<int32_t>(prompt_utf8.size()),
                                       nullptr, 0, true, true);
    if (n_tokens <= 0) {
        finish(FINISH_ERROR, 0);
        return -2;
    }
    std::vector<llama_token> tokens(static_cast<size_t>(n_tokens));
    const int32_t tokenized = llama_tokenize(session->vocab, prompt_utf8.c_str(),
                                             static_cast<int32_t>(prompt_utf8.size()),
                                             tokens.data(), n_tokens, true, true);
    if (tokenized <= 0) {
        finish(FINISH_ERROR, 0);
        return -2;
    }
    n_tokens = tokenized;
    tokens.resize(static_cast<size_t>(n_tokens));

    // 2. 上下文长度保护：必要时从头部截断
    const int32_t n_ctx = static_cast<int32_t>(llama_n_ctx(session->ctx));
    int32_t gen_limit = max_tokens > 0 ? max_tokens : 256;
    if (gen_limit > n_ctx / 2) {
        gen_limit = n_ctx / 2;
    }
    const int32_t budget = n_ctx - gen_limit - 8;
    if (n_tokens > budget && budget > 0) {
        const int32_t drop = n_tokens - budget;
        tokens.erase(tokens.begin(), tokens.begin() + drop);
        n_tokens = budget;

        const llama_token bos = llama_vocab_bos(session->vocab);
        if (llama_vocab_get_add_bos(session->vocab) && bos != LLAMA_TOKEN_NULL &&
            (tokens.empty() || tokens.front() != bos)) {
            tokens.insert(tokens.begin(), bos);
            if (static_cast<int32_t>(tokens.size()) > budget) {
                tokens.pop_back();
            }
            n_tokens = static_cast<int32_t>(tokens.size());
        }
        LOGI("prompt truncated to %d tokens", n_tokens);
    }

    // 3. KV 复用：与上一次的 KV 内容求最长公共前缀，命中则只 decode 新增部分。
    //    chat 模板是确定性的，多轮对话里前面几轮的消息逐 token 相同，
    //    因此不必每轮都 llama_memory_clear + 全量重放，首 token 延迟不再随上下文线性增长。
    llama_memory_t mem = llama_get_memory(session->ctx);
    int32_t reuse = 0;
    {
        const int32_t limit = static_cast<int32_t>(
                std::min<size_t>(session->kv_tokens.size(), tokens.size()));
        while (reuse < limit && session->kv_tokens[reuse] == tokens[reuse]) {
            ++reuse;
        }
        // 前缀太短时复用没有收益，直接整段重算，避免 KV 里留下碎片
        if (reuse < 16) {
            reuse = 0;
        }
    }
    if (reuse > 0 && !llama_memory_seq_rm(mem, 0, reuse, -1)) {
        LOGI("llama_memory_seq_rm failed at %d, fall back to full prefill", reuse);
        reuse = 0;
    }
    if (reuse == 0) {
        llama_memory_clear(mem, true);
    }
    session->kv_tokens.resize(static_cast<size_t>(reuse));
    if (reuse > 0) {
        LOGI("kv reuse: %d/%d prompt tokens already cached", reuse, n_tokens);
    }

    // 4. 只 decode 公共前缀之后的部分（首次请求时 reuse == 0，等于全量 prefill）
    const long long t_prefill_start = now_ms();
    const uint32_t n_batch = llama_n_batch(session->ctx);
    for (int32_t i = reuse; i < n_tokens; i += static_cast<int32_t>(n_batch)) {
        if (session->stop.load()) {
            finish(FINISH_CANCELLED, 0);
            return 0;
        }
        const int32_t chunk = std::min<int32_t>(static_cast<int32_t>(n_batch), n_tokens - i);
        llama_batch batch = llama_batch_get_one(tokens.data() + i, chunk);
        if (llama_decode(session->ctx, batch) != 0) {
            LOGE("llama_decode(prompt) failed at %d", i);
            // decode 失败后 KV 状态不可信，作废缓存
            llama_memory_clear(mem, true);
            session->kv_tokens.clear();
            finish(FINISH_ERROR, 0);
            return -4;
        }
        session->kv_tokens.insert(session->kv_tokens.end(),
                                  tokens.begin() + i, tokens.begin() + i + chunk);
    }
    // prefill 结束 ≈ 首 token 延迟（TTFT）里可控的那一段
    const long long t_prefill_end = now_ms();

    // 5. sampler
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    sparams.no_perf = true;
    llama_sampler *smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(
            llama_vocab_n_tokens(session->vocab), 64, repeat_penalty, 0.0f, 0.0f));

    if (temperature <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        if (top_k > 0) {
            llama_sampler_chain_add(smpl, llama_sampler_init_top_k(top_k));
        }
        if (top_p > 0.0f && top_p < 1.0f) {
            llama_sampler_chain_add(smpl, llama_sampler_init_top_p(top_p, 1));
        }
        if (min_p > 0.0f) {
            llama_sampler_chain_add(smpl, llama_sampler_init_min_p(min_p, 1));
        }
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(
                seed >= 0 ? static_cast<uint32_t>(seed) : LLAMA_DEFAULT_SEED));
    }

    // 6. 逐 token 生成
    std::string pending;
    int generated = 0;
    int finish_reason = FINISH_STOP;
    std::vector<char> piece_buf(256);

    for (int i = 0; i < gen_limit; ++i) {
        if (session->stop.load()) {
            finish_reason = FINISH_CANCELLED;
            break;
        }

        llama_token id = llama_sampler_sample(smpl, session->ctx, -1);
        if (llama_vocab_is_eog(session->vocab, id)) {
            finish_reason = FINISH_STOP;
            break;
        }

        int32_t piece_len = llama_token_to_piece(session->vocab, id, piece_buf.data(),
                                                 static_cast<int32_t>(piece_buf.size()), 0, false);
        if (piece_len < 0) {
            piece_buf.resize(static_cast<size_t>(-piece_len));
            piece_len = llama_token_to_piece(session->vocab, id, piece_buf.data(),
                                             static_cast<int32_t>(piece_buf.size()), 0, false);
        }
        if (piece_len > 0) {
            pending.append(piece_buf.data(), static_cast<size_t>(piece_len));

            const size_t complete = utf8_complete_prefix(pending);
            if (complete > 0) {
                cb.token(pending.substr(0, complete));
                pending.erase(0, complete);
            }
        }
        ++generated;

        llama_batch single = llama_batch_get_one(&id, 1);
        if (llama_decode(session->ctx, single) != 0) {
            LOGE("llama_decode(generated) failed at %d", i);
            // KV 状态不可信，作废缓存，下次请求走全量 prefill
            llama_memory_clear(mem, true);
            session->kv_tokens.clear();
            finish_reason = FINISH_ERROR;
            break;
        }
        // 生成的 token 也进了 KV，记下来供下次对话复用作前缀
        session->kv_tokens.push_back(id);

        if (i + 1 >= gen_limit) {
            finish_reason = FINISH_LENGTH;
        }
    }

    if (!pending.empty()) {
        cb.token(pending);
    }
    llama_sampler_free(smpl);

    // 生成阶段耗时：从 prefill 结束到最后一个 token，不含首 token 等待，
    // 这样 t/s 才是 decode 速度，不会被长 prompt 的 prefill 拉低
    const long long t_end = now_ms();
    const long long prefill_ms = t_prefill_end - t_prefill_start;
    const long long gen_ms = t_end - t_prefill_end;
    session->last_prefill_ms.store(prefill_ms);
    session->last_gen_ms.store(gen_ms);
    session->last_generated.store(generated);

    const double tps = gen_ms > 0 ? (static_cast<double>(generated) * 1000.0 / gen_ms) : 0.0;

    // 供 adb logcat -s LlamaJNI 观察 KV 增长趋势，判断是否被上下文上限卡住
    LOGI("generate done: finish=%d, generated=%d, kv_tokens=%zu, n_ctx=%u, "
         "prefill=%lldms, decode=%lldms, tps=%.2f",
         finish_reason, generated, session->kv_tokens.size(),
         llama_n_ctx(session->ctx), prefill_ms, gen_ms, tps);

    finish(finish_reason, generated);
    return generated;
}

} // extern "C"
