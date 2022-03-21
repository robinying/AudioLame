package com.robin.audiolame




object SimpleLame {
    /**
     * pcm文件转换mp3函数
     */
    external fun convert(
        encoder: AudioEncoder?, jwav: String?, jmp3: String?,
        inSampleRate: Int, outChannel: Int, outSampleRate: Int, outBitrate: Int,
        quality: Int
    )

    init {
        System.loadLibrary("native-lib")
    }
}