#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
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
};

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

void android_log_callback(ggml_log_level level, const char *text, void * /*user_data*/) {
    if (text == nullptr) {
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
Java_com_workbuddy_localmodel_llm_LlamaBridge_nativeBackendInit(JNIEnv * /*env*/, jobject /*thiz*/) {
    llama_log_set(android_log_callback, nullptr);
    llama_backend_init();
    LOGI("llama.cpp backend initialized: %s", llama_version());
}

JNIEXPORT jlong JNICALL
Java_com_workbuddy_localmodel_llm_LlamaBridge_nativeLoadModel(
        JNIEnv *env, jobject /*thiz*/, jstring path, jint n_threads, jint n_ctx,
        jint n_batch, jint n_gpu_layers) {

    const std::string model_path = jstring_to_utf8(env, path);
    if (model_path.empty()) {
        return 0;
    }

    auto *session = new LlamaSession();

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = n_gpu_layers;

    session->model = llama_model_load_from_file(model_path.c_str(), mparams);
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

    LOGI("model loaded: %s (ctx=%u, threads=%d)",
         model_path.c_str(), llama_n_ctx(session->ctx), cparams.n_threads);
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

JNIEXPORT jint JNICALL
Java_com_workbuddy_localmodel_llm_LlamaBridge_nativeContextSize(JNIEnv * /*env*/, jobject /*thiz*/,
                                                               jlong handle) {
    auto *session = reinterpret_cast<LlamaSession *>(handle);
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

    // 3. 清空 KV cache
    llama_memory_clear(llama_get_memory(session->ctx), true);

    // 4. prompt 分批 decode
    const uint32_t n_batch = llama_n_batch(session->ctx);
    for (int32_t i = 0; i < n_tokens; i += static_cast<int32_t>(n_batch)) {
        if (session->stop.load()) {
            finish(FINISH_CANCELLED, 0);
            return 0;
        }
        const int32_t chunk = std::min<int32_t>(static_cast<int32_t>(n_batch), n_tokens - i);
        llama_batch batch = llama_batch_get_one(tokens.data() + i, chunk);
        if (llama_decode(session->ctx, batch) != 0) {
            LOGE("llama_decode(prompt) failed at %d", i);
            finish(FINISH_ERROR, 0);
            return -4;
        }
    }

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
            finish_reason = FINISH_ERROR;
            break;
        }

        if (i + 1 >= gen_limit) {
            finish_reason = FINISH_LENGTH;
        }
    }

    if (!pending.empty()) {
        cb.token(pending);
    }
    llama_sampler_free(smpl);

    finish(finish_reason, generated);
    return generated;
}

} // extern "C"
