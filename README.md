# 数学网课助手（ScreenMemory）

一个 Android 应用：**在后台常驻录制手机最近 30 秒的画面和声音**，屏幕上悬浮一个小球。看数学网课的时候，老师讲到某一步没跟上，点一下小球 —— **按住说话 → 模型结合刚才那 30 秒的板书回答你**。

> 典型用法：老师在推一个公式，你卡在「这一步为什么能直接约掉」；或者板书里冒出个没见过的符号；或者想让他把你的题接着算完 —— 不用截图、不用暂停回拉、不用描述板书内容，点球问一句就行。

---

## 1. 运行效果

| 步骤 | 用户操作 | 应用行为 |
| --- | --- | --- |
| ① | 打开 App 点「启动助手」 | 授权录屏 + 悬浮窗，回到网课 App，后台开始循环录制 |
| ② | 点屏幕上悬浮的「问」小球 | **画面在这一刻冻结**（缓冲区不再前进），随后弹出的面板和键盘不会进入录像；弹出面板并提示已冻结 |
| ③ | **按住**「按住说话」说一句，松手 | 录音 → 转写成文字填进输入框（可修改），也可以直接打字 |
| ④ | 点「发送」 | 把最近 30 秒的板书抽 8 帧（或整段录像直传）+ 你的问题发给多模态模型，答案**流式**逐字显示，公式按 LaTeX 渲染 |
| ⑤ | 继续打字再点「发送」 | **追问**：把之前几轮以纯文本回放给模型，板书沿用第一次那批，不重复上传（省时省钱）；面板上显示完整对话 |
| ⑥ | 点面板右上「新对话」 | 清空上下文，下一问重新附上当前板书 |

模型被要求按「先定位到哪一步 → 给依据（定义/定理/变形）→ 给下一步写法」的顺序讲，公式一律 LaTeX，而不是把板书抄一遍。

> 每段问答都会存到本机 `filesDir/chat_history.json`（最近 30 段、每段最多 20 轮），
> 首页顶部「对话历史」Tab 可以查看全文、也可以清空。

---

## 2. 快速开始

### 2.1 编译

```bash
# 需要 JDK 17+（17 / 21 都验证过）、Android SDK 34
cd ScreenMemory
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

或用 Android Studio 直接 `Open` 这个目录（Gradle 8.7 / AGP 8.5.2 / Kotlin 1.9.24，无需改配置）。

> 已在 **Android Studio 2026.2.1（Rabbit 1，自带 JBR 21）** 上验证：`assembleDebug` 通过。AS 提示升级 AGP / Kotlin / compileSdk 时先别点，原因和长期升级路径见 [`开发指南.md`](开发指南.md) 的 0.5 节。

安装：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> 支持 Android 10（API 29）及以上。

### 2.2 首次配置

设置页按「画面怎么发给模型」拆成**三块互相独立的配置**，切换模式只影响用哪块，值各自保存、互不覆盖。每个输入框下面都有同样的说明（`helperText`），这里再汇总一遍：

#### ① 抽帧图片（默认，OpenAI 兼容）

| 字段 | 含义 | 示例 |
| --- | --- | --- |
| 对话接口 URL | **完整请求地址**，程序不再拼接任何路径，粘进来就用 | `https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions` |
| API Key | 该厂商的密钥，只存本机；以 `Authorization: Bearer` 发出。下面两块留空时会复用它 | `sk-...` |
| 对话模型 | **必须支持图片输入**，否则图片被忽略或直接报错 | `qwen-vl-max`、`glm-4v-plus`、`gpt-4o-mini` |
| 每次发送的画面张数 | 1–20，把窗口内的画面均匀切几刀；越多久越贵 | `8` |

#### ② 视频直传（千问 / 百炼）

| 字段 | 含义 | 示例 |
| --- | --- | --- |
| 视频对话 URL | 完整地址：百炼 OpenAI 兼容入口 | `https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions` |
| 上传凭证 URL | 完整地址：文件上传凭证接口 | `https://dashscope.aliyuncs.com/api/v1/uploads` |
| 百炼 API Key | 留空则复用①的 Key；用阿里云时建议单独填 | `sk-...` |
| 视频模型 | 上传文件时与它绑定，推理必须用同一个 | `qwen-vl-max`、`qwen3.5-omni-plus` |
| 模型能听声音吗 | 自动 / 能听 / 听不到，只影响 prompt 措辞 | 自动（见 2.5） |

#### ③ 语音转写（按住说话，两种模式共用）

