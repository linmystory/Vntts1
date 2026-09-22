package com.docdoc.app

// ============================================================================
// TtsFileSaverPlugin.kt
//
// Plugin Capacitor native cho Android, dùng để tổng hợp văn bản thành GIỌNG NÓI
// và LƯU RA FILE WAV THẬT trên thiết bị, sử dụng bộ máy TextToSpeech (TTS) có
// sẵn của hệ điều hành Android — hoàn toàn offline, không cần API key hay
// dịch vụ đám mây nào.
//
// Vì sao cần plugin native riêng?
// Web Speech API (window.speechSynthesis) chạy trong WebView CHỈ có thể phát
// âm thanh trực tiếp ra loa, không cho phép lấy dữ liệu âm thanh dưới dạng
// file. Ngược lại, Android cung cấp hàm gốc:
//     TextToSpeech.synthesizeToFile(text, params, file, utteranceId)
// hàm này ghi thẳng dữ liệu âm thanh (PCM/WAV) ra file mà KHÔNG phát ra loa.
// Plugin này gọi hàm đó, xử lý việc chia nhỏ văn bản dài (do TTS có giới hạn
// độ dài mỗi lần tổng hợp), rồi ghép nhiều đoạn WAV lại thành một file hoàn
// chỉnh duy nhất.
//
// Cách cài đặt: xem HUONG_DAN_CAI_DAT.md đi kèm trong dự án.
// ============================================================================

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.core.content.FileProvider
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@CapacitorPlugin(name = "TtsFileSaver")
class TtsFileSaverPlugin : Plugin() {

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val executor = Executors.newSingleThreadExecutor()

    // Độ dài an toàn mỗi đoạn văn bản gửi cho TTS (ký tự). Android TTS có giới
    // hạn thực tế do TextToSpeech.getMaxSpeechInputLength() cung cấp, nhưng ta
    // dùng một ngưỡng an toàn nhỏ hơn để tránh lỗi trên các máy/engine khác nhau.
    private val SAFE_CHUNK_LEN = 350

    override fun load() {
        super.load()
        tts = TextToSpeech(context) { status ->
            ttsReady = (status == TextToSpeech.SUCCESS)
        }
    }

