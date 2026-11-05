# Third-party notices

## Paradee 1.1

Paradee by Sahil Mahendrakar, https://github.com/sahilmahendrakar/paradee,
and the fixed Paradee-8M-v1.1 INT8 model: Apache-2.0. License retained at
`docs/licenses/Paradee-Apache-2.0.txt`. This client adds a declaration-only
ONNX runtime adapter; source graph nodes and weights remain unchanged.
The offline English lexicon is reused from the existing sherpa-onnx Kokoro
v1.1 package; corresponding upstream notices continue to apply.

The application license does not replace the separate licenses of its dependencies. Retain the original notices in source and binary distributions.

The public source distribution omits GGML's optional YOLO example and its image assets. Both clients build GGML with examples disabled.

| Component | Source / revision | License |
|---|---|---|
| Original Android client | Danmoreng/qwen3-tts-android | MIT; original Danmoreng notice retained in `LICENSE` |
| Native inference engine | Danmoreng/qwen3-tts.cpp, `4562731dc612cb87b4cd1eedac275a17d1078773` | MIT, `external/qwen3-tts.cpp/LICENSE` |
| GGML | `eced84c86f8b012c752c016f7fe789adea168e1e` | MIT and nested component notices, retained in `external/qwen3-tts.cpp/ggml/` |
| Android RNNoise | Xiph RNNoise `70f1d256acd4b34a572f999a05c87bf00b67730d` | BSD-style terms, retained in `android/src/main/cpp/rnnoise/COPYING` and `android/src/main/assets/rnnoise/COPYING` |
| RNNoise model | Existing int8 model from the input Android project | Preserve `android/src/main/assets/rnnoise/MODEL-METADATA.txt`; this is the existing reduced int8 model, not a claim that it is the full upstream archive |
| Qwen models and tokenizer | Qwen3-TTS / Serveurperso GGUF distribution | Separate Apache-2.0 model license; weights are downloaded separately and not bundled |
| AndroidX, Compose, Room, Kotlin and Gradle dependencies | Android build configuration and version catalog | Their individual license and NOTICE requirements remain applicable |
| Windows desktop adapter and UI | Local Volcano Voice Studio derivative, itself based on the same upstream engine | MIT; full inherited license retained at `docs/licenses/Volcano-Voice-Studio-MIT.txt`; rebranded and adapted to this layout |
| Python | Windows standalone runtime | Python PSF license and bundled-component notices, retained under `licenses/` |
| PySide6 / Shiboken / Qt 6.11.0 | Windows 0.6.0 native desktop UI; Qt Core, GUI and Widgets | Dynamic LGPL v3 libraries; license texts, exceptions, source links and replacement/rebuild information are retained in `docs/QT-RUNTIME.md` and the bundle's `licenses/` folder. No Qt source patches are applied |
| NSIS | Windows installer compiler and bootstrap | Its zlib, bzip2, CPL and other component terms remain applicable; the compiler's license/notice is retained with the installer bundle. No NSIS compiler is installed on end-user machines |
| PyInstaller | Standalone packager | GPL with bootloader exception; retain its license and exception in the distribution |
| Microsoft Visual C++ runtime | Native Windows dependencies | Microsoft redistribution terms apply; these files are not relicensed as MIT |

The existing Android cloud and denoising adapters retain their documented provenance. This reorganization does not make a new claim that they were independently rewritten. See `docs/LEGACY-PROVENANCE.md`. Maintainers must confirm rights to publish any adaptations carried forward from their own projects before a public upload.

The Windows runtime bundle also retains the applicable OpenSSL, zlib and Python bundled-component license files. No official Qwen or provider endorsement is implied.

Windows/Android 0.7.0 add Kokoro-82M v1.1-zh INT8 (Apache-2.0 weights, downloaded separately), sherpa-onnx 2.0.1 (Apache-2.0), ONNX Runtime (MIT), and Apache Commons Compress 1.27.1 (Apache-2.0, Android only). This build uses lexicon-based frontends, excludes unused legacy phonemizer data from model installation, and does not link the old eSpeak NG frontend. Corresponding source/build links and exact runtime/model digests are in `docs/KOKORO.md`. The application license does not replace dependency licenses or grant additional rights in voices.