| 字段 | 含义 | 示例 |
| --- | --- | --- |
| 优先用系统离线识别 | 默认开启：走 `SpeechRecognizer`（Android 12+ 优先 `createOnDeviceSpeechRecognizer`）。不支持或没结果时自动回退下面的云端接口 | 开 |
| 转写接口 URL | **完整地址**，不再从①推导。需兼容 Whisper 协议 | `https://dashscope.aliyuncs.com/compatible-mode/v1/audio/transcriptions` |
| 转写 API Key | 留空则复用①的 Key | `sk-...` |
| 转写模型 | Whisper 协议下的模型名 | `whisper-1`、`SenseVoiceSmall`、`qwen3-asr-flash` |

> **升级提示**：旧版本存的是 Base URL（程序拼路径）。新版首次启动会自动补全成完整地址（`…/v1` → `…/v1/chat/completions`、`…/v1/audio/transcriptions`），不用手动改。

> 国内可用示例（都是 OpenAI 兼容协议）：
> - 通义千问：`https://dashscope.aliyuncs.com/compatible-mode/v1`，模型 `qwen-vl-max` + 转写 `qwen3-asr-flash`
> - 智谱：`https://open.bigmodel.cn/api/paas/v4`，模型 `glm-4v-plus`
> - 硅基流动：`https://api.siliconflow.cn/v1`，模型 `Qwen/Qwen2-VL-72B-Instruct`，转写 `FunAudioLLM/SenseVoiceSmall`

> **数学网课场景的建议配置**
> - 模型：`qwen-vl-max` / `glm-4v-plus` 这类能看清小字和公式的；`gpt-4o-mini` 在密集板书上容易读错上下标。
> - 画质留「标准」（1080p / q80）：公式里的 $x^{2}$、下标 $a_{n}$ 在「省流」档会明显变糊，「极低」档只适合大号板书。
> - 抽帧张数 8 张够看出推导过程；老师写得飞快、一屏挤三四行的时候可以调到 10–12。
> - 视频直传模式下，如果模型是 `qwen3.5-omni-plus` 这类能听音频的，老师的口述讲解也会进上下文。

### 2.3 启动与授权

点「启动助手」，会依次要 3 个权限，**缺一个都跑不起来**：

1. **麦克风**（录音）
2. **显示在其他应用上层**（悬浮球）—— 会跳到系统设置页，手动打开
3. **屏幕录制**（系统弹窗，点「立即开始」/「允许」）

授权完成后 App 会自动退到后台，你回到原来的应用就能看到悬浮球。

> Android 14+ 每次启动都需要重新确认录屏，这是系统限制，任何 App 都绕不过。

### 2.4 自定义录制参数

「录制参数」卡片里的秒数**可以直接点数字框输入任意整数**，滑块会自动跟到同一位置；反过来拖滑块也会同步回填数字框。（帧数跟着「抽帧图片」那块配置走，见 2.2 ①。）

| 参数 | 范围 | 默认 | 影响 |
| --- | --- | --- | --- |
| 保留最近多少秒 | **5–60 秒** | 30 | 缓冲区窗口长度。越大越能覆盖「刚才发生的事」，内存占用线性增长（标准档 1080p/6Mbps 约 0.75MB/秒，60 秒约 45MB，硬上限 160MB 会自动丢最旧的帧） |
| 画质 | **极低 / 省流 / 标准 / 清晰** | 标准 | 一次决定「录制分辨率 + 码率 + 抽帧尺寸 + JPEG 质量」，见下表。改完要重启助手才生效 |
| 每次发给模型的画面张数 | **1–20 张** | 8 | 越能看出画面怎么变化，请求体积和 token 成本也越高 |
| 视频抽帧频率 | **0.5–4 帧/秒** | 1 | 只在视频直传时生效。**首字延迟跟文件大小无关，跟「时长 × 频率」成正比**：8 秒 @1fps = 8 帧，@2fps = 16 帧。滑块下方会实时显示「N 秒的片段约 M 帧」 |
| 深度思考 | **不传 / 关闭 / 开启** | 不传 | 对应请求体的 `enable_thinking`。qwen3 这类推理型模型默认可能在内部推理十几秒才出字；「不传」兼容所有模型，模型不支持时自动去掉参数重试一次 |

| 档位 | 录制 | H.264 码率 | 抽帧 | 30 秒体积 | 适合 |
| --- | --- | --- | --- | --- | --- |
| 极低 | 480p | 0.5Mbps | 480px / q60 | ~2MB | 弱网、流量极紧；只适合大号板书 |
| 省流 | 720p | 1.2Mbps | 720px / q65 | ~4MB | 视频直传 + 短窗口，流量敏感 |
| **标准**（默认） | 1080p | 6Mbps | 1080px / q80 | ~21MB | UI、聊天记录、网页正文 |
| 清晰 | 1080p | 10Mbps | 1080px / q90 | ~37MB | 表格、代码、报错码、小字 |

