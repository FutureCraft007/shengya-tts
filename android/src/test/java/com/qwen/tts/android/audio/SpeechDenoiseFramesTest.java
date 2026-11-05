package com.qwen.tts.android.audio;

import org.junit.Test;
import java.io.IOException;
import java.util.*;
import static org.junit.Assert.*;

public class SpeechDenoiseFramesTest {
    @Test public void compensatesDelayAndFlushesFullAndPartialFrames()throws Exception{
        for(int count:new int[]{1,239,480,481,960,1001,537213}){
            List<Float> dry=new ArrayList<>(),wet=new ArrayList<>();int[] read={0},calls={0};
            ArrayDeque<float[]> delayed=new ArrayDeque<>();delayed.add(new float[480]);delayed.add(new float[480]);
            SpeechDenoiseFrames.process(count,()->++read[0]/1000000f,(input,output)->{calls[0]++;System.arraycopy(delayed.remove(),0,output,0,480);delayed.add(input.clone());},dry::add,wet::add,()->false);
            assertEquals(count,read[0]);assertEquals(dry,wet);assertEquals(count,wet.size());assertEquals((count+479)/480+2,calls[0]);
        }
    }
    @Test public void preservesSingleTransientAtVeryLastSample()throws Exception{
        int count=481;List<Float> wet=new ArrayList<>();int[] read={0};
        ArrayDeque<float[]> delayed=new ArrayDeque<>();delayed.add(new float[480]);delayed.add(new float[480]);
        SpeechDenoiseFrames.process(count,()->++read[0]==count?.1f:0,(input,output)->{System.arraycopy(delayed.remove(),0,output,0,480);delayed.add(input.clone());},v->{},wet::add,()->false);
        assertEquals(.1f,wet.get(count-1),0);assertEquals(0,wet.get(count-2),0);
    }
    @Test public void cancellationStopsBeforeProcessorAndOutput()throws Exception{
        try{SpeechDenoiseFrames.process(480,()->{fail();return 0;},(a,b)->fail(),v->fail(),v->fail(),()->true);fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("取消"));}
    }
    @Test public void rejectsEmptyInput()throws Exception{
        try{SpeechDenoiseFrames.process(0,()->0,(a,b)->{},v->{},v->{},()->false);fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("空"));}
    }
}
