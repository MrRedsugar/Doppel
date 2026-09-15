package dev.doppel.sdk

import java.nio.ByteBuffer

/** Repack RGBA rows once; the final row need not include ImageReader's alignment padding. */
internal object LegacyCaptureBuffers {
    fun byteCount(width: Int, height: Int): Int {
        require(width in 1..16384 && height in 1..16384) { "屏幕尺寸无效" }
        val count = width.toLong() * height * 4
        require(count <= 64L * 1024 * 1024) { "屏幕尺寸超过单次采集的内存上限" }
        return count.toInt()
    }
    fun packRgba(source: ByteBuffer, width: Int, height: Int, pixelStride: Int, rowStride: Int): ByteBuffer {
        val count = byteCount(width, height)
        require(pixelStride == 4 && rowStride >= width * 4) { "屏幕像素格式不受支持" }
        val start = source.position()
        val required = start.toLong() + (height - 1L) * rowStride + width * 4L
        require(required <= source.limit()) { "屏幕像素尚未完整到达" }
        val packed = ByteBuffer.allocate(count)
        for (row in 0 until height) {
            val offset = (start.toLong() + row.toLong() * rowStride).toInt()
            val line = source.duplicate().apply { position(offset); limit(offset + width * 4) }
            packed.put(line)
        }
        packed.flip()
        return packed
    }
}