> 体积这两列是**标称值**（码率 × 时长）。硬编码器如果只能跑 VBR，实际会超出设定值 1.5–2.5 倍（屏幕内容变化大时更明显）；支持 CBR 的机型代码会强制 CBR。想看真实值就看 `Timing` 日志里的 `片段=…MB/…s/…Mbps`。
>
> 省流档从 2.5 降到 1.2 Mbps：录的是屏幕内容（大片纯色 + 文字），熵远低于实拍，720 短边下 1.2 Mbps 讲稿/PPT/板书依然清楚。
>
> 「极低」和「省流」会优先尝试软件 AVC 编码器、显式设置 VBR，并通过 `max-fps-to-encoder` 把输入限制在最多 10fps；软件路径初始化失败时自动回退硬件编码器。实际编码器、目标码率、实测码率和帧率会写入 `Timing` 日志。

为什么绑成档位而不是四个独立滑块：录制分辨率决定抽帧的像素上限，码率决定解码后有多少压缩噪点，抽帧尺寸和 JPEG 质量决定第二次压缩的损失——四个值拆开很容易配出「1080p 录制 + q50 抽帧」这种自相矛盾的组合。

> 旧版默认是 720p / 3Mbps / 抽帧 720px q65，也就是**两次有损压缩叠加**，中文小字给到模型时约 16px 且带振铃，容易认错 `0/O`、`8/B`。新版默认提升到 1080p / q80。

**运行中改也生效（部分）**：点「启动助手」后回到设置页改时长或帧数，离开页面时会通过 `ACTION_UPDATE` 推给正在跑的服务 —— 窗口时长立刻按新值裁剪（变小时旧帧会在下一帧到达时被裁掉），帧数在下一次发送时读取。悬浮面板标题「最近 N 秒」和常驻通知同步刷新。

**画质例外**：分辨率、码率在编码器创建时就锁定了，改画质必须重新启动助手；这时会弹一句提示，不会让你以为已经生效。

**视频抽帧频率和深度思考例外**：这两项是每次请求现读的，改完下一问就生效，不用重启。

### 2.5 视频直传模式（仅千问）

默认是「抽帧图片」：把最近 N 秒的画面抽成 1–20 张 JPEG，塞进任意 OpenAI 兼容的 `chat/completions`（这模式不挑厂商）。

切成「视频直传（千问）」后，走的是**阿里云百炼的本地文件上传** —— 不用你找图床，也不用把十几 MB 的 base64 塞进请求体。两步流程对应两份官方文档：

```
① GET  {上传凭证 URL}?action=getPolicy&model=qwen-vl-max
   Authorization: Bearer sk-xxx
   ← {"data":{"upload_host":"…","upload_dir":"…","policy":"…","signature":"…",
              "oss_access_key_id":"…","x_oss_object_acl":"private",
              "x_oss_forbid_overwrite":"true","max_file_size_mb":100}}

② POST {upload_host}   multipart/form-data（file 必须是最后一个字段）
   OSSAccessKeyId / policy / Signature / key / x-oss-object-acl /
   x-oss-forbid-overwrite / success_action_status=200 / file=@clip.mp4
   → oss://dashscope-instant/xxx/…/clip.mp4（48 小时有效）

③ POST {视频对话 URL}
   Authorization: Bearer sk-xxx
   X-DashScope-OssResourceResolve: enable        ← 少了这个头，oss:// 解析不了
   {"model":"qwen-vl-max","messages":[
      {"role":"system","content":"…"},
      {"role":"user","content":[
         {"type":"text","text":"你刚才问的话"},
         {"type":"video_url","video_url":{"url":"oss://…"},"fps":1}]}]}
   ← choices[0].message.content
```

