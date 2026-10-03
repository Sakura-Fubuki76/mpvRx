// Migrated from yume-lib/src/main/cpp/yuv/yuv_to_bitmap.cpp.
#include <jni.h>
#include <android/bitmap.h>
#include <libyuv.h>
#include <android/log.h>
#include <cstddef>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <vector>

#define TAG "YuvToBitmap"

#define LOGD(...) ((void)0)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* /*vm*/, void* /*reserved*/) {
    return JNI_VERSION_1_6;
}

enum {
    COLOR_STANDARD_BT709     = 1,
    COLOR_STANDARD_BT601_PAL = 2,
    COLOR_STANDARD_BT601_NTSC = 4,
    COLOR_STANDARD_BT2020    = 6,
};

enum {
    COLOR_RANGE_FULL    = 1,
    COLOR_RANGE_LIMITED = 2,
};

static const libyuv::YuvConstants* selectYuvMatrix(jint colorStandard, jint colorRange) {
    const bool fullRange = (colorRange == COLOR_RANGE_FULL);
    switch (colorStandard) {
        case COLOR_STANDARD_BT601_PAL:
        case COLOR_STANDARD_BT601_NTSC:
            return fullRange ? &libyuv::kYuvJPEGConstants : &libyuv::kYuvI601Constants;
        case COLOR_STANDARD_BT2020:
            return fullRange ? &libyuv::kYuvV2020Constants : &libyuv::kYuv2020Constants;
        case COLOR_STANDARD_BT709:
        default:
            return fullRange ? &libyuv::kYuvF709Constants : &libyuv::kYuvH709Constants;
    }
}

struct BitmapFactoryIds {
    jclass bitmapClass = nullptr;
    jmethodID createBitmap = nullptr;
    jobject config = nullptr;
    bool ok = false;
};

static const BitmapFactoryIds& bitmapFactoryIds(JNIEnv* env) {
    static BitmapFactoryIds ids;
    static std::once_flag once;
    std::call_once(once, [env] {
        jclass localBitmap = env->FindClass("android/graphics/Bitmap");
        jclass localConfig = env->FindClass("android/graphics/Bitmap$Config");
        if (!localBitmap || !localConfig) return;
        ids.bitmapClass = (jclass) env->NewGlobalRef(localBitmap);
        jmethodID createBitmap = env->GetStaticMethodID(
            ids.bitmapClass, "createBitmap",
            "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
        jfieldID argb8888Field = env->GetStaticFieldID(
            localConfig, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
        jobject localConfigObj = env->GetStaticObjectField(localConfig, argb8888Field);
        if (createBitmap && localConfigObj) {
            ids.createBitmap = createBitmap;
            ids.config = env->NewGlobalRef(localConfigObj);
            ids.ok = true;
        }
        env->DeleteLocalRef(localBitmap);
        env->DeleteLocalRef(localConfig);
        if (localConfigObj) env->DeleteLocalRef(localConfigObj);
    });
    return ids;
}

static jobject createArgbBitmap(JNIEnv* env, jint width, jint height) {
    if (width <= 0 || height <= 0) return nullptr;
    // Guard against absurd allocations that later confuse libyuv strides.
    if (width > 8192 || height > 8192) return nullptr;
    const auto& ids = bitmapFactoryIds(env);
    if (!ids.ok) return nullptr;
    return env->CallStaticObjectMethod(ids.bitmapClass, ids.createBitmap, width, height, ids.config);
}

static uint8_t* directBufferPtr(JNIEnv* env, jobject buf) {
    if (!buf) return nullptr;
    return static_cast<uint8_t*>(env->GetDirectBufferAddress(buf));
}

// GetDirectBufferAddress ignores ByteBuffer.position(); add it back.
static jint bufferPosition(JNIEnv* env, jobject buf) {
    if (!buf) return 0;
    jclass cls = env->GetObjectClass(buf);
    jmethodID mid = env->GetMethodID(cls, "position", "()I");
    env->DeleteLocalRef(cls);
    if (!mid) return 0;
    return env->CallIntMethod(buf, mid);
}

static uint8_t* planePtr(JNIEnv* env, jobject buf) {
    uint8_t* base = directBufferPtr(env, buf);
    if (!base) return nullptr;
    return base + bufferPosition(env, buf);
}

struct BitmapLock {
    JNIEnv* env;
    jobject bitmap;
    AndroidBitmapInfo info;
    void* pixels;

    BitmapLock(JNIEnv* e, jobject b) : env(e), bitmap(b), info{}, pixels(nullptr) {
        if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
            pixels = nullptr;
            return;
        }
        if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
            pixels = nullptr;
        }
    }

    ~BitmapLock() {
        if (pixels) {
            AndroidBitmap_unlockPixels(env, bitmap);
        }
    }

    bool ok() const { return pixels != nullptr; }
    int stride() const { return static_cast<int>(info.stride); }
};

