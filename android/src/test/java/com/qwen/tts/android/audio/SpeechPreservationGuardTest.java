package com.qwen.tts.android.audio;

import org.junit.Test;
import java.io.*;
import java.nio.file.Files;
import java.util.Arrays;
import static org.junit.Assert.*;

public class SpeechPreservationGuardTest {
    private static final int RATE=48000;
    @Test public void acceptsUnchangedAndMildAttenuationWithoutMutatingFiles()throws Exception{
        short[] dry=speech();for(double gain:new double[]{1,.8,.6}){short[] wet=dry.clone();for(int i=0;i<wet.length;i++)wet[i]=(short)Math.round(wet[i]*gain);verify(dry,wet,false);}
    }
    @Test public void rejectsMutedSoftShuoLikeOnset()throws Exception{
        short[] dry=speech();soft(dry,4800,5280,.006);short[] wet=dry.clone();Arrays.fill(wet,4800,5280,(short)0);verify(dry,wet,true);
    }
    @Test public void rejectsFiveMillisecondLowEnergyConsonant()throws Exception{
        short[] dry=speech();soft(dry,4800,5040,.0006);short[] wet=dry.clone();Arrays.fill(wet,4800,5040,(short)0);verify(dry,wet,true);
    }
    @Test public void rejectsPartialLossAndSpectralChangeWithSameEnergy()throws Exception{
        short[] dry=speech();short[] wet=dry.clone();for(int i=9600;i<9840;i++)wet[i]=(short)Math.round(wet[i]*.2);verify(dry,wet,true);
        wet=dry.clone();for(int i=9600;i<9840;i++)wet[i]=(short)-wet[i];verify(dry,wet,true);
    }
    @Test public void acceptsDigitalSilenceAndSubFloorResidue()throws Exception{
        short[] dry=new short[481];Arrays.fill(dry,(short)1);verify(dry,new short[481],false);
    }
    @Test public void protectsShortFinalPhonemeAndPartialWindow()throws Exception{
        short[] dry=new short[481];dry[480]=1000;verify(dry,dry.clone(),false);verify(dry,new short[481],true);
    }
    @Test public void rejectsTruncationAndCancellation()throws Exception{
        File dry=file(new short[480],0),wet=file(new short[479],44);try{
            try{SpeechPreservationGuard.verify(dry,wet,480,RATE,()->false);fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("长度"));}
        }finally{dry.delete();wet.delete();}
        dry=file(new short[480],0);wet=file(new short[480],44);try{
            try{SpeechPreservationGuard.verify(dry,wet,480,RATE,()->true);fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("取消"));}
        }finally{dry.delete();wet.delete();}
    }
    @Test public void restores195msConsonantWithoutDiscardingDenoisingElsewhere()throws Exception{
        short[] dry=speech();soft(dry,9360,9600,.006);short[] wet=dry.clone();
        for(int i=0;i<wet.length;i++)wet[i]=(short)Math.round(wet[i]*.8);
        Arrays.fill(wet,9360,9600,(short)0);
        File a=file(dry,0),b=file(wet,44);byte[] source=Files.readAllBytes(a.toPath());
        try{
            SpeechPreservationGuard.Protection report=SpeechPreservationGuard.protect(a,b,dry.length,RATE,()->false);
            SpeechPreservationGuard.verify(a,b,dry.length,RATE,()->false);
            short[] result=read(b,dry.length);assertTrue(report.protectedSamples>=2160);assertTrue(report.changedSamples>0);
            // Entire risky region and 20ms context remain bit-identical to source.
            assertArrayEquals(Arrays.copyOfRange(dry,8400,10560),Arrays.copyOfRange(result,8400,10560));
            assertArrayEquals(Arrays.copyOfRange(wet,14400,24000),Arrays.copyOfRange(result,14400,24000));
            assertArrayEquals(source,Files.readAllBytes(a.toPath()));assertEquals(44+dry.length*2,b.length());
        }finally{a.delete();b.delete();}
    }
    @Test public void preservesMultipleSoftConsonantsAndShortTail()throws Exception{
        short[] dry=new short[24001];soft(dry,0,dry.length,.08);soft(dry,2400,2640,.0006);dry[24000]=1000;
        short[] wet=dry.clone();Arrays.fill(wet,2400,2640,(short)0);Arrays.fill(wet,9600,10080,(short)0);wet[24000]=0;
        File a=file(dry,0),b=file(wet,44);try{
            SpeechPreservationGuard.protect(a,b,dry.length,RATE,()->false);SpeechPreservationGuard.verify(a,b,dry.length,RATE,()->false);
            short[] result=read(b,dry.length);assertArrayEquals(dry,result);
        }finally{a.delete();b.delete();}
    }
    @Test public void leavesAcceptedCandidateUnchangedAndReportsNoopHonestly()throws Exception{
        for(double gain:new double[]{1,.8,0}){
            short[] dry=speech(),wet=dry.clone();for(int i=0;i<wet.length;i++)wet[i]=(short)Math.round(wet[i]*gain);
            File a=file(dry,0),b=file(wet,44);try{
                SpeechPreservationGuard.Protection report=SpeechPreservationGuard.protect(a,b,dry.length,RATE,()->false);
                SpeechPreservationGuard.verify(a,b,dry.length,RATE,()->false);
                if(gain==.8){assertArrayEquals(wet,read(b,dry.length));assertTrue(report.changedSamples>0);assertEquals(0,report.protectedSamples);}
                else{assertArrayEquals(dry,read(b,dry.length));assertEquals(0,report.changedSamples);}
            }finally{a.delete();b.delete();}
        }
    }
    @Test public void protectionRejectsTruncationAndHonorsCancellation()throws Exception{
        File a=file(new short[480],0),b=file(new short[479],44);try{
            try{SpeechPreservationGuard.protect(a,b,480,RATE,()->false);fail();}catch(IOException e){assertTrue(e.getMessage().contains("长度"));}
        }finally{a.delete();b.delete();}
        a=file(speech(),0);b=file(speech(),44);byte[] before=Files.readAllBytes(a.toPath());try{
            try{SpeechPreservationGuard.protect(a,b,RATE,RATE,()->true);fail();}catch(IOException e){assertTrue(e.getMessage().contains("取消"));}
            assertArrayEquals(before,Files.readAllBytes(a.toPath()));
        }finally{a.delete();b.delete();}
    }
    @Test public void fadesIntoAndOutOfProtectedRegionsWithoutHardGainSwitches()throws Exception{
        short[] dry=new short[24000],wet=new short[24000];Arrays.fill(dry,(short)10000);Arrays.fill(wet,(short)8000);Arrays.fill(wet,9360,9600,(short)0);
        File a=file(dry,0),b=file(wet,44);try{
            SpeechPreservationGuard.protect(a,b,dry.length,RATE,()->false);SpeechPreservationGuard.verify(a,b,dry.length,RATE,()->false);
            short[] result=read(b,dry.length);for(int i=1;i<result.length;i++)assertTrue(Math.abs(result[i]-result[i-1])<=5);
            assertEquals(10000,result[9360]);assertEquals(8000,result[14400]);
        }finally{a.delete();b.delete();}
    }
    private static short[] read(File file,int samples)throws Exception{
        byte[] bytes=Files.readAllBytes(file.toPath());short[] result=new short[samples];for(int i=0;i<samples;i++)result[i]=(short)((bytes[44+i*2]&255)|(bytes[45+i*2]<<8));return result;
    }
    private static short[] speech(){short[] s=new short[RATE];soft(s,9600,24000,.10);return s;}
    private static void soft(short[] s,int start,int end,double gain){for(int i=start;i<end;i++)s[i]=(short)Math.round(Math.sin(2*Math.PI*7000*i/RATE)*gain*32767);}
    private static File file(short[] samples,int header)throws Exception{File f=File.createTempFile("speech-preserve-",".pcm");try(RandomAccessFile out=new RandomAccessFile(f,"rw")){out.setLength(header+samples.length*2);out.seek(header);for(short s:samples){out.write(s);out.write(s>>>8);}}return f;}
    private static void verify(short[] dry,short[] wet,boolean reject)throws Exception{
        File a=file(dry,0),b=file(wet,44);byte[] original=Files.readAllBytes(a.toPath()),candidate=Files.readAllBytes(b.toPath());try{
            try{SpeechPreservationGuard.verify(a,b,dry.length,RATE,()->false);if(reject)fail("Expected speech-loss rejection");}catch(IOException error){if(!reject)throw error;assertTrue(error.getMessage().contains("保留完整原声"));}
            assertArrayEquals(original,Files.readAllBytes(a.toPath()));assertArrayEquals(candidate,Files.readAllBytes(b.toPath()));
        }finally{a.delete();b.delete();}
    }
}
