# Kokoro 中文轻量配音（Android 0.7.2 / Windows 0.7.1）

安卓和 Windows：打开配音工作台，在“配音模型”下选择“Kokoro · 轻量中英文”。切回千问可使用声音克隆或千问预置音色；文案在切换时保留。选择“下载中文轻量模型”，或导入官方 `kokoro-int8-multi-lang-v1_1.tar.bz2`。下载包为 147,052,523 字节；安装前核对 SHA-256 `259c9c8bc906d6078e4e0570421885dabb68b539c2f970fcbb890a58eeaf3e9c`。支持断点续传。模型与应用分开分发。

下载完成后，选择音色、填写中文或英文文案、选择语速并生成。无需 API Key 或显卡。音频为 24kHz PCM WAV，保存到现有历史；安卓可继续用历史页的导出和降噪功能。轻量引擎使用预置音色，参考音频克隆仍使用千问 Base。模型提供 55 个中文女声、45 个中文男声、3 个英文声，ID 和名称按官方表，不推测音色风格。未登录词、英文专名和多音字需要实际试听检查。

## 来源与固定版本

- 模型：Kokoro-82M v1.1-zh，量化包由 sherpa-onnx 维护者发布；声学模型和音色权重为 Apache-2.0。https://huggingface.co/csukuangfj/kokoro-int8-multi-lang-v1_1
- 音色映射及样音：https://k2-fsa.github.io/sherpa/onnx/tts/all/Chinese-English/kokoro-multi-lang-v1_1.html
- 双端运行库：sherpa-onnx 2.0.1；Apache-2.0。对应源码、外部依赖版本和构建脚本：https://github.com/k2-fsa/sherpa-onnx/tree/v2.0.1
- Android 静态 ONNX Runtime AAR：`sherpa-onnx-static-link-onnxruntime-2.0.1.aar`，SHA-256 `07e042d97f3a6bd5b7fe74d4eef0513dc01a714b16c15a903250095aba43a575`。Gradle 获取后校验。ARM64 JNI 库与应用一起分发。
- ONNX Runtime：MIT，随固定版推理库分发。https://github.com/microsoft/onnxruntime
- Android 模型包解压：Apache Commons Compress 1.27.1；Apache-2.0；许可证和 NOTICE 随包保留。

当前运行库直接使用模型附带的中文和美式英文词典。未使用旧 eSpeak NG 发音引擎；原始下载包中的 `espeak-ng-data/` 和不再需要的 `dict/` 不安装。相关独立许可证不被应用 MIT 许可替代。分发的原生运行库应保留对应源码和构建信息。

## 验证边界

已用真实中文权重在这台 Windows 电脑生成 WAV，并验证模型包完整性、损坏包拒绝、解压目录边界、取消和界面路由。桌面实测包括首次加载，不能当作手机耗时。手机型号、线程数、文案长度、发热和系统后台限制都会影响速度。Android 的 native ABI、打包和 JVM/Compose 测试与手机真实推理需分别记录。

## 与另外两个模型的区别

Paradee 约 9MB 的版本是单音色英文模型，不满足中文必需的要求。CosyVoice 3 的零样本克隆和方言能力适合另一条质量路线，官方运行依赖和多份 GB 级文件需要单独部署；本次没有把它标为已经接入。原有 Qwen Base/CustomVoice 功能保留。