> 文档出处：[上传本地文件获取临时 URL](https://help.aliyun.com/zh/model-studio/get-temporary-file-url)、[视觉理解（视频）](https://docs.bailian.console.aliyun.com/zh/model-studio/vision)。
> 注意视频走的是 **OpenAI 兼容入口**，不是 `multimodal-generation/generation`；`fps` 放在 content 元素这一层，取值范围 [0.1, 10]，默认 2。
>
> **为什么设成 1 fps**：服务端按这个频率把视频拆成图片喂给视觉编码器，8 秒 × 2fps = 16 帧 720p ≈ 十几 k token 的 prefill。**首字延迟跟文件大小无关，跟「时长 × fps × 分辨率」成正比** —— 1.25MB 的视频照样慢，就是这个原因。网课板书基本静态，1 fps 足够看出推导过程。

**和抽帧的差别**

| | 抽帧图片 | 视频直传（千问） |
| --- | --- | --- |
| 协议 | `image_url`（OpenAI 官方标准） | `video_url` + 百炼文件上传（oss:// 引用） |
| 适用厂商 | 所有视觉模型 | 仅千问（百炼） |
| 谁在抽帧 | 客户端，你定张数和时刻 | 服务端按 `fps: 2` 抽 |
| 上传体积 | 0.3–2MB | 20MB 左右（30 秒 / 标准档 6Mbps） |
| 耗时 | 1–3 秒回 | 多 3–10 秒上传+转码 |
| 音轨 | 不涉及 | 传上去了；模型能不能用取决于型号（omni 能，vl 不能） |

**关于声音（按「有没有音轨 × 模型能不能听」四种情况分别处理）**

不是所有千问模型都只听画面——`qwen3.5-omni-plus` 这类 omni 型号能把音轨一起理解。所以 prompt 不是写死的，而是按实际情况生成：

| 片段有音轨 | 模型能听声音 | prompt 里的措辞 | 状态栏标注 |
| --- | --- | --- | --- |
| ❌ | 任意 | 「这段录像没有声音轨，你听不到任何声音，不要臆测」 | `无音轨` + 橙色警告条 |
| ✅ | ✅ | 「你可以听到画面里的声音，把说话内容/音效一并作为依据」 | `含音轨，模型会参考声音` |
| ✅ | ❌ | 「带声音轨但当前模型无法解析音频，不要臆测」 | `含音轨，但 qwen-vl-max 只解析画面` |

**「模型能听声音」三态开关**

型号名不一定靠谱（别名、代理转发、新命名都会让名字变味），所以设置页给了手动开关，默认自动：

| 选项 | 行为 | 什么时候用 |
| --- | --- | --- |
| **自动**（默认） | 型号名含 `omni` → 能听；否则听不到 | 常规情况，填什么型号按什么判断 |
| **能听** | 强制视为能解析音频，prompt 让模型把声音当依据 | 模型别名、代理重命名、或新出的音频型号不带 `omni` |
| **听不到** | 强制视为只能看画面 | 名字带 omni 但实际不支持，或你只想让它专注画面 |

开关下方会实时显示「当前：自动判定：能听声音（型号含 omni）」这类文案，把自动判定的结果摊开给你看，不用猜当前到底走了哪条分支。

**注意：这个开关只改 prompt 措辞和状态栏标注，不改上传参数** —— 只要片段有音轨就会传上去，模型能不能利用是它自己的事。所以它永远不会让请求失败，只会影响模型"被允许"回答什么。

- **静音检测**：片段导出时会算音频峰值（16bit，阈值约 -60dBFS）。全静音时不再谎报「含音轨」，而是弹橙色提示，并给出两个可能的原因——目标 App 禁止内录，或当时确实没在放声音。打开面板时还会先探一下最近 5 秒，提问前就能看到「当前没有采到声音」。
- 三套 prompt 都保留了「不要编造没听到的内容」这句——多模态模型最容易被问题带着瞎编，这条必须留着。

**三个实现细节**

- **字段名全部取自凭证，不硬编码**：`OSSAccessKeyId`←`oss_access_key_id`、`x-oss-object-acl`←`x_oss_object_acl`、`x-oss-forbid-overwrite`←`x_oss_forbid_overwrite`。OSS 表单是按 policy 校验的，自己瞎填 acl 值会被 403 拒掉（`Invalid according to Policy`）。
- **按文档的 7MB 规则自动降级**：文档明确「小于 7MB 可用 Base64，7MB～100MB 只能公网 URL / SDK 本地路径」。所以先走 oss://；如果服务端仍读不到文件、且片段小于 7MB，就自动改成 `data:video/mp4;base64,…` 再试一次；超过 7MB 则直接提示你缩短时长，不做无用的重试。
- **上传带进度**：文件分片写 socket，实时回调百分比，面板显示 `正在上传视频 42%`；`max_file_size_mb` 超限会在上传前就拦下来。落库的快照 48 小时后由百炼自动清理。

---

## 3. 代码结构

```
app/src/main/java/com/screenmemory/app/
├── MainActivity.kt                  权限流程 + 配置界面（顶部 Tab：主功能 / 对话历史）
├── HistoryDetailActivity.kt         单段对话的完整问答，可继续追问
├── Prefs.kt                         配置存取
├── chat/
│   └── ChatStore.kt                 多轮对话的本地记录（ChatTurn / Conversation / JSON 落盘）
├── service/
│   └── CaptureService.kt            前台服务：持有 MediaProjection、串联录制/悬浮窗/网络
├── capture/
│   ├── ScreenRingRecorder.kt        核心：H.264 + AAC 编码 + 环形缓冲（窗口可热改）+ 导出 MP4
│   ├── FrameExtractor.kt            从 MP4 等间隔抽帧 → base64 JPEG
│   └── MediaProbe.kt                探测片段有没有音轨（缺音轨时提醒用户）
├── audio/
│   ├── AudioHub.kt                  单路 AudioRecord，PCM 广播给多个订阅者
│   ├── UtteranceRecorder.kt         「按住说话」录音（独立一路麦克风）
│   └── Wav.kt                       PCM → WAV
├── speech/
│   └── SpeechTranscriber.kt         系统语音识别（离线优先，失败回退云端）
├── overlay/
│   └── OverlayController.kt         悬浮球 + 提问面板（纯代码构建视图）
└── net/
    ├── LlmClient.kt                 Whisper 转写 + 多模态 chat/completions（抽帧模式）
    └── QwenVideoClient.kt           百炼上传凭证 → OSS 上传 → 多模态 generation（视频直传）
```

### 数据流

```
MediaProjection ──► VirtualDisplay ──► MediaCodec(H.264) ─┐
                                                          ├─► 内存环形缓冲(最近 N 秒) ──► MP4 ─┬─► 抽帧 ──► base64 ──► /chat/completions
AudioRecord ──► AudioHub ──► MediaCodec(AAC) ─────────────┘                                    │
                  │                                                                           └─► 直传 ──► getPolicy ──► OSS ──► oss:// ──► multimodal-generation
                  └──► UtteranceRecorder ──► WAV ──► /audio/transcriptions ──► 文字 ───────────────┘
```

两条上传路径二选一（设置里「画面怎么发给模型」）：**抽帧图片**（`image_url`，任意 OpenAI 兼容端点）或**视频直传（千问）**（百炼上传 + `oss://` 引用）。

---

## 4. 关键实现说明（为什么这么写）

**① 用 MediaCodec + 环形缓冲，而不是 MediaRecorder 分段录制。**
`MediaRecorder` 只能写文件、不能取内存缓冲。这里直接把 VirtualDisplay 的画面喂给 H.264 编码器的输入 Surface，编码后的帧（含时间戳、是否关键帧）塞进 `ArrayDeque`，按时间窗口滚动裁剪：只保留「窗口 + 1.5 秒余量」，余量用于保证导出时能找到关键帧起点，总内存上限 96MB。提问时立刻把缓冲区的帧重新封装成一个 MP4 —— 编码线程全程不中断，所以任何时候都能取到「刚刚过去」的 30 秒。

**② 导出时对齐关键帧。**
H.264 不能从任意帧开始解码，所以导出会向前找到最近的一个 IDR 帧（`KEY_I_FRAME_INTERVAL = 1`，即每秒一个），最多损失约 1 秒画面。音视频用同一套 `System.nanoTime()` 时钟打时间戳，封装时统一减去起点，保证口型对得上。

**③ 画面默认抽帧，可选视频直传。**

- **抽帧（默认）**：主流多模态接口对视频支持有限、上传慢、还贵。用 `MediaMetadataRetriever` 从刚导出的 MP4 里等间隔抽帧（**1–20 帧可自定义**，默认 8），再按画质档位缩到 1080px / JPEG q80（标准档），base64 塞进 `image_url`。8 帧约 1.5MB，能看清小字，延迟和成本也可控。
- **视频直传（仅千问）**：`QwenVideoClient` 走百炼三步——取上传凭证 → 传 OSS 临时空间拿 `oss://` → 调多模态 generation（header 带 `X-DashScope-OssResourceResolve: enable`）。整段 MP4 含音轨，服务端按 `fps: 2` 抽帧。
- 码率跟画质档位走（极低 0.5 / 省流 1.2 / 标准 6 / 清晰 10 Mbps），**不再因为“怕超过 base64 上限”而降码率**——那条限制只在 base64 降级路径上才存在。编码器支持 CBR 时强制 CBR，否则 VBR 会超发。详见 §4⑤。
- 两种模式共用同一个环形缓冲和同一个 MP4，切换只影响「打包好之后怎么发」，不影响录制。

**④ 音频：只采应用内声音，并且会检测「到底有没有声音」。**
片段音源固定走 `AudioPlaybackCaptureConfiguration`（回放捕获），不再提供麦克风选项——录屏场景下麦克风只会把环境噪声混进去。代价是两类声音天然采不到：`USAGE_VOICE_COMMUNICATION`（通话、语音消息）永远不允许捕获；带版权内容的 App 会通过 `setAllowedCapturePolicy` 主动禁止。

关键坑：**被禁止时不会报错，`AudioRecord` 正常初始化、只是把全 0 当数据给你**。所以早期版本会显示「含音轨」，等于骗用户。现在 `ScreenRingRecorder` 在喂编码器时顺带记录每个音频块的峰值，导出时取整段最大值：

- 峰值 > 32（约 -60dBFS）→ 判定有声音；
- 全静音 → `hasAudio = false`，面板弹橙色提示，文案同时给出「App 屏蔽内录」和「当时确实没放声音」两种可能（这两种情况在 PCM 层面无法区分，不能武断下结论）；
- 打开面板时额外探一次最近 5 秒，让用户在开口提问前就知道没声音。

「按住说话」仍需要麦克风，它在回放捕获之外单独开一路 `AudioRecord(MIC)`，与片段音轨互不影响。

**⑤ 语音转写支持本地识别。**
「按住说话」默认先走系统 `SpeechRecognizer`：Android 12+ 且设备支持时用 `createOnDeviceSpeechRecognizer()`（明确不联网），否则用普通实例加 `EXTRA_PREFER_OFFLINE` 偏向本地引擎。失败（引擎不可用、没装离线包、识别不出结果）就本回合切回云端 Whisper 协议，下一轮再试本地。

两个实现约束：

- **麦克风互斥**：`SpeechRecognizer` 自己开麦，所以不能同时用 `UtteranceRecorder` 录音，两者按可用性二选一；
- **包可见性**：Android 11+ 必须在 Manifest 里声明 `<queries><intent><action android:name="android.speech.RecognitionService"/></intent></queries>`，否则 `isRecognitionAvailable()` 会误报 false，功能等于没开。

另外，本地识别先结束时可能拿不到结果：`SpeechTranscriber` 里用 `finished` + `finishedResult` 兜住"松手前会话已结束"的情况，否则面板会一直停在「正在识别…」。

**⑥ 悬浮窗里的输入框能弹出键盘。**
悬浮窗默认 `FLAG_NOT_FOCUSABLE`，键盘弹不出来。提问面板改成可聚焦窗口 + `SOFT_INPUT_ADJUST_RESIZE`，同时在面板显示时不主动抢焦点，避免一点开就弹键盘挡住内容。悬浮球用 `FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_NO_LIMITS`，保证不拦截下层 App 的触摸，只在小球自身上响应拖动/点击。

**⑦ 点悬浮球就冻结画面。**
不然会有个很尴尬的问题：提问面板自己也在屏幕上，用户打字的过程会被录进去，模型看到的是「一个正在打字的输入框」而不是原始界面，关键词还被自己的问题占满了。

第一版做的是「记下点按时刻的时间戳，导出时过滤掉之后的帧」，第二轮就被真机打回来了：**屏幕静止时编码器可能十几秒不产帧**，缓冲区里「最新一帧」的时间戳远早于现在；等面板出现、新帧一到，裁剪逻辑按新的最新时间戳把旧帧全清掉，于是第二次打开面板立刻报「截断点已滚出缓冲区」。

现在的做法是**冻结缓冲区本身**：`freezeWindow()` 把当前窗口锁住并停止接收新帧（画面在冻结点定格），导出时直接取冻结内容。面板开多久都不会失效，内存也不再增长。

| 情况 | 行为 |
| --- | --- |
| 面板保持打开很久 | 冻结的是同一批帧，随时发送都有效 |
| 面板收起后再点一次球 | `unfreezeWindow()` 解冻，重新冻结拿到新一轮上下文 |
| 面板内连续追问 | 复用同一份冻结帧，几次提问看的是同一段上下文 |

> 已知残留：悬浮球本身（56dp 小圆）无法从录制中排除，会一直出现在画面角落。面板和键盘是干净的，球不遮挡主要内容，暂不做处理（把球设成 `FLAG_SECURE` 在部分机型会让整个录屏变黑，风险更大）。

**⑦.5 追问（多轮对话）与本地历史。**
面板里的「发送」可以连发很多次，每次都是对**同一批冻结帧**的追问。关键取舍是**媒体只传一次**：

- 首轮：抽帧/视频 + 问题，走原来的链路；
- 追问：**不重新抽帧、不重新上传**，只用 [LlmClient.ask()](app/src/main/java/com/screenmemory/app/net/LlmClient.kt) /
  [QwenVideoClient.ask()](app/src/main/java/com/screenmemory/app/net/QwenVideoClient.kt) 的 `history` 参数把前面几轮
  按 `user` / `assistant` 消息回放（纯文本），并把当前问题附在最后。
  视频直传模式下追问传 `video = null`，直接跳过「取凭证 + 传 OSS」那 20MB。

为什么这么做：一轮追问如果重传 8 张图（约 3 MB）或整段视频（约 20 MB），延迟和费用都会失控；
而追问通常是「为什么这么说」「第二步呢」这类，靠文字历史就够了。提示词也分了两套：
`systemPrompt()` / `videoPrompt()` 用于首轮，`followUpSystemPrompt()` / `videoFollowUpPrompt()` 明确告诉模型
「这一轮不附图，画面沿用上文」，避免它以为又给了一批新截图。

历史落盘在 [ChatStore.kt](app/src/main/java/com/screenmemory/app/chat/ChatStore.kt)：
`filesDir/chat_history.json`，一段对话一条记录（id = 冻结时刻），只留最近 30 段、每段 20 轮；
写盘是「临时文件 + rename」，进程被杀也不会留下半截 JSON。

**看历史在 App 里，和主功能平级**：首页顶部是两个 Tab —— **主功能 / 对话历史**，平级切换，悬浮窗里不承担历史浏览。

- 「主功能」= 运行状态 + 全部录制/接口配置；
- 「对话历史」= 每段一张卡片（时间 · 轮数 · 首个问题 · 最后回答摘要），右上角「清空」；
- 点一段进 [HistoryDetailActivity](app/src/main/java/com/screenmemory/app/HistoryDetailActivity.kt) 看全文并可以**接着聊**。

**历史对话可以接着聊**：[HistoryDetailActivity](app/src/main/java/com/screenmemory/app/HistoryDetailActivity.kt) 底部有输入框，
新问题会带着这段对话的历史（最近 10 轮）发给模型，回答流式写入、完成后追加到同一段并落盘，回到列表能看到轮数变了。

有两点是刻意的限制：

- **不附图**：原始抽帧/视频本来就没落盘，历史里的追问只能是纯文字，所以提示词里写明了「必须看画面才能回答时，请让用户回到悬浮球重新问」，不让模型瞎猜；
- **按当前模式选端点**：视频模式走 `QwenVideoClient`（`video = null`，跳过上传），抽帧模式走 `LlmClient`，两边都用你在设置里填的 key/URL。

**⑧ 流式输出 + Markdown/公式渲染。**
`stream: true` 后按 SSE 逐行读 `choices[0].delta.content`（`Sse.consume()` 两家共用），刷新做 120ms 节流。

答案面板用 [Markwon](https://github.com/noties/Markwon) 渲染 Markdown：标题、列表、加粗、行内代码、表格、删除线，数学公式走 `ext-latex`（JLatexMath）。

**提示词不再规定写法**：早期版本在提示词里硬性要求「标题用 ###、列表用 -、公式用 $...$」，本质是让模型迁就渲染器的实现缺陷。现在只写「用 Markdown 正常排版」，兼容交给渲染层（`Latex.normalize()` 同时认 `$...$`、`$$...$$`、`\(...\)`、`\[...\]`）。提示词里只保留两条渲染器确实做不到的约束：不要 HTML 标签、不要 ASCII 线框表格。

两个必须绕开的坑：

1. **ext-latex 只认 `$$...$$`，不支持单美元 `$...$`**（反编译 `JLatexMathInlineProcessor`：`Pattern.compile("(\\${2})([\\s\\S]+?)\\1")`）。模型习惯写 `$x^2$`，所以要在渲染前做一次归一化：`Latex.normalize()` 把成对的单美元改成双美元，并跳过含中文或没有数学符号的内容（否则「$5 和 $10」会被拼成一段永不闭合的公式，把后面整段吞掉）。
2. **开行内公式还要两个前置**：`inlinesEnabled(true)`；以及 `.usePlugin(MarkwonInlineParserPlugin.create())`，否则插件的 `configure()` 里 `registry.require(MarkwonInlineParserPlugin.class)` 会抛 `IllegalStateException`，整段退化成源码。
2. **流式过程中公式是半截的**（`$\frac{1` 还没闭合），JLatexMath 在 `draw()` 阶段解析残缺 LaTeX 会抛异常，而 draw 里的异常 catch 不住，直接崩。

所以分两段：

| 阶段 | 渲染方式 |
| --- | --- |
| 流式输出中（每 120ms） | `Preview.plain()` 抹掉记号后按纯文本显示，公式显示为源码（`\frac{1}{2}`） |
| 输出结束 | Markwon 一次性渲染，公式变成真正的排版 |

视频直传模式下如果已经吐出文字再失败，**不会**降级重试 base64，否则用户会看到两份答案。

---

## 5. 已知限制

- **会被系统杀**：国产 ROM 的省电策略很激进，长期后台需要手动把 App 加入「自启动/后台运行白名单」，并保持常驻通知不被清理。前台服务 + 常驻通知已经是标准做法。
- **旋转屏幕以分段保存**：检测到横竖屏变化后会封存上一方向的缓冲，再重建视频编码器，并对现有 `VirtualDisplay` 执行 `resize + setSurface`；音频编码器不重启。抽帧模式会按时间顺序合并旋转前后的截图，视频直传因 MP4 不能混两种尺寸，只取最新方向的片段并提示实际可用秒数。编码器重建期间可能有很短的画面缺口。
- **旋转时悬浮球的位置**：窗口坐标是绝对像素，旋转后旧坐标可能落到屏幕外。现在监听 `onConfigurationChanged` 重新夹取，并加了一个 2 秒一次的兜底巡检（部分 ROM 在后台不派发配置变更），顺带处理个别 ROM 旋转时会摘掉悬浮窗、需要重新 `addView` 的情况。
- **内部音频捕获不一定成功**：取决于目标 App 是否允许（如 Netflix、部分音乐 App 会返回静音）。现在会做振幅检测并明确提示，不会再谎报「有声音」；但检测不出「为什么没声音」，文案里两种可能都列了。
- **转写准确度取决于所选 ASR 服务**：面板里转出的文字可直接编辑，就是为了兜底。
- **成本**：每次提问 = 一次 ASR + 一次多模态请求（抽帧 8 张图，或直传 3–12MB 视频）。默认 30 秒窗口、8 帧是延迟/成本/信息量的折中，两个数值都能自己填。
- **视频直传只能配千问**：走的是百炼专有的「上传凭证 + OSS 临时地址」接口，不是通用协议。换别家要么改 `QwenVideoClient`，要么用抽帧模式。
- **声音能不能用，取决于型号和开关**：`qwen-vl` 系列只看画面，`qwen3.5-omni-plus` 这类 omni 型号能理解音轨。默认按型号名自动判断，也可以在设置里手动指定（三态开关）；prompt 和状态栏都会跟着变，片段没录到声音时还会弹橙色提示。
- **Markdown 表格在窄面板里会挤**：Markwon 不提供横向滚动，列数多的表格会被压扁。日常问答够用，真要看宽表格建议让模型改用要点罗列。
- **APK 体积**：Markwon + JLatexMath 的字体让包从 13MB 涨到 22MB。公式渲染是按需的，但字体没法按需拆。
- **本地识别看机型**：`SpeechRecognizer` 在部分国产 ROM 上没有可用引擎（`isRecognitionAvailable()` 返回 false），或没装中文离线包。这两种情况都会自动回退云端，面板状态栏会提示「已切回云端」。
- **不做本地推理**：画面理解仍全部走你配置的云端接口，未内置任何密钥；只有「按住说话」的语音转写可以完全离线。

---

## 6. 隐私与合规

- 画面只在**内存**里循环覆盖，不落盘、不后台偷偷上传；只有你点「发送」才会抽帧或上传视频片段。
- 片段文件默认保存在 `Android/data/com.screenmemory.app/files/Movies/`，可随 App 卸载清除，也可以在设置里关掉保存。
- 录屏、录音都是系统级敏感权限，**只能用于你本人设备上的本人场景**。用他人手机、录他人内容前请取得明确同意；不同地区的法律对录音录屏有不同要求，请自行确认。

### 6.1 什么时候会写文件？写在哪？

| 动作 | 会不会产生文件 | 位置 |
| --- | --- | --- |
| 开始录制、连续缓冲 | ❌ 只在内存（编码帧环形缓冲） | — |
| **点悬浮球冻结画面** | ❌ 不产生任何文件，只是把内存里的窗口锁住 | — |
| 点「发送」 | ✅ 生成一个 MP4 | `Android/data/com.screenmemory.app/cache/clips/clip_<时间戳>.mp4` |
| 开启了「另存片段」 | ✅ 再复制一份 | `Android/data/com.screenmemory.app/files/Movies/clip_<时间戳>.mp4` |
| 抽帧 | ❌ JPEG 只在内存里 base64 编码后发出，不落盘 | — |

缓存目录里的片段**只保留最近 3 个**，更早的会在下次发送时自动删除（每个约 20MB，不清会吃掉几百 MB）。设成「不另存」时，缓存里那份也会随这轮清理消失。

---

## 7. 可以继续扩展的方向

1. **听老师的声音**：现在抽帧模式只有板书；可以「音轨单独跑 ASR → 文字拼进 prompt」，让不支持音频的 vl 型号也能结合老师的讲解（视频直传 + omni 型号已经能做到）。
2. **公式取词**：接 `AccessibilityService` 或公式 OCR，把板书里的符号变成文字再给模型，比纯看图更稳（尤其是 $\frac{}{}$ 这类分层结构）。
3. **错题本**：把「提问记录」里带公式的问答按知识点自动归类，生成可复习的错题卡片。
4. **本地转写**：接 `whisper.cpp` / sherpa-onnx，离线也能转写，省一次网络往返。
5. **按知识点追问**：点一段历史后，让模型顺着这个知识点继续出练习，「再给我两道同类题」。
6. **片段回看**：在 App 内做一个最近片段的列表和播放器，配合提问定位到具体某一帧。
