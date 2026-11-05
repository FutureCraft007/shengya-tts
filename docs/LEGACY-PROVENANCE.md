# 来源与实现范围

0.4.0 在安卓层新增六个非导出的私有推理进程及绑定式 Messenger 协议，未改动上游 C++ 推理子模块。主进程持久化任务、音色查询及历史，工作进程只处理独立推理和音频文件；进度/结果带任务 ID，不通过 Binder 传输大音频数组。本机监听仍仅由用户显式启动。稿好了在既有配音表单和调度器中开放千问 1～6 路配置，配音缓存身份不包含并发数，已有素材不因配置改变而失效。

0.3.1 修复根标签导航：云端克隆统一切换音色库根标签，恢复旧版导航栈时回到用户点击的根页面。新增 Navigation Compose/Robolectric 回归，已在修复前复现 cloud 实际恢复为 voices；未改动云端协议、数据库、推理或服务启动方式。

0.3.0 接通原有 JNI 完整 ICL 提示提取和合成入口，新增保留数据的数据库 2→3 迁移、本地逐字稿编辑、云端样音与生成文案绑定。本机 HTTP 服务由用户手动启动。稿好了联动复用其既有的原生请求接口与凭据存储。

- 安卓上游：`https://github.com/Danmoreng/qwen3-tts-android`。原 LICENSE 保留。
- 原生推理：上游固定子模块 `Danmoreng/qwen3-tts.cpp`，提交 `4562731dc612cb87b4cd1eedac275a17d1078773`；其 GGML 固定为 `eced84c86f8b012c752c016f7fe789adea168e1e`。本次没有改动推理子模块源码。
- 模型：`Serveurperso/Qwen3-TTS-GGUF` 的 0.6B Base、0.6B CustomVoice 和共用 tokenizer，均为 Q4_K_M。具体字节数和 SHA-256 来自公开 Hugging Face 文件元数据，写入 App 的下载/导入约束。模型权重未打包。
- 本次新增：中文界面、音频导入解码与裁剪、音色试听/改名、模型校验导入/续传、CustomVoice 选择入口、长文案分段、云端目录/样音/缓存/本地克隆串联、Keystore 凭据保存、回归测试及 Windows 构建脚本。
- 本机降噪参考同一用户项目中的 `LocalSpeechDenoiser.java`、`SpeechDenoiseFrames.java`、`SpeechPreservationGuard.java`、`RnnoiseNative.java`、`rnnoise_jni.cpp` 及其测试。保留帧延迟补偿、人声保护和 JNI 算法；针对本 App 的完整 PCM16 WAV 配音改写流式读取和重采样，新增记录中的双版本播放/导出、取消、文件清理与非破坏数据库迁移。没有修改参考项目。
- 随包 RNNoise 源码及 int8 模型取自参考项目固定的 Xiph RNNoise 提交 `70f1d256acd4b34a572f999a05c87bf00b67730d`；模型 SHA-256 `1ad07b428be34c74d9678d05b3c387b73ccf0e6203a1a5f9ce58689b30b303c8`。源目录和 APK 的 `assets/rnnoise/COPYING` 保留 BSD 许可证；`MODEL-METADATA.txt` 保留模型裁剪说明及原归档摘要。此本地处理没有网络请求或云端计费调用。

云端请求需要使用者自己的有效凭据和平台权限。本次仅做代码及模拟协议测试，没有读取参考项目凭据，没有发起真实收费配音。推理效果和 vivo X300 Pro 实机表现未测。
