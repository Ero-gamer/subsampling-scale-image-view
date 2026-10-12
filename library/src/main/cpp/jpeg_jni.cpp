#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <cstdint>
#include <cstdlib>
#include <cstring>

#include "jpeg_core.h"

#define LOG_TAG "SsivJpeg"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Resolved once in JNI_OnLoad: avoids FindClass / GetStaticFieldID / GetStaticMethodID for every tile.
jclass g_bitmap_cls = nullptr;
jmethodID g_create_bitmap = nullptr;
jobject g_cfg_argb = nullptr;
jobject g_cfg_565 = nullptr;

jobject static_config(JNIEnv *env, jclass cfg_cls, const char *name) {
    jfieldID fid = env->GetStaticFieldID(cfg_cls, name, "Landroid/graphics/Bitmap$Config;");
    if (!fid) return nullptr;
    jobject local = env->GetStaticObjectField(cfg_cls, fid);
    if (!local) return nullptr;
    jobject global = env->NewGlobalRef(local);
    env->DeleteLocalRef(local);
    return global;
}

jobject create_bitmap(JNIEnv *env, int w, int h, bool rgb565) {
    jobject cfg = rgb565 ? g_cfg_565 : g_cfg_argb;
    if (!g_bitmap_cls || !g_create_bitmap || !cfg) return nullptr;
    jobject bmp = env->CallStaticObjectMethod(g_bitmap_cls, g_create_bitmap, (jint) w, (jint) h, cfg);
    if (env->ExceptionCheck()) {  // e.g. OutOfMemoryError: the Kotlin side falls back to Skia
        env->ExceptionClear();
        return nullptr;
    }
    return bmp;
}

// 4x4 ordered (Bayer) dither keyed on ABSOLUTE scaled coordinates so adjacent tiles agree at their seams.
const uint8_t kBayer[4][4] = {
    {0, 8, 2, 10}, {12, 4, 14, 6}, {3, 11, 1, 9}, {15, 7, 13, 5}
};

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass bmp = env->FindClass("android/graphics/Bitmap");
    jclass cfg = env->FindClass("android/graphics/Bitmap$Config");
    if (bmp && cfg) {
        g_bitmap_cls = static_cast<jclass>(env->NewGlobalRef(bmp));
        g_create_bitmap = env->GetStaticMethodID(
            bmp, "createBitmap", "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
        g_cfg_argb = static_config(env, cfg, "ARGB_8888");
        g_cfg_565 = static_config(env, cfg, "RGB_565");
    }
    if (env->ExceptionCheck()) env->ExceptionClear();  // decode just reports failure, Kotlin falls back
    return JNI_VERSION_1_6;
}

/** Maps [offset, offset + length) of fd and parses the header. Returns an opaque handle, 0 on failure. */
extern "C" JNIEXPORT jlong JNICALL
Java_com_davemorrissey_labs_subscaleview_decoder_JpegNative_nativeOpen(
    JNIEnv *, jclass, jint fd, jlong offset, jlong length) {
    return reinterpret_cast<jlong>(jpeg_open_fd(fd, offset, length));
}

