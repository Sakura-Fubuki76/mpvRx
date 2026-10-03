package app.gyrolet.mpvrx.domain.cloud

import android.graphics.Bitmap
import android.media.Image
import java.nio.ByteBuffer

object YuvToBitmapBridge {

    val available: Boolean = runCatching {
        System.loadLibrary("mpvrx_yuv")
        true
    }.getOrDefault(false)

    init {
        CloudTrace.event("native.yuv.loaded", detail = "available=$available")
        if (available && app.gyrolet.mpvrx.BuildConfig.DEBUG) {
            runCatching { YuvNativeDiagnostics.verify() }
                .onSuccess { CloudTrace.event("native.yuv.check", detail = "success=true") }
                .onFailure { CloudTrace.event("native.yuv.check", detail = "success=false error=${it.javaClass.simpleName}") }
        }
    }

    @JvmStatic
    external fun imageToBitmap(
        yBuf: ByteBuffer,
        yRowStride: Int,
        yPixelStride: Int,
        uBuf: ByteBuffer,
        uRowStride: Int,
        uPixelStride: Int,
        vBuf: ByteBuffer,
        vRowStride: Int,
        vPixelStride: Int,
        cropLeft: Int,
        cropTop: Int,
        cropWidth: Int,
        cropHeight: Int,
        colorStandard: Int,
        colorRange: Int,
        forceNV21: Boolean,
    ): Bitmap?

    @JvmStatic
    external fun bufferToBitmap(
        yuvBuffer: ByteBuffer,
        offset: Int,
        colorFormat: Int,
        stride: Int,
        sliceHeight: Int,
        cropLeft: Int,
        cropTop: Int,
        cropWidth: Int,
        cropHeight: Int,
        colorStandard: Int,
        colorRange: Int,
        forceNV21: Boolean,
    ): Bitmap?

    @JvmStatic
    external fun i420Scale(
        srcY: ByteBuffer,
        srcStrideY: Int,
        srcU: ByteBuffer,
        srcStrideU: Int,
        srcV: ByteBuffer,
        srcStrideV: Int,
        srcWidth: Int,
        srcHeight: Int,
        dstY: ByteBuffer,
        dstStrideY: Int,
        dstU: ByteBuffer,
        dstStrideU: Int,
        dstV: ByteBuffer,
        dstStrideV: Int,
        dstWidth: Int,
        dstHeight: Int,
        filterMode: Int,
    ): Boolean

    @JvmStatic
    external fun i420IsMostlySolidColor(
        yBuf: ByteBuffer,
        yRowStride: Int,
        uBuf: ByteBuffer,
        uRowStride: Int,
        vBuf: ByteBuffer,
        vRowStride: Int,
        width: Int,
        height: Int,
        threshold: Float,
        tolerance: Int,
    ): Boolean

    @JvmStatic
    external fun compositeToSheet(
        frameBitmap: Bitmap,
        sheetBitmap: Bitmap,
        col: Int,
        row: Int,
        frameWidth: Int,
        frameHeight: Int,
        cols: Int,
    ): Boolean

    @JvmStatic
    external fun nv12ScaleToI420(
        srcY: ByteBuffer,
        srcStrideY: Int,
        srcUV: ByteBuffer,
        srcStrideUV: Int,
        srcWidth: Int,
        srcHeight: Int,
        dstY: ByteBuffer,
        dstStrideY: Int,
        dstU: ByteBuffer,
        dstStrideU: Int,
        dstV: ByteBuffer,
        dstStrideV: Int,
        dstWidth: Int,
        dstHeight: Int,
        filterMode: Int,
        forceNV21: Boolean,
    ): Boolean

    @JvmStatic
    external fun argbScale(
        srcBitmap: Bitmap,
        dstWidth: Int,
        dstHeight: Int,
        filterMode: Int,
    ): Bitmap?

    @JvmStatic
    external fun argbIsMostlySolidColor(
        bitmap: Bitmap,
        threshold: Float,
        tolerance: Int,
    ): Boolean

