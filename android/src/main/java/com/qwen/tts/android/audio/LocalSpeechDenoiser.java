package com.qwen.tts.android.audio;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

/** Offline post-processing of complete generated PCM16 WAVs. Never edits the source. */
public final class LocalSpeechDenoiser {
    public static final String MODEL = "rnnoise-70f1d256-int8";
    static final String MODEL_ASSET = "rnnoise/" + MODEL + ".bin";
    static final String MODEL_SHA256 = "1ad07b428be34c74d9678d05b3c387b73ccf0e6203a1a5f9ce58689b30b303c8";
    static final int RATE = 48000, MAX_SECONDS = 300;

    public static final class Result {
        public final int sampleRate = RATE;
        public final int sampleCount;
        public final long changedSamples, protectedMillis;
        Result(long samples, SpeechPreservationGuard.Protection protection) {
            sampleCount = (int) samples;
            changedSamples = protection.changedSamples;
            protectedMillis = Math.round(protection.protectedSamples * 1000d / RATE);
        }
    }

    private LocalSpeechDenoiser() {}

    public static Result process(Context context, File source, File output,
                                 BooleanSupplier cancelled, IntConsumer progress) throws Exception {
        check(cancelled);
        // Validate before loading native state; WAV is already PCM, no device decoder required.
        inspect(source);
        File model = modelFile(context);
        check(cancelled);
        long handle = RnnoiseNative.create(model.getAbsolutePath());
        if (handle == 0) throw new IOException("本机降噪模型无法启动");
        try {
            return processPcm(source, output, (input, clean) -> RnnoiseNative.process(handle, input, clean), cancelled, progress);
        } finally {
            RnnoiseNative.destroy(handle);
        }
    }

    // Separate from Android/JNI so resampling, duration, cancellation and preservation can be tested.
    static Result processPcm(File source, File output, SpeechDenoiseFrames.Processor processor,
                             BooleanSupplier cancelled, IntConsumer progress) throws Exception {
        if (source.getCanonicalFile().equals(output.getCanonicalFile()))
            throw new IOException("降噪结果必须单独保存");
        if (output.exists()) throw new IOException("降噪保存位置已存在");
        Wav wav = inspect(source);
        long samples = Math.round(wav.samples * (double) RATE / wav.rate);
        if (samples <= 0 || samples > (long) RATE * MAX_SECONDS)
            throw new IOException("单段本机降噪最长支持 5 分钟");
        File reference = File.createTempFile("denoise-reference-", ".pcm", output.getParentFile());
        boolean complete = false;
        try {
            check(cancelled);
            try (RandomAccessFile header = new RandomAccessFile(output, "rw")) { writeHeader(header, samples); }
            try (InputStream input = new BufferedInputStream(new FileInputStream(source), 65536);
                 OutputStream dry = new BufferedOutputStream(new FileOutputStream(reference), 65536);
                 OutputStream wet = new BufferedOutputStream(new FileOutputStream(output, true), 65536)) {
                long remaining = wav.offset;
                while (remaining > 0) {
                    long skipped = input.skip(remaining);
                    if (skipped <= 0) throw new EOFException("配音 WAV 头部不完整");
                    remaining -= skipped;
                }
                SpeechDenoiseFrames.Source resampled = new SpeechDenoiseFrames.Source() {
                    float left = readPcm(input), right = wav.samples > 1 ? readPcm(input) : left;
                    long index = 0, written = 0;
                    public float next() throws IOException {
                        double position = written++ * wav.rate / (double) RATE;
                        long target = (long) Math.floor(position);
                        while (index < target) {
                            left = right;
                            index++;
                            right = index + 1 < wav.samples ? readPcm(input) : left;
                        }
                        return (float) (left + (right - left) * (position - target));
                    }
                };
                long[] written = {0};
                SpeechDenoiseFrames.process(samples, resampled, processor,
                    value -> writePcm(dry, value), value -> {
                        writePcm(wet, value);
                        if (++written[0] % 24000 == 0 && progress != null)
                            progress.accept((int) (written[0] * 90 / samples));
                    }, cancelled);
            }
            if (progress != null) progress.accept(93);
            SpeechPreservationGuard.Protection protection = SpeechPreservationGuard.protect(reference, output, samples, RATE, cancelled);
            SpeechPreservationGuard.verify(reference, output, samples, RATE, cancelled);
            check(cancelled);
            if (output.length() != 44 + samples * 2) throw new IOException("降噪音频不完整");
            if (progress != null) progress.accept(100);
            complete = true;
            return new Result(samples, protection);
        } finally {
            reference.delete();
            if (!complete) output.delete();
        }
    }

    private static final class Wav {
        final int rate; final long offset, samples;
        Wav(int rate, long offset, long samples) { this.rate = rate; this.offset = offset; this.samples = samples; }
    }

