package com.freedom.ocr.plugin.runtime

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.ParcelFileDescriptor
import org.autojs.plugin.paddle.ocr.api.PaddleOcrOptionExtraKeys
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Adapted from AutoJs6 Paddle OCR plugin's ImageFdDecoder. Keeps image decoding in the plugin
 * process and accepts both encoded images and the raw ARGB_8888 transport defined by its AIDL API.
 */
internal object OcrImageFdDecoder {
    fun decode(imageFd: ParcelFileDescriptor, extras: Bundle?): Bitmap {
        try {
            if (extras?.getBoolean(PaddleOcrOptionExtraKeys.RAW_IMAGE, false) == true) {
                return decodeRaw(imageFd, extras)
            }
            imageFd.use { descriptor ->
                return BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor)
                    ?: throw IllegalArgumentException("OCR 插件无法解码传入的图片")
            }
        } catch (error: IOException) {
            throw IllegalStateException("读取 OCR 图片失败", error)
        } finally {
            runCatching { imageFd.close() }
        }
    }

    private fun decodeRaw(descriptor: ParcelFileDescriptor, extras: Bundle): Bitmap {
        val width = extras.getInt(PaddleOcrOptionExtraKeys.RAW_WIDTH, -1)
        val height = extras.getInt(PaddleOcrOptionExtraKeys.RAW_HEIGHT, -1)
        val stride = extras.getInt(PaddleOcrOptionExtraKeys.RAW_STRIDE, width * 4)
        val config = extras.getString(PaddleOcrOptionExtraKeys.RAW_CONFIG)
        require(width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION) { "原始 OCR 图片尺寸无效" }
        require(config == Bitmap.Config.ARGB_8888.name) { "原始 OCR 图片必须使用 ARGB_8888" }
        val rowBytes = Math.multiplyExact(width, 4)
        require(stride >= rowBytes && stride <= MAX_ROW_BYTES) { "原始 OCR 图片 stride 无效" }
        val totalBytes = Math.multiplyExact(stride, height)
        require(totalBytes <= MAX_RAW_BYTES) { "原始 OCR 图片超过大小限制" }
        val raw = ByteArray(totalBytes)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            var offset = 0
            while (offset < raw.size) {
                val count = input.read(raw, offset, raw.size - offset)
                if (count < 0) break
                offset += count
            }
            require(offset == raw.size) { "原始 OCR 图片数据不完整：$offset/${raw.size}" }
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        if (stride == rowBytes && bitmap.rowBytes == rowBytes) {
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(raw))
        } else {
            val packed = ByteBuffer.allocate(Math.multiplyExact(rowBytes, height))
            var sourceOffset = 0
            repeat(height) {
                packed.put(raw, sourceOffset, rowBytes)
                sourceOffset += stride
            }
            packed.flip()
            bitmap.copyPixelsFromBuffer(packed)
        }
        return bitmap
    }

    private const val MAX_DIMENSION = 12_000
    private const val MAX_ROW_BYTES = 64 * 1024
    private const val MAX_RAW_BYTES = 128 * 1024 * 1024
}