    fun scaleTwoPassFromImage(
        image: Image,
        midWidth: Int = 320,
        midHeight: Int = 180,
        dstWidth: Int = 160,
        dstHeight: Int = 90,
        filterMode: Int = FilterMode.BOX,
        @Suppress("UNUSED_PARAMETER") forceNV21: Boolean = false,
    ): ScaledYuv? {
        val crop = image.cropRect
        val srcWidth = crop.width() and -2
        val srcHeight = crop.height() and -2
        if (!available || srcWidth <= 0 || srcHeight <= 0 || image.planes.size < 3) return null
        val planes = image.planes
        val (midY, midU, midV) = allocateYuvPlanes(midWidth, midHeight)
        if (!android420ScaleToI420(
                planes[0].buffer, planes[0].rowStride, planes[0].pixelStride,
                planes[1].buffer, planes[1].rowStride, planes[1].pixelStride,
                planes[2].buffer, planes[2].rowStride, planes[2].pixelStride,
                crop.left, crop.top, srcWidth, srcHeight,
                midY, midU, midV, midWidth, midHeight, filterMode,
            )) return null
        val (dstY, dstU, dstV) = allocateYuvPlanes(dstWidth, dstHeight)
        val midUvStride = (midWidth + 1) / 2
        val dstUvStride = (dstWidth + 1) / 2
        if (!i420Scale(midY, midWidth, midU, midUvStride, midV, midUvStride,
                midWidth, midHeight, dstY, dstWidth, dstU, dstUvStride, dstV, dstUvStride,
                dstWidth, dstHeight, filterMode)) return null
        return ScaledYuv(dstY, dstU, dstV, dstWidth, dstHeight)
    }

    @JvmStatic
    external fun android420ScaleToI420(
        y: ByteBuffer, yStride: Int, yPixelStride: Int,
        u: ByteBuffer, uStride: Int, uPixelStride: Int,
        v: ByteBuffer, vStride: Int, vPixelStride: Int,
        left: Int, top: Int, width: Int, height: Int,
        dstY: ByteBuffer, dstU: ByteBuffer, dstV: ByteBuffer,
        dstWidth: Int, dstHeight: Int, filter: Int,
    ): Boolean

    fun scaleTwoPass(
        srcY: ByteBuffer,
        srcStrideY: Int,
        srcU: ByteBuffer,
        srcStrideU: Int,
        srcV: ByteBuffer,
        srcStrideV: Int,
        srcWidth: Int,
        srcHeight: Int,
        midWidth: Int = 320,
        midHeight: Int = 180,
        dstWidth: Int = 160,
        dstHeight: Int = 90,
        filterMode: Int = FilterMode.BOX,
    ): ScaledYuv? {
        val (midY, midU, midV) = allocateYuvPlanes(midWidth, midHeight)
        val midUvStride = (midWidth + 1) / 2
        if (!i420Scale(
                srcY, srcStrideY, srcU, srcStrideU, srcV, srcStrideV,
                srcWidth, srcHeight,
                midY, midWidth, midU, midUvStride, midV, midUvStride,
                midWidth, midHeight, filterMode,
            )
        ) {
            return null
        }

        midY.rewind()
        midU.rewind()
        midV.rewind()
        val (dstY, dstU, dstV) = allocateYuvPlanes(dstWidth, dstHeight)
        val dstUvStride = (dstWidth + 1) / 2
        if (!i420Scale(
                midY, midWidth, midU, midUvStride, midV, midUvStride,
                midWidth, midHeight,
                dstY, dstWidth, dstU, dstUvStride, dstV, dstUvStride,
                dstWidth, dstHeight, filterMode,
            )
        ) {
            return null
        }

        return ScaledYuv(dstY, dstU, dstV, dstWidth, dstHeight)
    }

    private fun allocateYuvPlanes(width: Int, height: Int): Triple<ByteBuffer, ByteBuffer, ByteBuffer> {
        // Match I420 chroma strides used by i420Scale ((width + 1) / 2).
        val ySize = width * height
        val uvStride = (width + 1) / 2
        val uvSize = uvStride * ((height + 1) / 2)
        return Triple(
            ByteBuffer.allocateDirect(ySize),
            ByteBuffer.allocateDirect(uvSize),
            ByteBuffer.allocateDirect(uvSize),
        )
    }
}

data class ScaledYuv(
    val y: ByteBuffer,
    val u: ByteBuffer,
    val v: ByteBuffer,
    val width: Int,
    val height: Int,
) {

    val strideY: Int get() = width

    val strideU: Int get() = (width + 1) / 2

    val strideV: Int get() = (width + 1) / 2
}

object FilterMode {

    const val NONE = 0

    const val LINEAR = 1

    const val BILINEAR = 2

    const val BOX = 3
}
