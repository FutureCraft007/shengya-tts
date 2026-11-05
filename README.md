# Shengya TTS · 声芽配音

[简体中文](README.zh-CN.md)

Shengya TTS turns text into speech on Android and Windows. Use it for video narration, tutorials, spoken content, or app prompts. The project builds on Danmoreng’s [qwen3-tts-android](https://github.com/Danmoreng/qwen3-tts-android) and uses [qwen3-tts.cpp](https://github.com/Danmoreng/qwen3-tts.cpp) and GGML for local inference.

## Offline models

Download or import a model in the app, then generate speech offline. Both clients let you switch models in the same workspace.

| Model | What it does | Good for |
| --- | --- | --- |
| **Qwen3-TTS Base** | Creates reusable local voices from reference recordings | Narration in your own voice or an authorized voice |
| **Qwen3-TTS CustomVoice** | Generates speech with the model’s preset voices | Video narration and tutorials |
| **Kokoro** | Offers 100 Chinese and 3 English voices with adjustable speed | Everyday Chinese narration and short readings |
| **Paradee** | Uses an English model of about 8.46 MB with one female voice and adjustable speed | Short English lines, prompts, and lightweight reading |

## From script to audio

1. **Pick a model and voice.** Choose a preset voice or one from your local library in the speech workspace.
2. **Enter your script and generate.** When the model supports speed adjustment, preview a short line before generating the full script.
3. **Preview and export.** Locally generated audio appears in History. Play it back or export a WAV file for editing.

## Local voice cloning

Use Qwen3-TTS Base to import a clear, single-speaker recording of **3–30 seconds**. Add a transcript that matches the recording to enable full cloning mode. The voice is saved in your local library so you can reuse it with new scripts. Use your own voice or a recording you have permission to clone.

## Custom cloud speech

Both clients can connect to your speech service. Enter the **full HTTPS endpoint, API key, model, and voice ID** to generate and preview audio in the cloud workspace. The service must accept OpenAI-compatible `/audio/speech` requests and return WAV directly.

## Android features

- **On-device denoising.** Apply RNNoise to generated audio from History. Preview and export the original and denoised versions separately.
- **Local speech API.** Start the service manually so other apps on the same phone can submit speech jobs and retrieve their results. Configure one to six inference workers.

[MIT License](LICENSE) · [Third-party licenses and credits](THIRD_PARTY_NOTICES.md)
