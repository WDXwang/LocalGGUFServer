# LocalGGUFServer — 安卓本地 GGUF 模型服务

在 Android 手机上直接加载 GGUF 模型进行推理，并通过局域网开放 **OpenAI 兼容 HTTP API**，供 PC 版 WorkBuddy、OpenAI SDK、curl 等客户端调用。

## 功能

- **本地推理**：内置 llama.cpp（JNI），纯 CPU 推理，无需联网。
- **GGUF 导入**：通过系统文件选择器导入 `.gguf` 模型，文件复制到应用私有目录。
- **对话界面**：Compose 聊天页，支持流式输出与中途停止。
- **OpenAI 兼容 API**：
  - `GET /health`
  - `GET /v1/models`
  - `POST /v1/chat/completions`（`stream=true` 走 SSE）
  - `POST /v1/completions`（`stream=true` 走 SSE）
- **鉴权**：可选 Bearer API Key（留空则不鉴权）。
- **后台运行**：前台服务 + 常驻通知，App 退到后台后接口仍可用。
- **CORS**：已放开跨域，方便浏览器/本地工具直接调用。

## 架构

```
┌──────────────────────────────────────────────┐
│                  Android App                 │
│                                              │
│  Compose UI ──┐                              │
│               ├── LlamaEngine（单例 + Mutex）│
│  HTTP Server ─┘         │                    │
│  (NanoHTTPD)            ▼                    │
│                    LlamaBridge (JNI)         │
│                         │                    │
│                    llama.cpp / ggml          │
└──────────────────────────────────────────────┘
              ▲
              │ http://<手机IP>:8080/v1
        PC WorkBuddy / OpenAI SDK
```

UI 聊天与 HTTP 请求共享同一个模型实例，通过 `Mutex` 串行化，避免 llama.cpp context 并发访问。

## 目录结构

```
LocalGGUFServer/
├── app/src/main/
│   ├── cpp/
│   │   ├── CMakeLists.txt      # 精简构建 llama.cpp
│   │   └── llama_jni.cpp       # JNI 推理实现
│   ├── java/com/workbuddy/localmodel/
│   │   ├── llm/                # LlamaBridge / LlamaEngine / 数据类型
│   │   ├── model/              # 模型导入、设置存储
│   │   ├── server/             # OpenAI 兼容 HTTP 服务
│   │   ├── service/            # 前台服务
│   │   ├── ui/                 # Compose 页面
│   │   └── MainActivity.kt
│   ├── res/
│   └── AndroidManifest.xml
├── scripts/setup_llama_cpp.sh  # 拉取固定版本 llama.cpp
└── README.md
```

## 环境要求

| 组件 | 版本 |
| --- | --- |
| Android Studio | Ladybug 及以上 |
| JDK | 17 |
| Android SDK | compileSdk / targetSdk 35 |
| NDK | `27.2.12479018` |
| CMake | `3.22.1` |
| 最低系统 | Android 8.0（API 26） |
| ABI | arm64-v8a、x86_64 |

## 构建

1. 拉取 llama.cpp 源码（固定 commit，保证 JNI 接口匹配）：

```bash
cd LocalGGUFServer
bash scripts/setup_llama_cpp.sh
```

2. 在 Android Studio 的 **SDK Manager → SDK Tools** 中安装：
   - NDK (Side by side) `27.2.12479018`
   - CMake `3.22.1`

3. 构建 APK：

```bash
# macOS / Linux
./gradlew assembleDebug

# Windows
gradlew.bat assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

直接安装：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> 首次 Gradle Sync 会下载 AGP、Compose 等依赖，需要网络。
> `app/src/main/cpp/llama.cpp/` 已加入 `.gitignore`，不会入库。

## 使用

1. **导入模型**：打开 App →「模型」页 →「导入 GGUF」，选择手机中的 `.gguf` 文件。文件会被复制到应用私有目录（`/data/data/com.workbuddy.localmodel/files/models`）。
2. **加载模型**：选择模型并设置上下文/线程/Batch，点击「加载模型」。建议：
   - 手机内存 8GB：上下文 2048~4096；
   - 手机内存 12GB 及以上：可用 4096~8192；
   - 线程数一般设为 4~6（大核数量），过多反而变慢。
3. **启动服务**：「接口」页设置端口（默认 `8080`）与 API Key（可留空），点击「启动服务」。
4. **在 PC 上调用**：手机与 PC 连接同一 Wi-Fi/热点，使用页面显示的 `http://<手机IP>:8080/v1`。