static bool planeFits(JNIEnv* env, jobject buffer, int rowStride, int pixelStride,
                      int left, int top, int width, int height);

extern "C" JNIEXPORT jobject JNICALL
Java_app_gyrolet_mpvrx_domain_cloud_YuvToBitmapBridge_imageToBitmap(
    JNIEnv* env, jclass ,
    jobject yBuf, jint yRowStride, jint yPixelStride,
    jobject uBuf, jint uRowStride, jint uPixelStride,
    jobject vBuf, jint vRowStride, jint vPixelStride,
    jint cropLeft, jint cropTop, jint cropWidth, jint cropHeight,
    jint colorStandard, jint colorRange,
    jboolean forceNV21)
{
    if (cropWidth <= 0 || cropHeight <= 0 || cropWidth > 8192 || cropHeight > 8192 ||
        cropLeft % 2 || cropTop % 2 || yPixelStride != 1 || uPixelStride != vPixelStride ||
        !planeFits(env, yBuf, yRowStride, yPixelStride, cropLeft, cropTop, cropWidth, cropHeight) ||
        !planeFits(env, uBuf, uRowStride, uPixelStride, cropLeft/2, cropTop/2, (cropWidth+1)/2, (cropHeight+1)/2) ||
        !planeFits(env, vBuf, vRowStride, vPixelStride, cropLeft/2, cropTop/2, (cropWidth+1)/2, (cropHeight+1)/2)) return nullptr;

    auto* yPtr = planePtr(env, yBuf);
    auto* uPtr = planePtr(env, uBuf);
    auto* vPtr = planePtr(env, vBuf);

    if (!yPtr || !uPtr || !vPtr) {
        LOGE("imageToBitmap: one or more planes are not direct buffers");
        return nullptr;
    }

    LOGD("imageToBitmap: yPixel=%d uPixel=%d uRow=%d vRow=%d crop=%dx%d std=%d range=%d"
         " vu_off=%td forceNV21=%d",
         yPixelStride, uPixelStride, uRowStride, vRowStride,
         cropWidth, cropHeight, colorStandard, colorRange,
         static_cast<ptrdiff_t>(vPtr - uPtr), forceNV21);

    const uint8_t* srcY = yPtr + cropTop * yRowStride + cropLeft * yPixelStride;

    jobject bitmap = createArgbBitmap(env, cropWidth, cropHeight);
    if (!bitmap) {
        LOGE("imageToBitmap: failed to create Bitmap");
        return nullptr;
    }

    const auto* matrix = selectYuvMatrix(colorStandard, colorRange);
    {
        BitmapLock lock(env, bitmap);
        if (!lock.ok()) {
            LOGE("imageToBitmap: failed to lock bitmap pixels");
            env->DeleteLocalRef(bitmap);
            return nullptr;
        }

        auto* dstPixels = static_cast<uint8_t*>(lock.pixels);
        const int dstStride = lock.stride();
        int result = -1;

        if (!forceNV21) {
            const uint8_t* srcU = uPtr + (cropTop / 2) * uRowStride
                                        + (cropLeft / 2) * uPixelStride;
            const uint8_t* srcV = vPtr + (cropTop / 2) * vRowStride
                                        + (cropLeft / 2) * vPixelStride;
            result = libyuv::Android420ToARGBMatrix(
                srcY, yRowStride,
                srcU, uRowStride,
                srcV, vRowStride,
                uPixelStride,
                dstPixels, dstStride,
                matrix,
                cropWidth, cropHeight);
            if (result != 0) {
                LOGE("imageToBitmap: Android420ToARGBMatrix failed with code %d (std=%d range=%d)",
                     result, colorStandard, colorRange);
            }
        } else if (uPixelStride == 2 && uRowStride == vRowStride) {
            const uint8_t* srcUV = uPtr + (cropTop / 2) * uRowStride
                                         + (cropLeft & ~1);
            result = libyuv::NV21ToARGBMatrix(
                srcY, yRowStride,
                srcUV, uRowStride,
                dstPixels, dstStride,
                matrix,
                cropWidth, cropHeight);
            if (result != 0) {
                LOGE("imageToBitmap: forced NV21ToARGBMatrix failed (code %d)", result);
            }
        } else {

            const uint8_t* srcU = uPtr + (cropTop / 2) * uRowStride
                                        + (cropLeft / 2) * uPixelStride;
            const uint8_t* srcV = vPtr + (cropTop / 2) * vRowStride
                                        + (cropLeft / 2) * vPixelStride;
            result = libyuv::I420ToARGBMatrix(
                srcY, yRowStride,
                srcU, uRowStride,
                srcV, vRowStride,
                dstPixels, dstStride,
                matrix,
                cropWidth, cropHeight);

            if (result != 0) {
                LOGE("imageToBitmap: I420ToARGBMatrix failed with code %d (std=%d range=%d)",
                     result, colorStandard, colorRange);
            }
        }

        if (result != 0) {
            env->DeleteLocalRef(bitmap);
            return nullptr;
        }
        libyuv::ARGBToABGR(dstPixels, dstStride, dstPixels, dstStride, cropWidth, cropHeight);
    }

    return bitmap;
}