    private static Wav inspect(File file) throws IOException {
        if (!file.isFile() || file.length() > 32L * 1024 * 1024) throw new IOException("本机降噪音频不存在或超过 32 MB");
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            if (!tag(in).equals("RIFF")) throw new IOException("配音不是 WAV 格式");
            long end = uint(in) + 8;
            if (!tag(in).equals("WAVE") || end != in.length()) throw new IOException("配音 WAV 长度不完整");
            int rate = 0; long offset = 0, size = 0;
            while (in.getFilePointer() + 8 <= end) {
                String kind = tag(in); long length = uint(in), start = in.getFilePointer();
                if (length > end - start) throw new IOException("配音 WAV 数据不完整");
                if (kind.equals("fmt ")) {
                    if (length < 16 || ushort(in) != 1 || ushort(in) != 1)
                        throw new IOException("本机降噪仅支持生成的单声道 PCM16 配音");
                    rate = (int) uint(in);
                    long bytes = uint(in); int align = ushort(in), bits = ushort(in);
                    if (rate < 8000 || rate > RATE || bytes != rate * 2L || align != 2 || bits != 16)
                        throw new IOException("配音 PCM 参数不支持本机降噪");
                } else if (kind.equals("data")) { offset = start; size = length; }
                in.seek(start + length + (length & 1));
            }
            if (rate == 0 || offset == 0 || size == 0 || (size & 1) != 0) throw new IOException("配音没有完整的 PCM 数据");
            if (size / 2 > rate * (long) MAX_SECONDS) throw new IOException("单段本机降噪最长支持 5 分钟");
            return new Wav(rate, offset, size / 2);
        }
    }

    private static String tag(RandomAccessFile in) throws IOException {
        byte[] bytes = new byte[4]; in.readFully(bytes); return new String(bytes, StandardCharsets.US_ASCII);
    }
    private static int ushort(RandomAccessFile in) throws IOException { return Short.toUnsignedInt(Short.reverseBytes(in.readShort())); }
    private static long uint(RandomAccessFile in) throws IOException { return Integer.toUnsignedLong(Integer.reverseBytes(in.readInt())); }
    private static float readPcm(InputStream in) throws IOException {
        int lo = in.read(), hi = in.read();
        if ((lo | hi) < 0) throw new EOFException("配音 PCM 提前结束");
        return (short) (lo | (hi << 8)) / 32768f;
    }
    private static void writePcm(OutputStream out, float value) throws IOException {
        int pcm = Math.round(Math.max(-1, Math.min(1, value)) * 32768f);
        pcm = Math.max(-32768, Math.min(32767, pcm));
        out.write(pcm); out.write(pcm >>> 8);
    }
    private static void writeHeader(RandomAccessFile out, long samples) throws IOException {
        out.writeBytes("RIFF"); out.writeInt(Integer.reverseBytes((int) (36 + samples * 2)));
        out.writeBytes("WAVEfmt "); out.writeInt(Integer.reverseBytes(16));
        out.writeShort(Short.reverseBytes((short) 1)); out.writeShort(Short.reverseBytes((short) 1));
        out.writeInt(Integer.reverseBytes(RATE)); out.writeInt(Integer.reverseBytes(RATE * 2));
        out.writeShort(Short.reverseBytes((short) 2)); out.writeShort(Short.reverseBytes((short) 16));
        out.writeBytes("data"); out.writeInt(Integer.reverseBytes((int) samples * 2));
    }

    private static synchronized File modelFile(Context context) throws Exception {
        File model = new File(context.getNoBackupFilesDir(), MODEL + ".bin");
        if (model.isFile() && MODEL_SHA256.equals(sha256(model))) return model;
        File partial = File.createTempFile("rnnoise-", ".partial", context.getNoBackupFilesDir());
        try {
            try (InputStream in = context.getAssets().open(MODEL_ASSET);
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(partial))) {
                byte[] buffer = new byte[65536];
                for (int n; (n = in.read(buffer)) >= 0;) if (n > 0) out.write(buffer, 0, n);
            }
            if (!MODEL_SHA256.equals(sha256(partial))) throw new IOException("本机降噪模型校验失败");
            if (model.exists() && !model.delete()) throw new IOException("本机降噪模型无法更新");
            if (!partial.renameTo(model)) throw new IOException("本机降噪模型无法保存");
            return model;
        } finally { partial.delete(); }
    }
    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[65536]; for (int n; (n = in.read(buffer)) >= 0;) if (n > 0) digest.update(buffer, 0, n);
        }
        StringBuilder out = new StringBuilder();
        for (byte b : digest.digest()) out.append(String.format(Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }
    private static void check(BooleanSupplier cancelled) throws IOException {
        if (cancelled != null && cancelled.getAsBoolean()) throw new IOException("降噪已取消，原声保留");
    }
}