> 如果 PC 无法连接：确认未开启路由器「AP 隔离/客户端隔离」，并检查 PC 防火墙。

## API 说明

Base URL：`http://<手机IP>:<端口>/v1`

鉴权：设置 API Key 后，请求需携带

```
Authorization: Bearer <你的 API Key>
```

或

```
X-Api-Key: <你的 API Key>
```

### GET /health

无需鉴权，用于探活。

```bash
curl http://192.168.1.20:8080/health
```

```json
{"status":"ok","loaded":true,"model":"Qwen2.5-1.5B-Instruct-Q4_K_M","object":"local-model.health"}
```

### GET /v1/models

```bash
curl http://192.168.1.20:8080/v1/models
```

### POST /v1/chat/completions

非流式：

```bash
curl http://192.168.1.20:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer sk-local" \
  -d '{
    "model": "local-model",
    "messages": [
      {"role": "system", "content": "你是一个简洁的助手。"},
      {"role": "user", "content": "你好，介绍一下你自己"}
    ],
    "max_tokens": 256,
    "temperature": 0.7,
    "stream": false
  }'
```

流式（SSE）：

```bash
curl -N http://192.168.1.20:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "local-model",
    "messages": [{"role": "user", "content": "写一首关于秋天的短诗"}],
    "stream": true
  }'
```

返回为标准 `data: {...}` 分块，最后以 `data: [DONE]` 结束。

支持的采样参数：`max_tokens` / `max_completion_tokens`、`temperature`、`top_p`、`top_k`、`min_p`、`repeat_penalty`、`seed`。

### POST /v1/completions

```bash
curl http://192.168.1.20:8080/v1/completions \
  -H "Content-Type: application/json" \
  -d '{"prompt":"从前有座山，","max_tokens":128,"stream":false}'
```

## 在 WorkBuddy / OpenAI SDK 中配置

| 配置项 | 值 |
| --- | --- |
| Base URL | `http://<手机IP>:8080/v1` |
| API Key | App 中设置的 Key；未设置可填任意非空字符串 |
| Model | App 内当前加载的模型名，或直接填 `local-model` |

Python OpenAI SDK 示例：

```python
from openai import OpenAI

client = OpenAI(
    base_url="http://192.168.1.20:8080/v1",
    api_key="sk-local",
)

resp = client.chat.completions.create(
    model="local-model",
    messages=[{"role": "user", "content": "你好"}],
    stream=True,
)
for chunk in resp:
    print(chunk.choices[0].delta.content or "", end="", flush=True)
```

## 已知限制

- **仅 CPU 推理**：当前构建关闭了 Vulkan/OpenCL，GPU 层数设置不起作用。
- **每轮全量 prefill**：请求开始时会清空 KV cache，多轮对话不会复用历史 KV，首 token 延迟随上下文增长。
- **单请求串行**：同一时间只处理一个生成任务；并发请求会排队/返回 `已有生成任务正在进行`。
- **无 HTTPS**：接口为明文 HTTP，仅建议在可信局域网内使用。
- **上下文截断**：prompt 超出上下文时从头部截断，并尽量保留 BOS。
- **聊天模板**：优先使用模型内置 chat template；不支持的模板会回退到 ChatML，效果可能下降。
- **客户端断开不会立即停止推理**：生成会在当前请求结束后释放。

## 性能建议

- 选择量化模型：`Q4_K_M` / `Q5_K_M` 在体积与质量间较均衡。
- 小内存手机优先 0.5B~3B 参数模型；7B 建议 12GB 以上内存。
- 线程数设为性能核数量，`batchSize` 保持 512 即可。
- 上下文越大，内存占用与首 token 延迟越高，按需设置。

## 常见问题

**端口启动失败**
端口被占用或无权限。换一个 1024~65535 的端口重试。

**PC 访问超时**
确认手机与 PC 同一网段、手机未锁屏断网、路由器未开启客户端隔离；可先用手机浏览器访问 `http://127.0.0.1:8080/health` 自测。

**模型加载失败**
优先确认文件是完整 GGUF；其次检查剩余内存是否足够（模型体积 + KV cache）。可降低上下文或换更小的量化模型。

**接口返回 503**
App 内尚未加载模型。先到「模型」页加载，再调用接口。

## 许可证

本项目代码仅供学习与自用。llama.cpp / ggml 遵循其上游 MIT 许可证；使用的模型权重请遵守对应模型许可。
