package com.qwen.tts.android.kokoro

import android.content.Context
import com.qwen.tts.android.data.ModelStorage
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal data class KokoroVoice(val id: Int, val name: String, val code: String, val language: String)

internal class KokoroModels(context: Context,private val modelName:String="kokoro") {
    private val app=context.applicationContext
    val spec=JSONObject(app.assets.open("$modelName-model.json").bufferedReader().use { it.readText() })
    private val root=File(app.filesDir,"$modelName-models")
    val directory=File(root,spec.getString("directory"))
    val voices: List<KokoroVoice> = spec.getJSONArray("voices").let { array -> (0 until array.length()).map { index ->
        val v=array.getJSONObject(index);KokoroVoice(v.getInt("id"),v.getString("name"),v.getString("code"),v.getString("language"))
    } }
    private val required=if(spec.has("required"))spec.getJSONObject("required").let { obj->obj.keys().asSequence().associateWith {obj.getLong(it)} } else mapOf("model.int8.onnx" to 114203914L,"voices.bin" to 53790720L,"tokens.txt" to 1111L,
        "lexicon-zh.txt" to 2120131L,"lexicon-us-en.txt" to 5956885L,"date-zh.fst" to 59154L,
        "number-zh.fst" to 64482L,"phone-zh.fst" to 88630L)
    fun ready(): Boolean = runCatching {
        val marker=JSONObject(File(directory,"installed.json").readText())
        marker.getString("archiveSha256")==spec.getString("sha256") &&
            (!spec.has("adaptedSha256") || marker.optString("adaptedSha256")==spec.getString("adaptedSha256")) &&
            required.all { (name,size) -> File(directory,name).let { it.isFile && it.length()==size } }
    }.getOrDefault(false)

    fun download(cancel: AtomicBoolean, progress: (Long) -> Unit) {
        root.mkdirs();val archive=File(root,spec.getString("archive"))
        ModelStorage.download(spec.getString("url"),archive,spec.getLong("bytes"),spec.getString("sha256"),cancel::get,progress)
        install(archive,cancel);archive.delete()
    }
    fun import(input: java.io.InputStream,cancel: AtomicBoolean) {
        root.mkdirs();val archive=File(root,"import-${UUID.randomUUID()}.tar.bz2")
        try {
            input.use { source -> archive.outputStream().buffered().use { output ->
                val buffer=ByteArray(131072);var size=0L
                while(true){check(!cancel.get()){ "操作已取消" };val n=source.read(buffer);if(n<0)break;size+=n;check(size<=spec.getLong("bytes")){"模型包过大"};output.write(buffer,0,n)}
            } };install(archive,cancel)
        } finally { archive.delete() }
    }
    private fun install(archive: File,cancel: AtomicBoolean) {
        require(archive.length()==spec.getLong("bytes")){"请导入当前模型的完整官方文件"}
        val hash=MessageDigest.getInstance("SHA-256")
        archive.inputStream().buffered().use { input -> val buffer=ByteArray(131072);while(true){check(!cancel.get()){ "操作已取消" };val n=input.read(buffer);if(n<0)break;hash.update(buffer,0,n)} }
        require(hash.digest().joinToString(""){"%02x".format(it.toInt() and 255)}==spec.getString("sha256")){"模型包 SHA-256 校验失败"}
        val stage=File(root,"install-${UUID.randomUUID()}").apply { mkdirs() };var backup:File?=null
        try {
            var bytes=0L;var count=0
            if(spec.optString("kind")=="paradee-onnx") {
                ParadeeAdapter.install(app,archive,File(stage,spec.getString("directory")))
            } else {
                TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered())).use { tar ->
                    while(true){
                        check(!cancel.get()){ "操作已取消" };val entry=tar.nextEntry?:break;count++
                        val parts=entry.name.trimEnd('/').split('/')
                        require(parts.firstOrNull()==spec.getString("directory") && parts.none { it==".." } && !entry.name.contains('\\') && (entry.isFile || entry.isDirectory) && !entry.isSymbolicLink && !entry.isLink && count<=2048){"模型包含非法路径或文件"}
                        val target=File(stage,entry.name)
                        require(target.canonicalPath.startsWith(stage.canonicalPath+File.separator)){"模型路径无效"}
                        if(parts.getOrNull(1) in listOf("espeak-ng-data","dict"))continue
                        if(entry.isDirectory){target.mkdirs();continue}
                        bytes+=entry.size;require(entry.size>=0 && bytes<=450L*1024*1024){"模型解压大小超限"}
                        target.parentFile?.mkdirs();target.outputStream().buffered().use { output ->
                            val buffer=ByteArray(131072);while(true){check(!cancel.get()){ "操作已取消" };val n=tar.read(buffer);if(n<0)break;output.write(buffer,0,n)}
                        }
                    }
                }
            }
            val extracted=File(stage,spec.getString("directory"))
            check(required.all { (name,size)->File(extracted,name).let { it.isFile && it.length()==size } }){"模型缺少必要文件"}
            if(spec.has("adaptedSha256")) {
                val adapted=MessageDigest.getInstance("SHA-256").digest(File(extracted,"model.int8.onnx").readBytes()).joinToString(""){"%02x".format(it.toInt() and 255)}
                check(adapted==spec.getString("adaptedSha256")){"模型适配校验失败"}
            }
            val marker=JSONObject().put("archiveSha256",spec.getString("sha256"))
            if(spec.has("adaptedSha256"))marker.put("adaptedSha256",spec.getString("adaptedSha256"))
            File(extracted,"installed.json").writeText(marker.toString())
            check(!cancel.get()){ "操作已取消" }
            if(directory.exists()){backup=File(root,"previous-${UUID.randomUUID()}");check(directory.renameTo(backup)){"旧模型正在使用，请稍后重试"}}
            if(!extracted.renameTo(directory)){backup?.renameTo(directory);backup=null;error("模型安装失败")}
        } finally { check(stage.canonicalPath.startsWith(root.canonicalPath+File.separator));stage.deleteRecursively();backup?.let { check(it.canonicalPath.startsWith(root.canonicalPath+File.separator));it.deleteRecursively() } }
    }
    fun createTts(threads:Int): OfflineTts {
        check(ready()){ "请先下载或导入当前模型" }
        val effectiveThreads=if(threads<=0) minOf(4,Runtime.getRuntime().availableProcessors().coerceAtLeast(1)) else threads.coerceIn(1,16)
        fun file(name:String)=File(directory,name).absolutePath
        return OfflineTts(config=OfflineTtsConfig(
            model=OfflineTtsModelConfig(kokoro=OfflineTtsKokoroModelConfig(model=file("model.int8.onnx"),voices=file("voices.bin"),
                tokens=file("tokens.txt"),
                lexicon=(spec.optJSONArray("lexicons")?.let { a->(0 until a.length()).map {a.getString(it)} }?:listOf("lexicon-zh.txt","lexicon-us-en.txt")).joinToString(",",transform=::file)),numThreads=effectiveThreads,provider="cpu",debug=false),
            ruleFsts=(spec.optJSONArray("ruleFsts")?.let { a->(0 until a.length()).map {a.getString(it)} }?:listOf("phone-zh.fst","date-zh.fst","number-zh.fst")).joinToString(",",transform=::file),maxNumSentences=1))
    }
}