extern "C" JNIEXPORT jobject JNICALL
Java_app_gyrolet_mpvrx_domain_cloud_YuvToBitmapBridge_bufferToBitmap(
    JNIEnv* env, jclass ,
    jobject yuvBuffer, jint offset,
    jint colorFormat, jint stride, jint sliceHeight,
    jint cropLeft, jint cropTop, jint cropWidth, jint cropHeight,
    jint colorStandard, jint colorRange,
    jboolean forceNV21)
{
    if (cropWidth <= 0 || cropHeight <= 0 || cropWidth > 8192 || cropHeight > 8192 ||
        cropLeft < 0 || cropTop < 0 || stride <= 0 || sliceHeight <= 0 ||
        stride > 32768 || sliceHeight > 32768 || offset < 0 ||
        cropLeft + cropWidth > stride || cropTop + cropHeight > sliceHeight) return nullptr;
    jclass bufferClass = env->GetObjectClass(yuvBuffer);
    jmethodID limitMethod = env->GetMethodID(bufferClass, "limit", "()I");
    env->DeleteLocalRef(bufferClass);
    const int64_t limit = env->CallIntMethod(yuvBuffer, limitMethod);
    const int64_t required = static_cast<int64_t>(offset) + static_cast<int64_t>(stride) * sliceHeight * 3 / 2;
    if (required > limit) return nullptr;

    auto* data = directBufferPtr(env, yuvBuffer);
    if (!data) {
        LOGE("bufferToBitmap: buffer is not a direct buffer");
        return nullptr;
    }
    data += offset;

    jobject bitmap = createArgbBitmap(env, cropWidth, cropHeight);
    if (!bitmap) {
        LOGE("bufferToBitmap: failed to create Bitmap");
        return nullptr;
    }

    const auto* matrix = selectYuvMatrix(colorStandard, colorRange);
    int result = -1;
    {
        BitmapLock lock(env, bitmap);
        if (!lock.ok()) {
            LOGE("bufferToBitmap: failed to lock bitmap pixels");
            env->DeleteLocalRef(bitmap);
            return nullptr;
        }

        auto* dstPixels = static_cast<uint8_t*>(lock.pixels);
        const int dstStride = lock.stride();

        switch (colorFormat) {

            case 21:
            case 39:
            case 0x7F000100:
            case 0x7F420888:
            {
                const uint8_t* yPtr = data + cropTop * stride + cropLeft;
                const uint8_t* uvPtr = data + stride * sliceHeight
                                       + (cropTop / 2) * stride
                                       + (cropLeft & ~1);
                if (forceNV21) {
                    result = libyuv::NV21ToARGBMatrix(yPtr, stride,
                                                       uvPtr, stride,
                                                       dstPixels, dstStride,
                                                       matrix,
                                                       cropWidth, cropHeight);
                } else {
                    result = libyuv::NV12ToARGBMatrix(yPtr, stride,
                                                       uvPtr, stride,
                                                       dstPixels, dstStride,
                                                       matrix,
                                                       cropWidth, cropHeight);
                }
                if (result != 0) {

                    const int chromaStride = stride / 2;
                    const int chromaSliceH = sliceHeight / 2;
                    const uint8_t* uPtr = data + stride * sliceHeight
                                          + (cropTop / 2) * chromaStride
                                          + (cropLeft / 2);
                    const uint8_t* vPtr = uPtr + chromaStride * chromaSliceH;
                    result = libyuv::I420ToARGBMatrix(yPtr, stride,
                                                       uPtr, chromaStride,
                                                       vPtr, chromaStride,
                                                       dstPixels, dstStride,
                                                       matrix,
                                                       cropWidth, cropHeight);
                }
                break;
            }

            case 19:
            case 20:
            {
                const int chromaStride = stride / 2;
                const int chromaSliceH = sliceHeight / 2;
                const uint8_t* yPtr = data + cropTop * stride + cropLeft;
                const uint8_t* uPtr = data + stride * sliceHeight
                                      + (cropTop / 2) * chromaStride
                                      + (cropLeft / 2);
                const uint8_t* vPtr = uPtr + chromaStride * chromaSliceH;
                result = libyuv::I420ToARGBMatrix(yPtr, stride,
                                                   uPtr, chromaStride,
                                                   vPtr, chromaStride,
                                                   dstPixels, dstStride,
                                                   matrix,
                                                   cropWidth, cropHeight);
                break;
            }

            default:
                LOGE("bufferToBitmap: unsupported color format 0x%X", colorFormat);
                break;
        }
        if (result == 0) {
            libyuv::ARGBToABGR(dstPixels, dstStride, dstPixels, dstStride, cropWidth, cropHeight);
        }
    }

    if (result != 0) {
        LOGE("bufferToBitmap: libyuv conversion failed with code %d (fmt=0x%X std=%d range=%d)",
             result, colorFormat, colorStandard, colorRange);
        env->DeleteLocalRef(bitmap);
        return nullptr;
    }

    return bitmap;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_gyrolet_mpvrx_domain_cloud_YuvToBitmapBridge_i420Scale(
    JNIEnv* env, jclass,
    jobject srcY, jint srcStrideY,
    jobject srcU, jint srcStrideU,
    jobject srcV, jint srcStrideV,
    jint srcWidth, jint srcHeight,
    jobject dstY, jint dstStrideY,
    jobject dstU, jint dstStrideU,
    jobject dstV, jint dstStrideV,
    jint dstWidth, jint dstHeight,
    jint filterMode)
{
    if (srcWidth <= 0 || srcHeight <= 0 || dstWidth <= 0 || dstHeight <= 0 ||
        srcStrideY <= 0 || srcStrideU <= 0 || srcStrideV <= 0 ||
        dstStrideY <= 0 || dstStrideU <= 0 || dstStrideV <= 0) {
        return JNI_FALSE;
    }

    auto* pSrcY = planePtr(env, srcY);
    auto* pSrcU = planePtr(env, srcU);
    auto* pSrcV = planePtr(env, srcV);
    auto* pDstY = planePtr(env, dstY);
    auto* pDstU = planePtr(env, dstU);
    auto* pDstV = planePtr(env, dstV);

    if (!pSrcY || !pSrcU || !pSrcV || !pDstY || !pDstU || !pDstV) {
        LOGE("i420Scale: one or more planes are not direct buffers");
        return JNI_FALSE;
    }

    int ret = libyuv::I420Scale(
        pSrcY, srcStrideY,
        pSrcU, srcStrideU,
        pSrcV, srcStrideV,
        srcWidth, srcHeight,
        pDstY, dstStrideY,
        pDstU, dstStrideU,
        pDstV, dstStrideV,
        dstWidth, dstHeight,
        static_cast<libyuv::FilterMode>(filterMode));

    if (ret != 0) {
        LOGE("i420Scale: libyuv I420Scale failed with code %d (%dx%d -> %dx%d filter=%d)",
             ret, srcWidth, srcHeight, dstWidth, dstHeight, filterMode);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_gyrolet_mpvrx_domain_cloud_YuvToBitmapBridge_i420IsMostlySolidColor(
    JNIEnv* env, jclass,
    jobject yBuf, jint yRowStride,
    jobject uBuf, jint uRowStride,
    jobject vBuf, jint vRowStride,
    jint width, jint height,
    jfloat threshold,
    jint tolerance)
{
    if (width <= 0 || height <= 0 || yRowStride <= 0 || uRowStride <= 0 || vRowStride <= 0) {
        return JNI_FALSE;
    }

    auto* yPtr = static_cast<uint8_t*>(env->GetDirectBufferAddress(yBuf));
    auto* uPtr = static_cast<uint8_t*>(env->GetDirectBufferAddress(uBuf));
    auto* vPtr = static_cast<uint8_t*>(env->GetDirectBufferAddress(vBuf));
    if (!yPtr || !uPtr || !vPtr) {
        LOGE("i420IsMostlySolidColor: one or more planes are not direct buffers");
        return JNI_FALSE;
    }

    const int marginX = width / 10;
    const int marginY = height / 10;
    const int sampleAreaRight = width - marginX;
    const int sampleAreaBottom = height - marginY;
    constexpr int gridSize = 10;
    const int stepX = (sampleAreaRight - marginX) / gridSize;
    const int stepY = (sampleAreaBottom - marginY) / gridSize;
    if (stepX <= 0 || stepY <= 0) {
        return JNI_FALSE;
    }

    const int refX = marginX;
    const int refY = marginY;
    const int refYValue = yPtr[refY * yRowStride + refX];
    const int refUValue = uPtr[(refY / 2) * uRowStride + refX / 2];
    const int refVValue = vPtr[(refY / 2) * vRowStride + refX / 2];

    int sampledCount = 0;
    int similarCount = 0;
    for (int x = 0; x < gridSize; x++) {
        for (int y = 0; y < gridSize; y++) {
            const int pixelX = marginX + x * stepX;
            const int pixelY = marginY + y * stepY;
            if (pixelX >= width || pixelY >= height) {
                continue;
            }

            const int yValue = yPtr[pixelY * yRowStride + pixelX];
            const int uValue = uPtr[(pixelY / 2) * uRowStride + pixelX / 2];
            const int vValue = vPtr[(pixelY / 2) * vRowStride + pixelX / 2];
            sampledCount++;
            if (std::abs(yValue - refYValue) <= tolerance &&
                std::abs(uValue - refUValue) <= tolerance &&
                std::abs(vValue - refVValue) <= tolerance) {
                similarCount++;
            }
        }
    }

    if (sampledCount <= 0) {
        return JNI_FALSE;
    }
    return (static_cast<float>(similarCount) / static_cast<float>(sampledCount)) >= threshold
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_gyrolet_mpvrx_domain_cloud_YuvToBitmapBridge_nv12ScaleToI420(
    JNIEnv* env, jclass,
    jobject srcY, jint srcStrideY,
    jobject srcUV, jint srcStrideUV,
    jint srcWidth, jint srcHeight,
    jobject dstY, jint dstStrideY,
    jobject dstU, jint dstStrideU,
    jobject dstV, jint dstStrideV,
    jint dstWidth, jint dstHeight,
    jint filterMode,
    jboolean forceNV21)
{
    if (srcWidth <= 0 || srcHeight <= 0 || dstWidth <= 0 || dstHeight <= 0 ||
        srcStrideY <= 0 || srcStrideUV <= 0 ||
        dstStrideY <= 0 || dstStrideU <= 0 || dstStrideV <= 0) {
        return JNI_FALSE;
    }

    auto* pSrcY  = planePtr(env, srcY);
    auto* pSrcUV = planePtr(env, srcUV);
    auto* pDstY  = planePtr(env, dstY);
    auto* pDstU  = planePtr(env, dstU);
    auto* pDstV  = planePtr(env, dstV);

    if (!pSrcY || !pSrcUV || !pDstY || !pDstU || !pDstV) {
        LOGE("nv12ScaleToI420: one or more planes are not direct buffers");
        return JNI_FALSE;
    }

    const size_t tmpYSize = static_cast<size_t>(dstStrideY) * static_cast<size_t>(dstHeight);
    const size_t tmpUVSize = static_cast<size_t>(dstStrideY) * static_cast<size_t>((dstHeight + 1) / 2);
    if (tmpYSize == 0 || tmpUVSize == 0) {
        LOGE("nv12ScaleToI420: empty temp buffer");
        return JNI_FALSE;
    }
    thread_local std::vector<uint8_t> tmpY;
    thread_local std::vector<uint8_t> tmpUV;
    tmpY.resize(tmpYSize);
    tmpUV.resize(tmpUVSize);
    if (tmpY.data() == nullptr || tmpUV.data() == nullptr) {
        LOGE("nv12ScaleToI420: temp allocation failed (%zu / %zu)", tmpYSize, tmpUVSize);
        return JNI_FALSE;
    }

    int ret = libyuv::NV12Scale(
        pSrcY, srcStrideY,
        pSrcUV, srcStrideUV,
        srcWidth, srcHeight,
        tmpY.data(), dstStrideY,
        tmpUV.data(), dstStrideY,
        dstWidth, dstHeight,
        static_cast<libyuv::FilterMode>(filterMode));

    if (ret != 0) {
        LOGE("nv12ScaleToI420: NV12Scale failed %d (%dx%d -> %dx%d)", ret, srcWidth, srcHeight, dstWidth, dstHeight);
        return JNI_FALSE;
    }

    if (forceNV21) {
        ret = libyuv::NV21ToI420(
            tmpY.data(), dstStrideY,
            tmpUV.data(), dstStrideY,
            pDstY, dstStrideY,
            pDstU, dstStrideU,
            pDstV, dstStrideV,
            dstWidth, dstHeight);
    } else {
        ret = libyuv::NV12ToI420(
            tmpY.data(), dstStrideY,
            tmpUV.data(), dstStrideY,
            pDstY, dstStrideY,
            pDstU, dstStrideU,
            pDstV, dstStrideV,
            dstWidth, dstHeight);
    }

    if (ret != 0) {
        LOGE("nv12ScaleToI420: %s failed %d", forceNV21 ? "NV21ToI420" : "NV12ToI420", ret);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_gyrolet_mpvrx_domain_cloud_YuvToBitmapBridge_compositeToSheet(
    JNIEnv* env, jclass,
    jobject frameBitmap,
    jobject sheetBitmap,
    jint col, jint row,
    jint frameWidth, jint frameHeight,
    jint cols)
{
    AndroidBitmapInfo frameInfo = {};
    AndroidBitmapInfo sheetInfo = {};
    if (AndroidBitmap_getInfo(env, frameBitmap, &frameInfo) < 0) {
        LOGE("compositeToSheet: cannot get frame bitmap info");
        return JNI_FALSE;
    }
    if (AndroidBitmap_getInfo(env, sheetBitmap, &sheetInfo) < 0) {
        LOGE("compositeToSheet: cannot get sheet bitmap info");
        return JNI_FALSE;
    }

    if (frameInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        sheetInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("compositeToSheet: both bitmaps must be ARGB_8888");
        return JNI_FALSE;
    }

    // Cell size is the sprite grid slot; the frame bitmap must already match it.
    // Mismatched size (e.g. after 90/270 rotation) would overrun both buffers.
    if (frameInfo.width != static_cast<uint32_t>(frameWidth) ||
        frameInfo.height != static_cast<uint32_t>(frameHeight)) {
        LOGE("compositeToSheet: frame %ux%u != cell %dx%d",
             frameInfo.width, frameInfo.height, frameWidth, frameHeight);
        return JNI_FALSE;
    }
    if (col < 0 || row < 0 || cols <= 0 || col >= cols || row >= 8192 ||
        frameWidth <= 0 || frameHeight <= 0 || frameWidth > 8192 || frameHeight > 8192) {
        return JNI_FALSE;
    }
    const uint32_t dstXBytes = static_cast<uint32_t>(col) * static_cast<uint32_t>(frameWidth) * 4u;
    const uint32_t dstYRows = static_cast<uint32_t>(row) * static_cast<uint32_t>(frameHeight);
    if (dstXBytes + static_cast<uint32_t>(frameWidth) * 4u > sheetInfo.stride ||
        dstYRows + static_cast<uint32_t>(frameHeight) > sheetInfo.height) {
        LOGE("compositeToSheet: cell (%d,%d) overflows sheet %ux%u stride=%u",
             col, row, sheetInfo.width, sheetInfo.height, sheetInfo.stride);
        return JNI_FALSE;
    }

    uint8_t* framePixels = nullptr;
    uint8_t* sheetPixels = nullptr;
    if (AndroidBitmap_lockPixels(env, frameBitmap, reinterpret_cast<void**>(&framePixels)) < 0) {
        LOGE("compositeToSheet: cannot lock frame bitmap");
        return JNI_FALSE;
    }
    if (AndroidBitmap_lockPixels(env, sheetBitmap, reinterpret_cast<void**>(&sheetPixels)) < 0) {
        LOGE("compositeToSheet: cannot lock sheet bitmap");
        AndroidBitmap_unlockPixels(env, frameBitmap);
        return JNI_FALSE;
    }

    libyuv::ARGBCopy(
        framePixels, frameInfo.stride,
        sheetPixels + dstYRows * sheetInfo.stride + dstXBytes, sheetInfo.stride,
        frameWidth, frameHeight);

    AndroidBitmap_unlockPixels(env, sheetBitmap);
    AndroidBitmap_unlockPixels(env, frameBitmap);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jobject JNICALL
Java_app_gyrolet_mpvrx_domain_cloud_YuvToBitmapBridge_argbScale(
    JNIEnv* env, jclass,
    jobject srcBitmap,
    jint dstWidth, jint dstHeight,
    jint filterMode)
{
    if (dstWidth <= 0 || dstHeight <= 0) return nullptr;

    AndroidBitmapInfo srcInfo = {};
    if (AndroidBitmap_getInfo(env, srcBitmap, &srcInfo) != ANDROID_BITMAP_RESULT_SUCCESS)
        return nullptr;
    if (srcInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || srcInfo.width <= 0 || srcInfo.height <= 0 || srcInfo.stride < srcInfo.width * 4) {
        LOGE("argbScale: bad source bitmap %ux%u stride=%u", srcInfo.width, srcInfo.height, srcInfo.stride);
        return nullptr;
    }

    BitmapLock srcLock(env, srcBitmap);
    if (!srcLock.ok()) return nullptr;

    jobject dstBitmap = createArgbBitmap(env, dstWidth, dstHeight);
    if (!dstBitmap) return nullptr;

    BitmapLock dstLock(env, dstBitmap);
    if (!dstLock.ok()) {
        env->DeleteLocalRef(dstBitmap);
        return nullptr;
    }
    if (dstLock.stride() < dstWidth * 4) {
        LOGE("argbScale: bad dest stride %d for width %d", dstLock.stride(), dstWidth);
        env->DeleteLocalRef(dstBitmap);
        return nullptr;
    }

    auto filter = static_cast<libyuv::FilterMode>(filterMode);
    int result = libyuv::ARGBScale(
        static_cast<uint8_t*>(srcLock.pixels), srcInfo.stride,
        srcInfo.width, srcInfo.height,
        static_cast<uint8_t*>(dstLock.pixels), dstLock.stride(),
        dstWidth, dstHeight,
        filter);

    if (result != 0) {
        LOGE("argbScale: ARGBScale failed %d (%dx%d -> %dx%d filter=%d)",
             result, srcInfo.width, srcInfo.height, dstWidth, dstHeight, filterMode);
        env->DeleteLocalRef(dstBitmap);
        return nullptr;
    }
    return dstBitmap;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_gyrolet_mpvrx_domain_cloud_YuvToBitmapBridge_argbIsMostlySolidColor(
    JNIEnv* env, jclass,
    jobject bitmap,
    jfloat threshold,
    jint tolerance)
{
    AndroidBitmapInfo info = {};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return JNI_FALSE;
    }
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.width <= 0 || info.height <= 0) {
        return JNI_FALSE;
    }

    uint8_t* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, reinterpret_cast<void**>(&pixels)) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return JNI_FALSE;
    }

    const int width = static_cast<int>(info.width);
    const int height = static_cast<int>(info.height);
    const int marginX = width / 10;
    const int marginY = height / 10;
    const int sampleAreaRight = width - marginX;
    const int sampleAreaBottom = height - marginY;
    constexpr int gridSize = 10;
    const int stepX = (sampleAreaRight - marginX) / gridSize;
    const int stepY = (sampleAreaBottom - marginY) / gridSize;
    if (stepX <= 0 || stepY <= 0) {
        AndroidBitmap_unlockPixels(env, bitmap);
        return JNI_FALSE;
    }

    const uint8_t* ref = pixels + marginY * info.stride + marginX * 4;
    const int ref0 = ref[0];
    const int ref1 = ref[1];
    const int ref2 = ref[2];

    int sampledCount = 0;
    int similarCount = 0;
    for (int x = 0; x < gridSize; x++) {
        for (int y = 0; y < gridSize; y++) {
            const int pixelX = marginX + x * stepX;
            const int pixelY = marginY + y * stepY;
            if (pixelX >= width || pixelY >= height) {
                continue;
            }

            const uint8_t* pixel = pixels + pixelY * info.stride + pixelX * 4;
            sampledCount++;
            if (std::abs(static_cast<int>(pixel[0]) - ref0) <= tolerance &&
                std::abs(static_cast<int>(pixel[1]) - ref1) <= tolerance &&
                std::abs(static_cast<int>(pixel[2]) - ref2) <= tolerance) {
                similarCount++;
            }
        }
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    if (sampledCount <= 0) {
        return JNI_FALSE;
    }
    return (static_cast<float>(similarCount) / static_cast<float>(sampledCount)) >= threshold
        ? JNI_TRUE
        : JNI_FALSE;
}

// Image planes already identify U/V. Do not guess their order from a codec vendor name.
static bool planeFits(JNIEnv* env, jobject buffer, int rowStride, int pixelStride,
                      int left, int top, int width, int height) {
    if (!buffer || !directBufferPtr(env, buffer) || rowStride <= 0 || pixelStride <= 0 ||
        left < 0 || top < 0 || width <= 0 || height <= 0) return false;
    jclass cls = env->GetObjectClass(buffer);
    jmethodID limitMethod = env->GetMethodID(cls, "limit", "()I");
    env->DeleteLocalRef(cls);
    const int limit = env->CallIntMethod(buffer, limitMethod);
    const int64_t last = static_cast<int64_t>(bufferPosition(env, buffer)) +
        static_cast<int64_t>(top + height - 1) * rowStride +
        static_cast<int64_t>(left + width - 1) * pixelStride;
    return last < limit && static_cast<int64_t>(left + width - 1) * pixelStride < rowStride;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_gyrolet_mpvrx_domain_cloud_YuvToBitmapBridge_android420ScaleToI420(
    JNIEnv* env, jclass, jobject y, jint yStride, jint yPixel,
    jobject u, jint uStride, jint uPixel, jobject v, jint vStride, jint vPixel,
    jint left, jint top, jint width, jint height, jobject dstY, jobject dstU, jobject dstV,
    jint dstWidth, jint dstHeight, jint filter) {
    if (width > 8192 || height > 8192 || width % 2 || height % 2 || left % 2 || top % 2 ||
        yPixel != 1 || uPixel != vPixel || filter < 0 || filter > 3 ||
        !planeFits(env, y, yStride, yPixel, left, top, width, height) ||
        !planeFits(env, u, uStride, uPixel, left / 2, top / 2, width / 2, height / 2) ||
        !planeFits(env, v, vStride, vPixel, left / 2, top / 2, width / 2, height / 2) ||
        dstWidth <= 0 || dstHeight <= 0 || dstWidth > 2048 || dstHeight > 2048 ||
        !planeFits(env, dstY, dstWidth, 1, 0, 0, dstWidth, dstHeight) ||
        !planeFits(env, dstU, (dstWidth + 1)/2, 1, 0, 0, (dstWidth + 1)/2, (dstHeight + 1)/2) ||
        !planeFits(env, dstV, (dstWidth + 1)/2, 1, 0, 0, (dstWidth + 1)/2, (dstHeight + 1)/2)) return JNI_FALSE;
    const int uvStride = width / 2;
    std::vector<uint8_t> packed(static_cast<size_t>(width) * height * 3 / 2);
    uint8_t* py = packed.data();
    uint8_t* pu = py + width * height;
    uint8_t* pv = pu + uvStride * (height / 2);
    if (libyuv::Android420ToI420(
        planePtr(env, y) + top * yStride + left, yStride,
        planePtr(env, u) + (top / 2) * uStride + (left / 2) * uPixel, uStride,
        planePtr(env, v) + (top / 2) * vStride + (left / 2) * vPixel, vStride, uPixel,
        py, width, pu, uvStride, pv, uvStride, width, height)) return JNI_FALSE;
    return libyuv::I420Scale(py, width, pu, uvStride, pv, uvStride, width, height,
        planePtr(env, dstY), dstWidth, planePtr(env, dstU), (dstWidth + 1)/2,
        planePtr(env, dstV), (dstWidth + 1)/2, dstWidth, dstHeight,
        static_cast<libyuv::FilterMode>(filter)) == 0 ? JNI_TRUE : JNI_FALSE;
}
