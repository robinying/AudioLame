package com.robin.audiolame

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import java.io.*
import java.nio.ByteBuffer


class AudioEncoder {
    private val audioHandler: Handler
    private val audioThread: HandlerThread
    private var encoderListener: OnAudioEncoderListener? = null
    fun setEncoderListener(encoderListener: OnAudioEncoderListener?) {
        this.encoderListener = encoderListener
    }

    fun start(path: String, list: List<AudioHolderBean>) {
        audioHandler.post(Runnable { encoders(path, list) })
    }

    fun start(path: String, list: List<AudioHolderBean>, encoderListener: OnAudioEncoderListener?) {
        this.encoderListener = encoderListener
        start(path, list)
    }

    private var audioTrackIndex = 0
    private var decoderHolder: AudioHolderBean? = null

    /**
     * 进行解码和拼接
     */
    private fun encoders(path: String, list: List<AudioHolderBean>) {
        val file = File(path)
        if (file.exists()) {
            file.delete()
        }
        //统一采样率，比特率和声道
        var bitRate: Int = list[0].bitRate
        var sampleRate: Int = list[0].sampleRate
        var channelCount: Int = list[0].channelCount
        if (list.size != 1) {
            for (holder in list) {
                bitRate = Math.min(bitRate, holder.bitRate)
                sampleRate = Math.min(sampleRate, holder.sampleRate)
                channelCount = Math.min(channelCount, holder.channelCount)
            }
            sampleRate = format(sampleRate, SampleRates)
            bitRate = if (sampleRate >= SampleRates[2]) {
                format(bitRate, Mpeg1BitRates)
            } else if (sampleRate <= SampleRates[6]) {
                format(bitRate, Mpeg25BitRates)
            } else {
                format(bitRate, Mpeg2BitRates)
            }
        }

        //临时用的pcm文件
        val pcm =
            Environment.getExternalStorageDirectory().absolutePath + "/robin/" + System.currentTimeMillis() + ".pcm"
        val mp3s: MutableList<String> = ArrayList()
        //总时长，用来计算进度用的
        var duration: Long = 0
        for (holder in list) {
            //只有1个音频的时候直接转mp3
            var mp3: String
            if (list.size == 1) {
                mp3 = path
                decoderHolder = null
            } else {
                decoderHolder = holder
                mp3 =
                    Environment.getExternalStorageDirectory().absolutePath + "/robin/" + System.currentTimeMillis() + ".mp3"
            }
            //将音频解码成pcm文件
            duration += decoderPCM(holder, pcm)
            //把pcm文件转成mp3
            SimpleLame.convert(
                this, pcm, mp3, holder.sampleRate,
                channelCount, sampleRate, bitRate,
                1
            )
            mp3s.add(mp3)
        }
        //只有一个音频就完成操作
        if (list.size == 1) {
            if (encoderListener != null) {
                encoderListener!!.onOver(path)
            }
            return
        }
        //以下可换成其他代码，比如用MediaCodec转成aac，因为采样率,比特率和声道都是一样的文件
        decoderHolder = null
        val f = File(pcm)
        if (f.exists()) {
            f.delete()
        }
        var pcmos: OutputStream? = null
        try {
            pcmos = FileOutputStream(pcm)
        } catch (e: FileNotFoundException) {
            e.printStackTrace()
        }
        //文件总大小
        var total: Long = 0
        for (mp3 in mp3s) {
            //将mp3转成pcm文件返回转换数据的大小
            total += encoderMP3(mp3, pcmos, total, duration)
        }
        try {
            pcmos?.flush()
            pcmos?.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
        //把pcm文件转成mp3
        SimpleLame.convert(
            this, pcm, path, sampleRate,
            channelCount, sampleRate, bitRate,
            1
        )
        if (encoderListener != null) {
            encoderListener!!.onOver(path)
        }
    }

    /**
     * 进行解码
     */
    private fun decoderPCM(holder: AudioHolderBean, pcm: String): Long {
        val startTime = (holder.start * 1000 * 1000) as Long
        val endTime = (holder.end * 1000 * 1000) as Long
        //初始化MediaExtractor和MediaCodec
        val audioExtractor = MediaExtractor()
        var audioDecoder: MediaCodec? = null
        try {
            audioExtractor.setDataSource(holder.file!!)
            for (i in 0 until audioExtractor.trackCount) {
                val format = audioExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime!!.startsWith(AUDIO)) {
                    audioExtractor.selectTrack(i)
                    audioTrackIndex = i
                    if (startTime != 0L) {
                        audioExtractor.seekTo(startTime, audioTrackIndex)
                    }
                    audioDecoder = MediaCodec.createDecoderByType(mime)
                    audioDecoder.configure(format, null, null, 0)
                    audioDecoder.start()
                    break
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
        val f = File(pcm)
        if (f.exists()) {
            f.delete()
        }
        //pcm文件
        var pcmos: OutputStream? = null
        try {
            pcmos = FileOutputStream(f)
        } catch (e: FileNotFoundException) {
            e.printStackTrace()
        }
        //这段音频的时长
        val duration = endTime - startTime
        val info = MediaCodec.BufferInfo()
        while (true) {
            extractorInputBuffer(audioExtractor, audioDecoder)
            val outIndex = audioDecoder!!.dequeueOutputBuffer(info, 50000)
            if (outIndex >= 0) {
                val data: ByteBuffer? = audioDecoder.getOutputBuffer(outIndex)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    info.size = 0
                }
                if (info.size != 0) {
                    //判断解码出来的数据是否在截取的范围内
                    if (info.presentationTimeUs >= startTime && info.presentationTimeUs <= endTime) {
                        val bytes = ByteArray(data!!.remaining())
                        data.get(bytes, 0, bytes.size)
                        data.clear()
                        //写入pcm文件
                        try {
                            pcmos?.write(bytes)
                        } catch (e: IOException) {
                            e.printStackTrace()
                        }
                        //进度条
                        if (encoderListener != null) {
                            val progress = ((info.presentationTimeUs - startTime) * 50 / duration).toInt()
                            if (decoderHolder == null) {
                                encoderListener!!.onEncoder(progress)
                            } else {
                                encoderListener!!.onDecoder(decoderHolder, progress)
                            }
                        }
                    }
                }
                audioDecoder.releaseOutputBuffer(outIndex, false)
                //超过截取时间结束解码
                if (info.presentationTimeUs >= endTime) {
                    break
                }
            }
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                break
            }
        }
        try {
            pcmos?.flush()
            pcmos?.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
        audioDecoder.stop()
        audioDecoder.release()
        audioExtractor.release()
        return duration
    }

    /**
     * mp3转pcm
     */
    private fun encoderMP3(mp3: String, pcmos: OutputStream?, startTime: Long, duration: Long): Long {
        var d: Long = 0
        val audioExtractor = MediaExtractor()
        var audioDecoder: MediaCodec? = null
        try {
            audioExtractor.setDataSource(mp3)
            for (i in 0 until audioExtractor.trackCount) {
                val format = audioExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime!!.startsWith(AUDIO)) {
                    d = format.getLong(MediaFormat.KEY_DURATION)
                    audioExtractor.selectTrack(i)
                    audioDecoder = MediaCodec.createDecoderByType(mime)
                    audioDecoder.configure(format, null, null, 0)
                    audioDecoder.start()
                    break
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
        val info = MediaCodec.BufferInfo()
        while (true) {
            extractorInputBuffer(audioExtractor, audioDecoder)
            val outIndex = audioDecoder!!.dequeueOutputBuffer(info, 50000)
            if (outIndex >= 0) {
                val data: ByteBuffer? = audioDecoder.getOutputBuffer(outIndex)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    info.size = 0
                }
                if (info.size != 0) {
                    val bytes = ByteArray(data!!.remaining())
                    data.get(bytes, 0, bytes.size)
                    data.clear()
                    try {
                        pcmos?.write(bytes)
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                    if (encoderListener != null) {
                        val progress = ((info.presentationTimeUs + startTime) * 50 / duration).toInt()
                        encoderListener!!.onEncoder(progress)
                    }
                }
                audioDecoder.releaseOutputBuffer(outIndex, false)
            }
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                break
            }
        }
        audioDecoder.stop()
        audioDecoder.release()
        audioExtractor.release()
        return d
    }

    private fun extractorInputBuffer(mediaExtractor: MediaExtractor, mediaCodec: MediaCodec?) {
        val inputIndex = mediaCodec!!.dequeueInputBuffer(50000)
        if (inputIndex >= 0) {
            val inputBuffer: ByteBuffer? = mediaCodec.getInputBuffer(inputIndex)
            val sampleTime = mediaExtractor.sampleTime
            inputBuffer?.let {
                val sampleSize = mediaExtractor.readSampleData(it, 0)
                if (mediaExtractor.advance()) {
                    mediaCodec.queueInputBuffer(inputIndex, 0, sampleSize, sampleTime, 0)
                } else {
                    if (sampleSize > 0) {
                        mediaCodec.queueInputBuffer(
                            inputIndex,
                            0,
                            sampleSize,
                            sampleTime,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                    } else {
                        mediaCodec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    }
                }
            }

        }
    }

    private fun format(f: Int, fs: IntArray): Int {
        if (f >= fs[0]) {
            return fs[0]
        } else if (f <= fs[fs.size - 1]) {
            return fs[fs.size - 1]
        } else {
            for (i in 1 until fs.size) {
                if (f >= fs[i]) {
                    return fs[i]
                }
            }
        }
        return -1
    }

    /**
     * jni回调的进度条函数，进度条以解码占50，pcm转mp3占50
     */
    fun setProgress(size: Long, total: Long) {
        if (encoderListener != null) {
            val progress = 50 + (total * 50 / size).toInt()
            if (decoderHolder == null) {
                encoderListener!!.onEncoder(progress)
            } else {
                encoderListener!!.onDecoder(decoderHolder, progress)
            }
        }
    }

    interface OnAudioEncoderListener {
        fun onDecoder(decoderHolder: AudioHolderBean?, progress: Int)
        fun onEncoder(progress: Int)
        fun onOver(path: String?)
    }

    companion object {
        private const val AUDIO = "audio/"
        private val SampleRates = intArrayOf(48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000)
        private val Mpeg1BitRates = intArrayOf(320, 256, 224, 192, 160, 128, 112, 96, 80, 64, 56, 48, 40, 32)
        private val Mpeg2BitRates = intArrayOf(160, 144, 128, 112, 96, 80, 64, 56, 48, 40, 32, 24, 16, 8)
        private val Mpeg25BitRates = intArrayOf(64, 56, 48, 40, 32, 24, 16, 8)
    }

    init {
        audioThread = HandlerThread("AudioEncoder")
        audioThread.start()
        audioHandler = Handler(audioThread.looper)
    }
}