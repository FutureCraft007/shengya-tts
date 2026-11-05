package com.qwen.tts.android.audio;

final class RnnoiseNative {
    static {System.loadLibrary("qwen_rnnoise");}
    private RnnoiseNative(){}
    static native long create(String modelPath);
    static native void process(long handle,float[] input,float[] output);
    static native void destroy(long handle);
}