    // ------------------------------------------------------------------
    // Hàm chính: nhận văn bản, tùy chọn giọng/tốc độ/cao độ, trả về Uri file
    // ------------------------------------------------------------------
    @PluginMethod
    fun synthesizeToFile(call: PluginCall) {
        val text = call.getString("text")
        if (text.isNullOrBlank()) {
            call.reject("Thiếu văn bản cần đọc (text).")
            return
        }
        if (!ttsReady || tts == null) {
            call.reject("Bộ máy Text-to-Speech chưa sẵn sàng. Vui lòng thử lại sau vài giây.")
            return
        }

        val voiceName = call.getString("voiceName") ?: ""
        val lang = call.getString("lang") ?: "vi-VN"
        val rate = (call.getFloat("rate") ?: 1.0f)
        val pitch = (call.getFloat("pitch") ?: 1.0f)

        executor.execute {
            try {
                applyVoiceSettings(voiceName, lang, rate, pitch)
                val chunks = splitIntoChunks(text, SAFE_CHUNK_LEN)
                if (chunks.isEmpty()) {
                    call.reject("Văn bản rỗng sau khi xử lý.")
                    return@execute
                }

                val tmpDir = File(context.cacheDir, "tts_tmp_${System.currentTimeMillis()}")
                tmpDir.mkdirs()

                val chunkFiles = ArrayList<File>()
                var synthesisFailed = false
                var failMessage = ""

                for ((index, chunkText) in chunks.withIndex()) {
                    val chunkFile = File(tmpDir, "chunk_%04d.wav".format(index))
                    val utteranceId = "chunk_$index"
                    val latch = CountDownLatch(1)
                    var thisChunkFailed = false

                    val listener = object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) {
                            if (id == utteranceId) latch.countDown()
                        }
                        @Deprecated("Deprecated in Java")
                        override fun onError(id: String?) {
                            if (id == utteranceId) {
                                thisChunkFailed = true
                                latch.countDown()
                            }
                        }
                        override fun onError(id: String?, errorCode: Int) {
                            if (id == utteranceId) {
                                thisChunkFailed = true
                                latch.countDown()
                            }
                        }
                    }

                    tts!!.setOnUtteranceProgressListener(listener)

                    val params = Bundle()
                    val result = tts!!.synthesizeToFile(chunkText, params, chunkFile, utteranceId)
                    if (result != TextToSpeech.SUCCESS) {
                        thisChunkFailed = true
                        latch.countDown()
                    }

                    // Chờ tối đa 20 giây cho mỗi đoạn (đoạn đã được giới hạn ngắn nên rất hiếm khi cần lâu vậy)
                    val finished = latch.await(20, TimeUnit.SECONDS)
                    if (!finished || thisChunkFailed || !chunkFile.exists() || chunkFile.length() == 0L) {
                        synthesisFailed = true
                        failMessage = "Không thể tổng hợp đoạn văn bản thứ ${index + 1}/${chunks.size}."
                        break
                    }
                    chunkFiles.add(chunkFile)
                }

                if (synthesisFailed) {
                    tmpDir.deleteRecursively()
                    call.reject(failMessage)
                    return@execute
                }

                // Ghép các file WAV nhỏ thành 1 file WAV hoàn chỉnh
                val mergedFile = File(context.cacheDir, "tts_output_${System.currentTimeMillis()}.wav")
                mergeWavFiles(chunkFiles, mergedFile)
                tmpDir.deleteRecursively()

                // Lưu file vào bộ nhớ công khai (Music/DocDocTTS) để người dùng dễ tìm lại,
                // đồng thời trả về content Uri để JS có thể chia sẻ/mở file.
                val fileName = "doc-van-ban-${System.currentTimeMillis()}.wav"
                val savedUri = saveWavToPublicStorage(mergedFile, fileName)
                mergedFile.delete()

                if (savedUri == null) {
                    call.reject("Không thể lưu file âm thanh vào bộ nhớ thiết bị.")
                    return@execute
                }

                val ret = JSObject()
                ret.put("uri", savedUri.toString())
                ret.put("fileName", fileName)
                call.resolve(ret)

            } catch (e: Exception) {
                call.reject("Lỗi khi tạo file âm thanh: ${e.message}", e)
            }
        }
    }

    // ------------------------------------------------------------------
    // Mở hộp thoại Chia sẻ / Lưu của Android cho file vừa tạo
    // ------------------------------------------------------------------
    @PluginMethod
    fun shareFile(call: PluginCall) {
        val uriString = call.getString("uri")
        if (uriString.isNullOrBlank()) {
            call.reject("Thiếu đường dẫn file (uri).")
            return
        }
        try {
            val uri = Uri.parse(uriString)
            val intent = Intent(Intent.ACTION_SEND)
            intent.type = "audio/wav"
            intent.putExtra(Intent.EXTRA_STREAM, uri)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val chooser = Intent.createChooser(intent, "Lưu / Chia sẻ file âm thanh")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            call.resolve()
        } catch (e: Exception) {
            call.reject("Không thể mở hộp thoại chia sẻ: ${e.message}", e)
        }
    }

    // ------------------------------------------------------------------
    // Chọn giọng nói + áp dụng tốc độ/cao độ cho TTS engine
    // ------------------------------------------------------------------
    private fun applyVoiceSettings(voiceName: String, lang: String, rate: Float, pitch: Float) {
        val engine = tts ?: return
        var chosenVoice: Voice? = null

        if (voiceName.isNotBlank()) {
            chosenVoice = engine.voices?.firstOrNull { it.name == voiceName }
        }
        if (chosenVoice == null && lang.isNotBlank()) {
            val locale = parseLocale(lang)
            chosenVoice = engine.voices?.firstOrNull { it.locale.language == locale.language }
        }
        if (chosenVoice != null) {
            engine.voice = chosenVoice
        } else if (lang.isNotBlank()) {
            engine.language = parseLocale(lang)
        }

        engine.setSpeechRate(rate)
        engine.setPitch(pitch)
    }

    private fun parseLocale(bcp47: String): Locale {
        return try {
            Locale.forLanguageTag(bcp47)
        } catch (e: Exception) {
            Locale("vi", "VN")
        }
    }

    // ------------------------------------------------------------------
    // Tách văn bản dài thành các đoạn ngắn theo câu, an toàn cho TTS
    // ------------------------------------------------------------------
    private fun splitIntoChunks(text: String, maxLen: Int): List<String> {
        val normalized = text.replace("\r\n", "\n").trim()
        if (normalized.isEmpty()) return emptyList()

        val sentenceRegex = Regex("(?<=[.!?…])\\s+|\\n+")
        val rawSentences = normalized.split(sentenceRegex).map { it.trim() }.filter { it.isNotEmpty() }

        val chunks = ArrayList<String>()
        var buffer = StringBuilder()

        fun flushBuffer() {
            if (buffer.isNotEmpty()) {
                chunks.add(buffer.toString())
                buffer = StringBuilder()
            }
        }

        for (sentence in rawSentences) {
            if (sentence.length > maxLen) {
                flushBuffer()
                var remaining = sentence
                while (remaining.length > maxLen) {
                    var cut = remaining.lastIndexOf(' ', maxLen)
                    if (cut <= 0) cut = maxLen
                    chunks.add(remaining.substring(0, cut).trim())
                    remaining = remaining.substring(cut).trim()
                }
                if (remaining.isNotEmpty()) buffer.append(remaining)
                continue
            }

            val candidateLen = buffer.length + 1 + sentence.length
            if (candidateLen > maxLen) {
                flushBuffer()
                buffer.append(sentence)
            } else {
                if (buffer.isNotEmpty()) buffer.append(' ')
                buffer.append(sentence)
            }
        }
        flushBuffer()
        return chunks
    }

    // ------------------------------------------------------------------
    // Đọc header của 1 file WAV để lấy vị trí + kích thước phần dữ liệu âm thanh (PCM)
    // ------------------------------------------------------------------
    private data class WavInfo(val dataOffset: Int, val dataSize: Int, val headerBytes: ByteArray)

    private fun readWavInfo(file: File): WavInfo {
        FileInputStream(file).use { input ->
            val header = ByteArray(44)
            var read = 0
            while (read < 44) {
                val n = input.read(header, read, 44 - read)
                if (n < 0) break
                read += n
            }
            // Tìm chunk "data" (không phải lúc nào cũng nằm đúng byte 36-44 tuyệt đối,
            // nhưng với file do Android TTS tạo ra thì theo chuẩn RIFF/WAVE cơ bản này là đủ)
            var dataSize = ((header[43].toInt() and 0xFF) shl 24) or
                    ((header[42].toInt() and 0xFF) shl 16) or
                    ((header[41].toInt() and 0xFF) shl 8) or
                    (header[40].toInt() and 0xFF)
            if (dataSize <= 0) {
                dataSize = (file.length() - 44).toInt()
            }
            return WavInfo(44, dataSize, header)
        }
    }

    // ------------------------------------------------------------------
    // Ghép nhiều file WAV nhỏ thành 1 file WAV hoàn chỉnh (dùng chung format của file đầu tiên)
    // ------------------------------------------------------------------
    private fun mergeWavFiles(files: List<File>, outFile: File) {
        if (files.isEmpty()) throw IOException("Không có đoạn âm thanh nào để ghép.")

        val firstInfo = readWavInfo(files[0])
        val headerTemplate = firstInfo.headerBytes.copyOf()

        var totalDataSize = 0L
        for (f in files) {
            totalDataSize += (f.length() - 44).coerceAtLeast(0)
        }

        BufferedOutputStream(FileOutputStream(outFile)).use { out ->
            // Ghi header tạm, sẽ cập nhật lại kích thước sau
            writeWavHeader(out, totalDataSize, headerTemplate)
            for (f in files) {
                FileInputStream(f).use { input ->
                    input.skip(44)
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                    }
                }
            }
        }
    }

    private fun writeWavHeader(out: java.io.OutputStream, dataSize: Long, template: ByteArray) {
        val byteRate = readInt32LE(template, 28)
        val sampleRate = readInt32LE(template, 24)
        val channels = readInt16LE(template, 22)
        val bitsPerSample = readInt16LE(template, 34)
        val blockAlign = readInt16LE(template, 32)

        val totalDataLen = dataSize + 36
        val header = ByteArray(44)

        writeAscii(header, 0, "RIFF")
        writeInt32LE(header, 4, totalDataLen.toInt())
        writeAscii(header, 8, "WAVE")
        writeAscii(header, 12, "fmt ")
        writeInt32LE(header, 16, 16) // Subchunk1Size cho PCM
        writeInt16LE(header, 20, 1)  // AudioFormat = 1 (PCM)
        writeInt16LE(header, 22, channels)
        writeInt32LE(header, 24, sampleRate)
        writeInt32LE(header, 28, byteRate)
        writeInt16LE(header, 32, blockAlign)
        writeInt16LE(header, 34, bitsPerSample)
        writeAscii(header, 36, "data")
        writeInt32LE(header, 40, dataSize.toInt())

        out.write(header)
    }

    private fun readInt16LE(b: ByteArray, offset: Int): Int {
        return (b[offset].toInt() and 0xFF) or ((b[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readInt32LE(b: ByteArray, offset: Int): Int {
        return (b[offset].toInt() and 0xFF) or
                ((b[offset + 1].toInt() and 0xFF) shl 8) or
                ((b[offset + 2].toInt() and 0xFF) shl 16) or
                ((b[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun writeInt16LE(b: ByteArray, offset: Int, value: Int) {
        b[offset] = (value and 0xFF).toByte()
        b[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun writeInt32LE(b: ByteArray, offset: Int, value: Int) {
        b[offset] = (value and 0xFF).toByte()
        b[offset + 1] = ((value shr 8) and 0xFF).toByte()
        b[offset + 2] = ((value shr 16) and 0xFF).toByte()
        b[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun writeAscii(b: ByteArray, offset: Int, text: String) {
        val bytes = text.toByteArray(Charsets.US_ASCII)
        System.arraycopy(bytes, 0, b, offset, bytes.size)
    }

    // ------------------------------------------------------------------
    // Lưu file WAV vào bộ nhớ công khai của thiết bị (Music/DocDocTTS)
    // Dùng MediaStore (Android 10+, không cần xin quyền runtime).
    // ------------------------------------------------------------------
    private fun saveWavToPublicStorage(sourceFile: File, displayName: String): Uri? {
        val resolver = context.contentResolver

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/DocDocTTS")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val itemUri = resolver.insert(collection, values) ?: return null

            resolver.openOutputStream(itemUri)?.use { out ->
                FileInputStream(sourceFile).use { input ->
                    input.copyTo(out)
                }
            } ?: return null

            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(itemUri, values, null, null)
            return itemUri
        } else {
            // Android 9 trở xuống: cần quyền WRITE_EXTERNAL_STORAGE (khai báo trong AndroidManifest.xml)
            val musicDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                "DocDocTTS"
            )
            if (!musicDir.exists()) musicDir.mkdirs()
            val destFile = File(musicDir, displayName)
            FileInputStream(sourceFile).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            return FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                destFile
            )
        }
    }

    override fun handleOnDestroy() {
        super.handleOnDestroy()
        tts?.shutdown()
        executor.shutdownNow()
    }
}
