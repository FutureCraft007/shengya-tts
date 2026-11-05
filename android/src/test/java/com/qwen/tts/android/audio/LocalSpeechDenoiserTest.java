package com.qwen.tts.android.audio;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import java.util.ArrayDeque;
import static org.junit.Assert.*;

public class LocalSpeechDenoiserTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private SpeechDenoiseFrames.Processor delayedIdentity() {
        ArrayDeque<float[]> queue = new ArrayDeque<>();
        queue.add(new float[480]); queue.add(new float[480]);
        return (input, output) -> { System.arraycopy(queue.remove(), 0, output, 0, 480); queue.add(input.clone()); };
    }

    @Test public void resamplesCompleteAudioIncludingThirtySecondBoundaryAndTail() throws Exception {
        for (int count : new int[]{1, 239, 240, 241, 744001}) {
            File source = wav(count, 24000), output = new File(temp.getRoot(), "result-" + count + ".wav");
            byte[] original = Files.readAllBytes(source.toPath());
            int[] lastProgress = {-1};
            LocalSpeechDenoiser.Result result = LocalSpeechDenoiser.processPcm(source, output, delayedIdentity(), () -> false, p -> lastProgress[0] = p);
            assertEquals(count * 2, result.sampleCount); assertEquals(48000, result.sampleRate);
            assertEquals(44 + count * 4L, output.length()); assertEquals(100, lastProgress[0]);
            assertArrayEquals(original, Files.readAllBytes(source.toPath()));
            try (RandomAccessFile in = new RandomAccessFile(output, "r")) {
                in.seek(output.length() - 4);
                assertEquals(1000, Short.reverseBytes(in.readShort()));
                assertEquals(1000, Short.reverseBytes(in.readShort()));
            }
        }
    }

    @Test public void fortyEightKhzRoundTripDoesNotLoseNegativeFullScaleOrFinalSample() throws Exception {
        File source = wav(481, 48000), output = new File(temp.getRoot(), "roundtrip.wav");
        try (RandomAccessFile in = new RandomAccessFile(source, "rw")) { in.seek(44); in.writeShort(Short.reverseBytes(Short.MIN_VALUE)); }
        LocalSpeechDenoiser.Result result = LocalSpeechDenoiser.processPcm(source, output, delayedIdentity(), () -> false, null);
        assertEquals(0, result.changedSamples);
        assertArrayEquals(Files.readAllBytes(source.toPath()), Files.readAllBytes(output.toPath()));
    }

    @Test public void cancellationAndProcessorFailureRemoveOnlyPartialOutput() throws Exception {
        File source = wav(48001, 24000);
        byte[] before = Files.readAllBytes(source.toPath());
        for (boolean fail : new boolean[]{false, true}) {
            File out = new File(temp.getRoot(), "failed-" + fail + ".wav");
            boolean[] cancelled = {false};
            SpeechDenoiseFrames.Processor processor = fail ? (a, b) -> { throw new IOException("fake processor error"); } : delayedIdentity();
            try {
                LocalSpeechDenoiser.processPcm(source, out, processor, () -> cancelled[0], p -> cancelled[0] = true);
                fail("Expected cancellation or processor failure");
            } catch (IOException expected) { assertFalse(out.exists()); }
            assertArrayEquals(before, Files.readAllBytes(source.toPath()));
            assertEquals(0, temp.getRoot().listFiles((d, name) -> name.startsWith("denoise-reference-")).length);
        }
    }

    @Test public void refusesSourceOverwriteAndExistingOutput() throws Exception {
        File source = wav(481, 24000), existing = temp.newFile();
        Files.write(existing.toPath(), new byte[]{1, 2, 3});
        byte[] before = Files.readAllBytes(source.toPath());
        for (File target : new File[]{source, existing}) {
            try { LocalSpeechDenoiser.processPcm(source, target, delayedIdentity(), () -> false, null); fail(); }
            catch (IOException expected) { assertArrayEquals(before, Files.readAllBytes(source.toPath())); }
        }
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(existing.toPath()));
    }

    @Test public void rejectsTruncatedWavAndAudioLongerThanFiveMinutesWithoutOutput() throws Exception {
        File source = wav(481, 24000), output = new File(temp.getRoot(), "invalid.wav");
        try (RandomAccessFile in = new RandomAccessFile(source, "rw")) { in.setLength(source.length() - 1); }
        expectRejected(source, output);
        source = wav(24000 * 300 + 1, 24000);
        expectRejected(source, output);
    }

    private void expectRejected(File source, File output) throws Exception {
        try { LocalSpeechDenoiser.processPcm(source, output, delayedIdentity(), () -> false, null); fail(); }
        catch (IOException expected) { assertFalse(output.exists()); }
    }

    private File wav(int count, int rate) throws Exception {
        File file = temp.newFile();
        try (RandomAccessFile out = new RandomAccessFile(file, "rw")) {
            out.writeBytes("RIFF"); out.writeInt(Integer.reverseBytes(36 + count * 2));
            out.writeBytes("WAVEfmt "); out.writeInt(Integer.reverseBytes(16));
            out.writeShort(Short.reverseBytes((short) 1)); out.writeShort(Short.reverseBytes((short) 1));
            out.writeInt(Integer.reverseBytes(rate)); out.writeInt(Integer.reverseBytes(rate * 2));
            out.writeShort(Short.reverseBytes((short) 2)); out.writeShort(Short.reverseBytes((short) 16));
            out.writeBytes("data"); out.writeInt(Integer.reverseBytes(count * 2));
            out.setLength(44 + count * 2L); out.seek(file.length() - 2); out.writeShort(Short.reverseBytes((short) 1000));
        }
        return file;
    }
}
