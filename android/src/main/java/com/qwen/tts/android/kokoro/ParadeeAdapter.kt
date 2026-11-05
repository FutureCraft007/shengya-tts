package com.qwen.tts.android.kokoro

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPInputStream

/** A declaration-only ONNX adapter; graph nodes and weights are unchanged. */
internal object ParadeeAdapter {
    private data class Field(val number:Int,val payload:ByteArray,val raw:ByteArray)
    private fun variable(value:Int):ByteArray {
        var n=value;val output=ByteArrayOutputStream()
        while(n>127){output.write((n and 127) or 128);n=n ushr 7};output.write(n);return output.toByteArray()
    }
    private fun field(number:Int,value:ByteArray)=variable((number shl 3) or 2)+variable(value.size)+value
    private fun fields(data:ByteArray):List<Field> {
        var offset=0
        fun read():Long {
            var value=0L;var shift=0
            while(true){check(offset<data.size && shift<70){"模型数据无效"};val b=data[offset++].toInt() and 255;value=value or ((b and 127).toLong() shl shift);if(b<128)return value;shift+=7}
        }
        return buildList {
            while(offset<data.size){val begin=offset;val tag=read().toInt();val wire=tag and 7
                val payload=when(wire){
                    2->{val size=read();check(size in 0..(data.size-offset).toLong()){"模型数据截断"};data.copyOfRange(offset,offset+size.toInt()).also {offset+=size.toInt()}}
                    0->{read();byteArrayOf()}
                    1,5->{offset+=if(wire==1)8 else 4;byteArrayOf()}
                    else->error("模型编码不支持")
                }
                check(offset<=data.size){"模型数据截断"};add(Field(tag ushr 3,payload,data.copyOfRange(begin,offset)))
            }
        }
    }
    fun adapt(data:ByteArray):ByteArray {
        val dim=field(1,byteArrayOf(8,1));val tensor=byteArrayOf(8,1)+field(2,dim+dim)
        val style=field(1,"style".toByteArray())+field(2,field(1,tensor))
        val result=ByteArrayOutputStream();var found=false
        fields(data).forEach { item ->
            if(item.number!=7)result.write(item.raw) else {
                val graph=ByteArrayOutputStream();val inputs=mutableListOf<ByteArray>()
                fields(item.payload).forEach { if(it.number==11)inputs+=it.payload else graph.write(it.raw) }
                val names=inputs.map { input->fields(input).first {it.number==1}.payload.toString(Charsets.UTF_8) }
                check(names==listOf("input_ids","speed")){"Paradee 模型版本不支持"}
                graph.write(field(11,inputs[0]));graph.write(field(11,style));graph.write(field(11,inputs[1]))
                result.write(field(7,graph.toByteArray()));found=true
            }
        }
        check(found){"模型缺少计算图"}
        linkedMapOf("model_type" to "kokoro","sample_rate" to "24000","version" to "2","voice" to "en-us",
            "n_speakers" to "1","style_dim" to "512,1,1","speaker_names" to "af_heart",
            "source_model" to "Paradee-8M-v1.1","shengya_adapter" to "1").forEach { (key,value)->
            result.write(field(14,field(1,key.toByteArray())+field(2,value.toByteArray())))
        }
        return result.toByteArray()
    }
    fun install(context:Context,source:File,directory:File) {
        directory.mkdirs();File(directory,"model.int8.onnx").writeBytes(adapt(source.readBytes()))
        context.assets.open("paradee-tokens.txt").use { input->File(directory,"tokens.txt").outputStream().use {input.copyTo(it)} }
        GZIPInputStream(context.assets.open("paradee-lexicon.bin")).use { input->File(directory,"lexicon.txt").outputStream().use {input.copyTo(it)} }
        File(directory,"voices.bin").writeBytes(ByteArray(2048))
    }
    fun prepareText(text:String,lexicon:File):String {
        require(text.all { it.code<=127 || it in "’‘“”–—…" }){"Paradee 只支持英文，请切换 Kokoro 或千问朗读中文。"}
        var normalized=text.replace('’','\'').replace('‘','\'').replace('“','"').replace('”','"').replace('–','-').replace('—','-').replace("…","...")
        val digits=listOf("zero","one","two","three","four","five","six","seven","eight","nine")
        normalized=Regex("[0-9]+").replace(normalized){match->match.value.map {digits[it-'0']}.joinToString(" ")}
        val known=lexicon.useLines { lines->lines.filter {it.isNotBlank()}.map {it.substringBefore(' ').lowercase(java.util.Locale.ROOT)}.toHashSet() }
        val words=Regex("[A-Za-z]+(?:'[A-Za-z]+)*").findAll(normalized).map {it.value}.toList()
        require(words.isNotEmpty()){"请输入英文文案"}
        val missing=words.filter {it.lowercase(java.util.Locale.ROOT) !in known}.distinct()
        require(missing.isEmpty()){"英文词典暂不包含：${missing.take(5).joinToString(", ")}。请修改拼写或切换其他模型。"}
        return normalized
    }
}
