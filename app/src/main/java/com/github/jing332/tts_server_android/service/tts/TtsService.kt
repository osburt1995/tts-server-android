package com.github.jing332.tts_server_android.service.tts

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.text.TextUtils
import android.util.Log
import android.widget.Toast
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.nio.ByteBuffer
import java.util.*


class TtsService : TextToSpeechService() {
    private val TAG = "TtsService"
    private val currentLanguage: MutableList<String> = mutableListOf("zho", "CHN", "")

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        return if (Locale.SIMPLIFIED_CHINESE.isO3Language == lang || Locale.US.isO3Language == lang) {
            if (Locale.SIMPLIFIED_CHINESE.isO3Country == country || Locale.US.isO3Country == country) TextToSpeech.LANG_COUNTRY_AVAILABLE else TextToSpeech.LANG_AVAILABLE
        } else TextToSpeech.LANG_NOT_SUPPORTED
    }

    override fun onGetLanguage(): Array<String> {
        Log.i(TAG, "onGetLanguage: ${currentLanguage.toTypedArray()}")
        return currentLanguage.toTypedArray()
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val result = onIsLanguageAvailable(lang, country, variant)
        Log.i(TAG, "onLoadLanguage ret: $result, $lang, $country, $variant")
        currentLanguage.clear()
        currentLanguage.addAll(
            mutableListOf(
                lang.toString(),
                country.toString(),
                variant.toString()
            )
        )
        return result
    }

    override fun onStop() {
        Log.e("TTS", "onStop")
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val arg = tts_server_lib.CreationArg()
        arg.text = request?.charSequenceText.toString()
        val rate = request?.speechRate?.toFloat()

        arg.voiceName = "zh-CN-XiaoxiaoNeural"
        arg.voiceId = "5f55541d-c844-4e04-a7f8-1723ffbea4a9"
        arg.style = "general"
        arg.styleDegree = "1.0"
        arg.role = "default"
        if (rate != null) {
            arg.rate = "${(rate - 20 * 2)}%"
        }
        arg.volume = "0%"
        arg.format = "audio-24khz-48kbitrate-mono-mp3"
        val format = TtsFormatManger.getFormat(arg.format)
        if (format == null) {
            Log.e(TAG, "不支持解码此格式: ${arg.format}")
            return
        }
        Log.e(TAG, "${arg.rate}")

        callback?.start(format.HZ, format.BitRate.toInt(), 1)
        try {
            val audio = tts_server_lib.Tts_server_lib.getCreationAudio(arg)
            Log.e(TAG, "获取成功, size: ${audio.size}")

            doDecode(callback!!, "", audio)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    var isSynthesizing = false
    private val currentMime: String? = null
    private var mediaCodec: MediaCodec? = null
    private var oldMime: String? = null

    /**
     * 根据mime创建MediaCodec
     * 当Mime未变化时复用MediaCodec
     *
     * @param mime mime
     * @return MediaCodec
     */
    private fun getMediaCodec(mime: String, mediaFormat: MediaFormat): MediaCodec {
        if (mediaCodec == null || mime != oldMime) {
            if (null != mediaCodec) {
                mediaCodec!!.release()
//                GcManger.getInstance().doGC()
            }
            try {
                mediaCodec = MediaCodec.createDecoderByType(mime)
                oldMime = mime
            } catch (ioException: IOException) {
                //设备无法创建，直接抛出
                ioException.printStackTrace()
                throw RuntimeException(ioException)
            }
        }
        mediaCodec!!.reset()
        mediaCodec!!.configure(mediaFormat, null, null, 0)
        return mediaCodec as MediaCodec
    }


    @Synchronized
    private fun doDecode(cb: SynthesisCallback, format: String, data: ByteArray) {
        isSynthesizing = true
        try {
            val mediaExtractor = MediaExtractor()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                //在高版本上使用自定义MediaDataSource
                mediaExtractor.setDataSource(ByteArrayMediaDataSource(data))
            } else {
                //在低版本上使用Base64音频数据
                mediaExtractor.setDataSource(
                    "data:" + currentMime.toString() + ";base64," + data.toByteString().base64()
                )
            }

            //找到音频流的索引
            var audioTrackIndex = -1
            var mime: String? = null
            var trackFormat: MediaFormat? = null
            for (i in 0 until mediaExtractor.trackCount) {
                trackFormat = mediaExtractor.getTrackFormat(i)
                mime = trackFormat.getString(MediaFormat.KEY_MIME)
                if (!TextUtils.isEmpty(mime) && mime!!.startsWith("audio")) {
                    audioTrackIndex = i
                    Log.d(TAG, "找到音频流的索引为：$audioTrackIndex")
                    Log.d(TAG, "找到音频流的mime为：$mime")
                    break
                }
            }
            //没有找到音频流的情况下
            if (audioTrackIndex == -1) {
                Log.e(TAG, "initAudioDecoder: 没有找到音频流")
//                updateNotification("TTS服务-错误中", "没有找到音频流")
                cb.done()
                isSynthesizing = false
                return
            }

            //Log.e("Track", trackFormat.toString());


            //opus的音频必须设置这个才能正确的解码
            /* if ("audio/opus" == mime) {
                 //Log.d(TAG, ByteString.of(trackFormat.getByteBuffer("csd-0")).hex());
                 val buf = Buffer()
                 // Magic Signature：固定头，占8个字节，为字符串OpusHead
                 buf.write("OpusHead".getBytes(StandardCharsets.UTF_8))
                 // Version：版本号，占1字节，固定为0x01
                 buf.writeByte(1)
                 // Channel Count：通道数，占1字节，根据音频流通道自行设置，如0x02
                 buf.writeByte(1)
                 // Pre-skip：回放的时候从解码器中丢弃的samples数量，占2字节，为小端模式，默认设置0x00,
                 buf.writeShortLe(0)
                 // Input Sample Rate (Hz)：音频流的Sample Rate，占4字节，为小端模式，根据实际情况自行设置
                 buf.writeIntLe(currentFormat.HZ)
                 //Output Gain：输出增益，占2字节，为小端模式，没有用到默认设置0x00, 0x00就好
                 buf.writeShortLe(0)
                 // Channel Mapping Family：通道映射系列，占1字节，默认设置0x00就好
                 buf.writeByte(0)
                 //Channel Mapping Table：可选参数，上面的Family默认设置0x00的时候可忽略
                 if (BuildConfig.DEBUG) {
                     Log.e(TAG,
                         trackFormat!!.getByteBuffer("csd-1")!!
                             .order(ByteOrder.nativeOrder()).long.toString() + ""
                     )
                     Log.e(TAG,
                         trackFormat.getByteBuffer("csd-2")!!
                             .order(ByteOrder.nativeOrder()).long.toString() + ""
                     )
                     Log.e(TAG, ByteString.of(*trackFormat.getByteBuffer("csd-2")!!.array()).hex())
                 }
                 val csd1bytes = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
                 val csd2bytes = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
                 val hd: ByteString = buf.readByteString()
                 val csd0: ByteBuffer = ByteBuffer.wrap(hd.toByteArray())
                 trackFormat!!.setByteBuffer("csd-0", csd0)
                 val csd1: ByteBuffer = ByteBuffer.wrap(csd1bytes)
                 trackFormat.setByteBuffer("csd-1", csd1)
                 val csd2: ByteBuffer = ByteBuffer.wrap(csd2bytes)
                 trackFormat.setByteBuffer("csd-2", csd2)
             }*/

            //选择此音轨
            mediaExtractor.selectTrack(audioTrackIndex)

            //创建解码器
            val mediaCodec: MediaCodec =
                getMediaCodec(
                    mime.toString(),
                    trackFormat!!
                ) //MediaCodec.createDecoderByType(mime);
            mediaCodec.start()
            val bufferInfo = MediaCodec.BufferInfo()
            var inputBuffer: ByteBuffer?
            val TIME_OUT_US: Long = 10000
            while (isSynthesizing) {
                //获取可用的inputBuffer，输入参数-1代表一直等到，0代表不等待，10*1000代表10秒超时
                //超时时间10秒
                val inputIndex = mediaCodec.dequeueInputBuffer(TIME_OUT_US)
                if (inputIndex < 0) {
                    break
                }
                bufferInfo.presentationTimeUs = mediaExtractor.sampleTime
                //bufferInfo.flags=mediaExtractor.getSampleFlags();
                inputBuffer = mediaCodec.getInputBuffer(inputIndex)
                if (inputBuffer != null) {
                    inputBuffer.clear()
                } else {
                    continue
                }
                //从流中读取的采用数据的大小
                val sampleSize = mediaExtractor.readSampleData(inputBuffer, 0)
                if (sampleSize > 0) {
                    bufferInfo.size = sampleSize
                    //入队解码
                    mediaCodec.queueInputBuffer(inputIndex, 0, sampleSize, 0, 0)
                    //移动到下一个采样点
                    mediaExtractor.advance()
                } else {
                    break
                }

                //取解码后的数据
                var outputIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, TIME_OUT_US)
                //不一定能一次取完，所以要循环取
                var outputBuffer: ByteBuffer?
                var pcmData: ByteArray
                while (outputIndex >= 0) {
                    outputBuffer = mediaCodec.getOutputBuffer(outputIndex)
                    pcmData = ByteArray(bufferInfo.size)
                    if (outputBuffer != null) {
                        outputBuffer.get(pcmData)
                        outputBuffer.clear() //用完后清空，复用
                    }
                    cb.audioAvailable(pcmData, 0, bufferInfo.size)
                    //释放
                    mediaCodec.releaseOutputBuffer(outputIndex, false)
                    //再次获取数据
                    outputIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, TIME_OUT_US)
                }
            }
            mediaCodec.reset()
            cb.done()
            isSynthesizing = false
        } catch (e: Exception) {
            Log.e(TAG, "doDecode", e)
            cb.error()
            isSynthesizing = false
            //GcManger.getInstance().doGC();
        }
    }


    @Synchronized
    private fun doUnDecode(cb: SynthesisCallback, format: String, data: ByteString) {
        isSynthesizing = true
        val length: Int = data.toByteArray().size
        //最大BufferSize
        val maxBufferSize = cb.maxBufferSize
        var offset = 0
        while (offset < length && isSynthesizing) {
            val bytesToWrite = Math.min(maxBufferSize, length - offset)
            cb.audioAvailable(data.toByteArray(), offset, bytesToWrite)
            offset += bytesToWrite
        }
        cb.done()
        isSynthesizing = false
    }

}