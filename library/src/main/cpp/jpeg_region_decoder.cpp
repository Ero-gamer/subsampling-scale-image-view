#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <turbojpeg.h>
#include <cstdlib>
#include <cstring>

#define LOG_TAG "SsivJpegDecoder"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ── Helpers ───────────────────────────────────────────────────────────────────

/** Create an ARGB_8888 Android Bitmap of the given dimensions via JNI reflection. */
static jobject create_bitmap(JNIEnv *env, int width, int height) {
    jclass bitmap_class = env->FindClass("android/graphics/Bitmap");
    if (!bitmap_class) return nullptr;

    jclass config_class = env->FindClass("android/graphics/Bitmap$Config");
    if (!config_class) return nullptr;

    jfieldID argb_field = env->GetStaticFieldID(config_class, "ARGB_8888",
                                                 "Landroid/graphics/Bitmap$Config;");
    if (!argb_field) return nullptr;
    jobject argb_config = env->GetStaticObjectField(config_class, argb_field);

    jmethodID create_method = env->GetStaticMethodID(bitmap_class, "createBitmap",
        "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    if (!create_method) return nullptr;

    return env->CallStaticObjectMethod(bitmap_class, create_method,
                                       (jint)width, (jint)height, argb_config);
}

// ── JNI entry point ───────────────────────────────────────────────────────────

/**
 * Decodes [jpegData] using libjpeg-turbo (pure software, no hardware HAL) at
 * (imgWidth/sampleSize × imgHeight/sampleSize) resolution, then crops and returns
 * the region [cropLeft, cropTop, cropRight, cropBottom] as an Android Bitmap.
 *
 * Called only for large JPEG strips where the Android hardware JPEG decoder has
 * a chroma upsampler bug producing coloured tint bands.
 *
 * Returns null on any error — the Kotlin caller falls back to BitmapRegionDecoder.
 */
extern "C"
JNIEXPORT jobject JNICALL
Java_com_davemorrissey_labs_subscaleview_decoder_LiJpegTurboRegionDecoder_nativeDecodeRegion(
    JNIEnv *env, jobject /* thiz */,
    jbyteArray jpeg_data,
    jint img_width, jint img_height,
    jint sample_size,
    jint crop_left, jint crop_top, jint crop_right, jint crop_bottom)
{
    // ── 1. Get raw JPEG bytes ─────────────────────────────────────────────────
    jsize jpeg_size = env->GetArrayLength(jpeg_data);
    jbyte *jpeg_buf = env->GetByteArrayElements(jpeg_data, nullptr);
    if (!jpeg_buf) {
        LOGE("Failed to get JPEG byte array");
        return nullptr;
    }

    // ── 2. Compute scaled dimensions ─────────────────────────────────────────
    int s = (sample_size < 1) ? 1 : sample_size;
    int scaled_w = img_width  / s;
    int scaled_h = img_height / s;
    if (scaled_w < 1) scaled_w = 1;
    if (scaled_h < 1) scaled_h = 1;

    // ── 3. Allocate pixel buffer (RGBA, 4 bytes/px) ───────────────────────────
    size_t buf_size = (size_t)scaled_w * scaled_h * 4;
    uint8_t *pixel_buf = (uint8_t *)malloc(buf_size);
    if (!pixel_buf) {
        LOGE("OOM: cannot allocate %zu bytes for JPEG decode", buf_size);
        env->ReleaseByteArrayElements(jpeg_data, jpeg_buf, JNI_ABORT);
        return nullptr;
    }

    // ── 4. Decompress with libjpeg-turbo ──────────────────────────────────────
    // ACCURATEDCT: integer DCT with full precision — avoids the rounding errors
    // in the fast DCT that can also contribute to colour shifting.
    // TJPF_RGBA: Android Bitmap ARGB_8888 is stored RGBA in native byte order.
    tjhandle tj = tjInitDecompress();
    if (!tj) {
        LOGE("tjInitDecompress failed: %s", tjGetErrorStr());
        free(pixel_buf);
        env->ReleaseByteArrayElements(jpeg_data, jpeg_buf, JNI_ABORT);
        return nullptr;
    }

    int result = tjDecompress2(
        tj,
        (const unsigned char *)jpeg_buf,
        (unsigned long)jpeg_size,
        pixel_buf,
        scaled_w,
        scaled_w * 4,   // pitch = width * 4 bytes (RGBA, no padding)
        scaled_h,
        TJPF_RGBA,
        TJFLAG_ACCURATEDCT   // software-only accurate DCT; no HAL involvement
    );

    tjDestroy(tj);
    env->ReleaseByteArrayElements(jpeg_data, jpeg_buf, JNI_ABORT);

    if (result != 0) {
        LOGE("tjDecompress2 failed: %s", tjGetErrorStr2(tj));
        free(pixel_buf);
        return nullptr;
    }

    // ── 5. Compute crop rect in scaled-image pixel space ─────────────────────
    // sRect coordinates are in source-image space; scale them down.
    int cx = crop_left  / s;
    int cy = crop_top   / s;
    int cr = crop_right / s;
    int cb = crop_bottom / s;
    if (cx < 0) cx = 0;
    if (cy < 0) cy = 0;
    if (cr > scaled_w) cr = scaled_w;
    if (cb > scaled_h) cb = scaled_h;
    int cw = cr - cx;
    int ch = cb - cy;
    if (cw < 1) cw = 1;
    if (ch < 1) ch = 1;

    // ── 6. Create Bitmap and copy cropped pixels ─────────────────────────────
    jobject bitmap = create_bitmap(env, cw, ch);
    if (!bitmap) {
        LOGE("Failed to create Android Bitmap (%d x %d)", cw, ch);
        free(pixel_buf);
        return nullptr;
    }

    void *bmp_pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &bmp_pixels) < 0) {
        LOGE("AndroidBitmap_lockPixels failed");
        free(pixel_buf);
        return nullptr;
    }

    // Copy row by row from the decoded full-scaled buffer into the Bitmap crop.
    // pixel_buf layout: RGBA, row-major, stride = scaled_w * 4.
    // Bitmap layout: ARGB_8888 = same byte order on Android (R G B A).
    uint8_t *dst = (uint8_t *)bmp_pixels;
    int row_bytes = cw * 4;
    for (int row = 0; row < ch; row++) {
        const uint8_t *src = pixel_buf + ((cy + row) * scaled_w + cx) * 4;
        memcpy(dst, src, row_bytes);
        dst += row_bytes;
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    free(pixel_buf);
    return bitmap;
}
