package com.qwen.tts.android.audio;

import java.io.IOException;
import java.util.Arrays;
import java.util.function.BooleanSupplier;

/** Stream the pinned RNNoise WOLA + delayed_X pipeline without dropping its tail. */
final class SpeechDenoiseFrames {
    static final int FRAME_SIZE=480,DELAY_FRAMES=2;
    interface Source {float next()throws Exception;}
    interface Processor {void process(float[] input,float[] output)throws Exception;}
    interface Sink {void sample(float value)throws Exception;}
    private SpeechDenoiseFrames(){}
    static void process(long samples,Source source,Processor processor,Sink original,Sink clean,BooleanSupplier cancelled)throws Exception{
        if(samples<=0)throw new IOException("本机降噪收到空 PCM");
        long frames=(samples+FRAME_SIZE-1)/FRAME_SIZE;
        float[] input=new float[FRAME_SIZE],output=new float[FRAME_SIZE];
        for(long index=0;index<frames+DELAY_FRAMES;index++){
            if(cancelled!=null&&cancelled.getAsBoolean())throw new IOException("任务已取消");
            Arrays.fill(input,0);Arrays.fill(output,0);
            int count=(int)Math.max(0,Math.min(FRAME_SIZE,samples-index*FRAME_SIZE));
            for(int i=0;i<count;i++){input[i]=source.next();original.sample(input[i]);}
            processor.process(input,output);
            long outputIndex=index-DELAY_FRAMES;
            if(outputIndex<0)continue;
            int outputCount=(int)Math.min(FRAME_SIZE,samples-outputIndex*FRAME_SIZE);
            for(int i=0;i<outputCount;i++)clean.sample(output[i]);
        }
    }
}
