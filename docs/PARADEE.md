# Paradee 超轻量英文

在安卓或 Windows 配音工作台的“配音模型”中选择 **Paradee · 超轻量英文**，下载模型，或导入作者的 `paradee_int8.onnx`。点击“填入英文示例”，再点击共用的生成按钮。试听、导出和历史记录沿用原工作台。下载后离线运行。

本次固定为 Paradee 1.1 INT8，7.88M 参数，模型文件 **8,461,438 字节**。固定一个英文女声 `af_heart`，24kHz 音频，可调语速，不支持中文或声音克隆。英文发音词典随应用提供，压缩后 1,405,568 字节，解压约 5.96MB；不能将 8.46MB 理解为整个 APK、安装包或运行内存。

模型来源：https://github.com/sahilmahendrakar/paradee

固定下载：https://huggingface.co/sahilmahendrakar/Paradee-8M-v1.1/resolve/330617ad1f097b4f6b02c103e54b39006c090b45/onnx/paradee_int8.onnx

原文件 SHA-256：`665c3cce6d9872e24bd43db4aa19d3b9c224cdeb6d79b0814190e1c2e43defbc`。

## 本客户端的文本处理

客户端使用随应用附带的英文发音表，将英文词映射为模型训练所用的 Misaki 音素。词典复用 sherpa-onnx 发布的 Kokoro v1.1 英文发音表；不需要用户下载 Kokoro 中文模型才能使用 Paradee。

数字目前逐位朗读，例如 `12` 读成 `one two`。自然数字、金额和日期建议直接写成英文单词。专名或未登录词会明确提示修改拼写，避免无声跳过词语。词性、多音词和上下文处理不等同于作者 Python 版本的完整 Misaki 前端，请实际试听。含中文时提示切换模型。

## 双端运行与适配

继续使用现有 sherpa-onnx 2.0.1 CPU 运行库。安装时校验作者原模型的固定 SHA-256，仅增加一个未使用的 style 输入声明及运行元数据，使现有运行库可执行该模型；**不修改模型节点或权重**。兼容音色占位数据为 2048 字节，不增加新音色。

Python 和 Kotlin 适配结果必须逐字节一致，适配后模型 SHA-256 为 `e403e5dc1d5c8982cfe7ad6fb6070ae19827651667b12d46218b12f21cc6265e`。安装在临时目录完成验证后再替换旧目录，保留取消及回滚逻辑。安卓复用 0.7.2 修复的具名 JNI 回调。

复现资源生成可使用 `scripts/prepare-paradee-assets.py`，将上游固定版本的模型、config.json、LICENSE 和 revision.txt 放入 `build/paradee/`，并提供脚本所列的 Kokoro 英文发音表。推理本身不需要 Python ONNX 转换依赖。

Paradee 和对应代码保留 Apache-2.0 许可及作者署名，见 `docs/licenses/Paradee-Apache-2.0.txt`。详细验证边界见 [VERIFICATION.md](VERIFICATION.md)。