/** {width, height} of the opened image, or null. */
extern "C" JNIEXPORT jintArray JNICALL
Java_com_davemorrissey_labs_subscaleview_decoder_JpegNative_nativeInfo(JNIEnv *env, jclass, jlong handle) {
    const JpegSource *src = reinterpret_cast<const JpegSource *>(handle);
    if (!src) return nullptr;
    jintArray arr = env->NewIntArray(2);
    if (!arr) return nullptr;
    const jint v[2] = {src->width, src->height};
    env->SetIntArrayRegion(arr, 0, 2, v);
    return arr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_davemorrissey_labs_subscaleview_decoder_JpegNative_nativeClose(JNIEnv *, jclass, jlong handle) {
    jpeg_close(reinterpret_cast<JpegSource *>(handle));
}

/**
 * Decodes [left, top, right, bottom) (source pixels) at 1/sampleSize scale (sampleSize 1..8, powers of two).
 * Returns an ARGB_8888 (or dithered RGB_565) bitmap, or null on any error.
 * When the MCU-aligned crop equals the requested tile and the output is ARGB_8888 the decoder writes
 * straight into the bitmap; otherwise it decodes into a temporary buffer and copies the exact tile.
 */
extern "C" JNIEXPORT jobject JNICALL
Java_com_davemorrissey_labs_subscaleview_decoder_JpegNative_nativeDecodeRegion(
    JNIEnv *env, jclass, jlong handle, jint sampleSize,
    jint left, jint top, jint right, jint bottom, jboolean rgb565) {
    const JpegSource *src = reinterpret_cast<const JpegSource *>(handle);
    if (!src || sampleSize < 1 || sampleSize > 8) return nullptr;
    JpegPlan plan;
    if (!jpeg_plan(src, sampleSize, left, top, right, bottom, &plan)) return nullptr;

    const bool to565 = rgb565 == JNI_TRUE;
    const int w = plan.inner_w;
    const int h = plan.inner_h;

    jobject bmp = create_bitmap(env, w, h, to565);
    if (!bmp) return nullptr;
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bmp, &info) < 0 || info.width != (uint32_t) w || info.height != (uint32_t) h) {
        env->DeleteLocalRef(bmp);
        return nullptr;
    }
    void *pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bmp, &pixels) < 0 || !pixels) {
        env->DeleteLocalRef(bmp);
        return nullptr;
    }
    uint8_t *dst = static_cast<uint8_t *>(pixels);
    bool ok = false;

    if (!to565 && plan.exact && info.format == ANDROID_BITMAP_FORMAT_RGBA_8888) {
        ok = jpeg_decode(src, plan, dst, info.stride) == 0;  // zero-copy path
    } else {
        const size_t pitch = (size_t) plan.out_w * 4;
        uint8_t *buf = static_cast<uint8_t *>(malloc(pitch * (size_t) plan.out_h));
        if (!buf) {
            LOGE("OOM: %zu bytes", pitch * (size_t) plan.out_h);
        } else if (jpeg_decode(src, plan, buf, pitch) == 0) {
            ok = true;
            if (!to565) {
                if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
                    ok = false;
                } else {
                    for (int row = 0; row < h; row++) {
                        memcpy(dst + (size_t) row * info.stride,
                               buf + ((size_t) (plan.inner_y + row) * plan.out_w + plan.inner_x) * 4,
                               (size_t) w * 4);
                    }
                }
            } else if (info.format != ANDROID_BITMAP_FORMAT_RGB_565) {
                ok = false;
            } else {
                const int abs_x = plan.aligned_left * plan.num / plan.denom + plan.inner_x;
                const int abs_y = plan.aligned_top * plan.num / plan.denom + plan.inner_y;
                for (int row = 0; row < h; row++) {
                    const uint8_t *s = buf + ((size_t) (plan.inner_y + row) * plan.out_w + plan.inner_x) * 4;
                    uint16_t *o = reinterpret_cast<uint16_t *>(dst + (size_t) row * info.stride);
                    const uint8_t *brow = kBayer[(abs_y + row) & 3];
                    for (int col = 0; col < w; col++, s += 4) {
                        const int b = brow[(abs_x + col) & 3];
                        int r = s[0] + ((b * 8) >> 4);
                        int g = s[1] + ((b * 4) >> 4);
                        int bl = s[2] + ((b * 8) >> 4);
                        if (r > 255) r = 255;
                        if (g > 255) g = 255;
                        if (bl > 255) bl = 255;
                        o[col] = (uint16_t) (((r >> 3) << 11) | ((g >> 2) << 5) | (bl >> 3));
                    }
                }
            }
        }
        free(buf);
    }

    AndroidBitmap_unlockPixels(env, bmp);
    if (!ok) {
        env->DeleteLocalRef(bmp);
        return nullptr;
    }
    return bmp;
}
