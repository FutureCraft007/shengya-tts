package com.qwen.tts.android.audio;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.function.BooleanSupplier;

/** Keep suspect speech regions dry; the final output still passes the unchanged guard. */
final class SpeechPreservationGuard {
    static final double SOURCE_RMS_FLOOR=.0001,MIN_ENERGY_RATIO=.25,MAX_ERROR_RATIO=.4225;
    private SpeechPreservationGuard(){}
    static final class Protection {
        final long protectedSamples,changedSamples;
        Protection(long protectedSamples,long changedSamples){this.protectedSamples=protectedSamples;this.changedSamples=changedSamples;}
    }
    private static boolean suspect(byte[] dry,byte[] wet,int count){
        double sourceEnergy=0,cleanEnergy=0,errorEnergy=0;
        for(int i=0;i<count*2;i+=2){double a=sample(dry,i)/32768d,b=sample(wet,i)/32768d;sourceEnergy+=a*a;cleanEnergy+=b*b;errorEnergy+=(a-b)*(a-b);}
        return sourceEnergy/count>=SOURCE_RMS_FLOOR*SOURCE_RMS_FLOOR&&(cleanEnergy<sourceEnergy*MIN_ENERGY_RATIO||errorEnergy>sourceEnergy*MAX_ERROR_RATIO);
    }
    private static short sample(byte[] bytes,int at){return (short)((bytes[at]&255)|(bytes[at+1]<<8));}
    private static void check(BooleanSupplier cancelled)throws IOException{if(cancelled!=null&&cancelled.getAsBoolean())throw new IOException("任务已取消");}
    /** File-backed, bounded memory. Restore risky 5ms windows with 20ms margins and 10ms fades. */
    static Protection protect(File originalPcm,File denoisedWav,long samples,int rate,BooleanSupplier cancelled)throws IOException{
        if(samples<=0||rate<=0||samples>rate*300L)throw new IOException("本机降噪保声检查参数无效");
        int window=Math.max(1,Math.round(rate*.005f)),countWindows=(int)((samples+window-1)/window);
        boolean[] keep=new boolean[countWindows];int[] distance=new int[countWindows];
        try(RandomAccessFile original=new RandomAccessFile(originalPcm,"r");RandomAccessFile clean=new RandomAccessFile(denoisedWav,"rw")){
            if(original.length()!=samples*2||clean.length()!=44+samples*2)throw new IOException("本机降噪保声检查长度不一致");
            byte[] dry=new byte[window*2],wet=new byte[window*2];clean.seek(44);
            for(int index=0;index<countWindows;index++){
                check(cancelled);int count=(int)Math.min(window,samples-(long)index*window);
                original.readFully(dry,0,count*2);clean.readFully(wet,0,count*2);
                if(suspect(dry,wet,count))for(int j=Math.max(0,index-4);j<Math.min(countWindows,index+5);j++)keep[j]=true;
            }
            int last=-countWindows-10;
            for(int i=0;i<countWindows;i++){if(keep[i])last=i;distance[i]=i-last;}
            last=countWindows*2+10;
            for(int i=countWindows-1;i>=0;i--){if(keep[i])last=i;distance[i]=Math.min(distance[i],last-i);}
            long protectedSamples=0,changedSamples=0;
            for(int index=0;index<countWindows;index++){
                check(cancelled);long start=(long)index*window;int count=(int)Math.min(window,samples-start);
                original.seek(start*2);original.readFully(dry,0,count*2);clean.seek(44+start*2);clean.readFully(wet,0,count*2);
                if(keep[index]){System.arraycopy(dry,0,wet,0,count*2);protectedSamples+=count;}
                else if(distance[index]<=2){
                    for(int i=0;i<count;i++){
                        // Distance to the nearest dry window edge, in sample units.
                        double left=index>0&&distance[index-1]<distance[index]?(distance[index]-1)*window+i+1:Double.POSITIVE_INFINITY;
                        double right=index+1<countWindows&&distance[index+1]<distance[index]?(distance[index]-1)*window+window-i:Double.POSITIVE_INFINITY;
                        double mix=Math.min(1,Math.min(left,right)/(2d*window));
                        short a=sample(dry,i*2),b=sample(wet,i*2),v=(short)Math.round(a+(b-a)*mix);
                        wet[i*2]=(byte)v;wet[i*2+1]=(byte)(v>>>8);
                    }
                    // A fade must not introduce a new failing window through phase cancellation.
                    if(suspect(dry,wet,count)){System.arraycopy(dry,0,wet,0,count*2);protectedSamples+=count;}
                }
                for(int i=0;i<count*2;i+=2)if(sample(dry,i)!=sample(wet,i))changedSamples++;
                clean.seek(44+start*2);clean.write(wet,0,count*2);
            }
            check(cancelled);
            return new Protection(protectedSamples,changedSamples);
        }
    }
    static void verify(File originalPcm,File denoisedWav,long samples,int rate,BooleanSupplier cancelled)throws IOException{
        if(samples<=0||rate<=0)throw new IOException("本机降噪保声检查参数无效");
        int window=Math.max(1,Math.round(rate*.005f));
        try(RandomAccessFile original=new RandomAccessFile(originalPcm,"r");RandomAccessFile clean=new RandomAccessFile(denoisedWav,"r")){
            if(original.length()!=samples*2||clean.length()!=44+samples*2)throw new IOException("本机降噪保声检查长度不一致");
            clean.seek(44);byte[] dry=new byte[window*2],wet=new byte[window*2];
            for(long start=0;start<samples;start+=window){
                if(cancelled!=null&&cancelled.getAsBoolean())throw new IOException("任务已取消");
                int count=(int)Math.min(window,samples-start);original.readFully(dry,0,count*2);clean.readFully(wet,0,count*2);
                if(suspect(dry,wet,count))
                    throw new IOException("本机降噪在 "+Math.round(start*1000d/rate)+"ms 处明显改变原声，可能削弱轻辅音；已拒绝降噪结果并保留完整原声");
            }
        }
    }
}
