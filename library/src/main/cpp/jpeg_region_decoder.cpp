#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <turbojpeg.h>
#include <cstdlib>
#include <cstring>
#include <cmath>

#define LOG_TAG "SsivJpegDecoder"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR,   LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG,   LOG_TAG, __VA_ARGS__)

// ── Helpers ───────────────────────────────────────────────────────────────────

/** Create an ARGB_8888 Android Bitmap. */
static jobject create_bitmap(JNIEnv *env, int width, int height) {
    jclass  bmp_cls  = env->FindClass("android/graphics/Bitmap");
    jclass  cfg_cls  = env->FindClass("android/graphics/Bitmap$Config");
    if (!bmp_cls || !cfg_cls) return nullptr;

    jfieldID argb_fid = env->GetStaticFieldID(cfg_cls, "ARGB_8888",
                            "Landroid/graphics/Bitmap$Config;");
    if (!argb_fid) return nullptr;
    jobject argb_cfg = env->GetStaticObjectField(cfg_cls, argb_fid);

    jmethodID create_mid = env->GetStaticMethodID(bmp_cls, "createBitmap",
        "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    if (!create_mid) return nullptr;

    return env->CallStaticObjectMethod(bmp_cls, create_mid,
                                       (jint)width, (jint)height, argb_cfg);
}

/**
 * Pick the TurboJPEG scaling factor whose value is <= (1.0 / sampleSize),
 * i.e. the largest scale that still fits within the requested downsample.
 * SSIV uses powers-of-two sample sizes (1, 2, 4, 8, 16 …); TJ supports
 * exact matches for 1/1, 1/2, 1/4, 1/8, so we always get an exact hit.
 */
static tjscalingfactor pick_scale(int sample_size, int num_factors,
                                  const tjscalingfactor *factors) {
    double target = 1.0 / (double)sample_size;
    tjscalingfactor best = {1, sample_size}; // safe fallback
    double best_val = 0.0;
    for (int i = 0; i < num_factors; i++) {
        double val = (double)factors[i].num / (double)factors[i].denom;
        if (val <= target + 1e-9 && val > best_val) {
            best_val = val;
            best = factors[i];
        }
    }
    return best;
}

// ── JNI entry point ───────────────────────────────────────────────────────────

/**
 * Decodes a region of [jpeg_data] using libjpeg-turbo's TJ3 cropped-region
 * API (tj3SetCroppingRegion + tj3Decompress8). Only the MCU blocks that
 * overlap the requested [sRect] are decompressed — no full-image decode.
 *
 * Parameters:
 *   jpeg_data          — complete JPEG file bytes
 *   img_width/height   — source image dimensions (from BitmapRegionDecoder header)
 *   sample_size        — SSIV tile sample size (1 = full res, 2 = half, 4 = quarter…)
 *   sRect_*            — requested tile rect in SOURCE image coordinates
 *
 * Returns an ARGB_8888 Bitmap covering exactly [sRect_left..sRect_right,
 * sRect_top..sRect_bottom] at (1/sample_size) scale, or null on any error.
 */
extern "C"
JNIEXPORT jobject JNICALL
Java_com_davemorrissey_labs_subscaleview_decoder_LiJpegTurboRegionDecoder_nativeDecodeRegion(
    JNIEnv  *env,
    jobject  /* thiz */,
    jbyteArray jpeg_data,
    jint img_width, jint img_height,
    jint sample_size,
    jint sRect_left, jint sRect_top, jint sRect_right, jint sRect_bottom)
{
    // ── 1. Lock JPEG bytes ────────────────────────────────────────────────────
    jsize  jpeg_size = env->GetArrayLength(jpeg_data);
    jbyte *jpeg_buf  = env->GetByteArrayElements(jpeg_data, nullptr);
    if (!jpeg_buf) { LOGE("GetByteArrayElements failed"); return nullptr; }

    jobject result = nullptr;  // returned bitmap; set on success

    // ── 2. Init TJ3 decompressor ──────────────────────────────────────────────
    tjhandle tj = tj3Init(TJINIT_DECOMPRESS);
    if (!tj) {
        LOGE("tj3Init failed");
        env->ReleaseByteArrayElements(jpeg_data, jpeg_buf, JNI_ABORT);
        return nullptr;
    }

    // Read JPEG header to detect chroma subsampling (needed for MCU size).
    if (tj3DecompressHeader(tj, (const unsigned char *)jpeg_buf,
                            (size_t)jpeg_size) != 0) {
        LOGE("tj3DecompressHeader failed: %s", tj3GetErrorStr(tj));
        tj3Destroy(tj);
        env->ReleaseByteArrayElements(jpeg_data, jpeg_buf, JNI_ABORT);
        return nullptr;
    }

    int subsamp = tj3Get(tj, TJPARAM_SUBSAMP);

    // MCU width/height in source pixels for the detected subsampling.
    // We use the safe maximum (16) for any subsampling type — it is always a
    // valid alignment for both 4:2:0 (16×16) and 4:2:2 (16×8) MCU blocks.
    int mcu_w = (subsamp == TJSAMP_444 || subsamp == TJSAMP_GRAY) ? 8 : 16;
    int mcu_h = (subsamp == TJSAMP_420 || subsamp == TJSAMP_440)  ? 16 : 8;

    // ── 3. Align crop region to MCU boundaries ────────────────────────────────
    int aligned_left   = (sRect_left / mcu_w) * mcu_w;
    int aligned_top    = (sRect_top  / mcu_h) * mcu_h;
    int aligned_right  = ((sRect_right  + mcu_w - 1) / mcu_w) * mcu_w;
    int aligned_bottom = ((sRect_bottom + mcu_h - 1) / mcu_h) * mcu_h;
    // Clamp to image bounds.
    if (aligned_right  > img_width)  aligned_right  = img_width;
    if (aligned_bottom > img_height) aligned_bottom = img_height;
    int crop_w = aligned_right  - aligned_left;
    int crop_h = aligned_bottom - aligned_top;

    tjregion crop_region = { aligned_left, aligned_top, crop_w, crop_h };
    if (tj3SetCroppingRegion(tj, crop_region) != 0) {
        LOGE("tj3SetCroppingRegion failed: %s", tj3GetErrorStr(tj));
        tj3Destroy(tj);
        env->ReleaseByteArrayElements(jpeg_data, jpeg_buf, JNI_ABORT);
        return nullptr;
    }

    // ── 4. Choose scaling factor ──────────────────────────────────────────────
    int num_factors = 0;
    const tjscalingfactor *factors = tj3GetScalingFactors(&num_factors);
    int s = (sample_size < 1) ? 1 : sample_size;
    tjscalingfactor sf = pick_scale(s, num_factors, factors);

    if (tj3SetScalingFactor(tj, sf) != 0) {
        // Non-fatal: fall back to 1:1 if scaling isn't supported for this image.
        tjscalingfactor unity = {1, 1};
        tj3SetScalingFactor(tj, unity);
        sf = unity;
    }

    // Compute scaled output dimensions for the aligned crop region.
    // TurboJPEG uses ceil((dim * num) / denom).
    int out_w = (int)ceil((double)crop_w * sf.num / sf.denom);
    int out_h = (int)ceil((double)crop_h * sf.num / sf.denom);
    if (out_w < 1) out_w = 1;
    if (out_h < 1) out_h = 1;

    // ── 5. Allocate pixel buffer and decompress ───────────────────────────────
    size_t   buf_size  = (size_t)out_w * out_h * 4;  // RGBA, 4 bytes/px
    uint8_t *pixel_buf = (uint8_t *)malloc(buf_size);
    if (!pixel_buf) {
        LOGE("OOM: cannot allocate %zu bytes", buf_size);
        tj3Destroy(tj);
        env->ReleaseByteArrayElements(jpeg_data, jpeg_buf, JNI_ABORT);
        return nullptr;
    }

    // TJPF_RGBA matches Android Bitmap ARGB_8888 byte order.
    // TJFLAG_ACCURATEDCT: integer DCT with full precision — avoids rounding
    // errors in the fast DCT path that can also shift colours slightly.
    int rc = tj3Decompress8(
        tj,
        (const unsigned char *)jpeg_buf, (size_t)jpeg_size,
        pixel_buf,
        out_w * 4,   // row stride in bytes
        TJPF_RGBA
    );

    tj3Destroy(tj);
    env->ReleaseByteArrayElements(jpeg_data, jpeg_buf, JNI_ABORT);

    if (rc != 0) {
        LOGE("tj3Decompress8 failed");
        free(pixel_buf);
        return nullptr;
    }

    // ── 6. Compute inner crop (aligned → exact sRect) in scaled-pixel space ──
    double scale = (double)sf.num / sf.denom;
    int inner_x = (int)((sRect_left - aligned_left) * scale);
    int inner_y = (int)((sRect_top  - aligned_top)  * scale);
    int inner_w = (int)ceil((sRect_right  - sRect_left) * scale);
    int inner_h = (int)ceil((sRect_bottom - sRect_top)  * scale);
    // Clamp to decoded buffer bounds.
    if (inner_x < 0) inner_x = 0;
    if (inner_y < 0) inner_y = 0;
    if (inner_x + inner_w > out_w) inner_w = out_w - inner_x;
    if (inner_y + inner_h > out_h) inner_h = out_h - inner_y;
    if (inner_w < 1) inner_w = 1;
    if (inner_h < 1) inner_h = 1;

    // ── 7. Create Android Bitmap and copy cropped rows ────────────────────────
    result = create_bitmap(env, inner_w, inner_h);
    if (!result) {
        LOGE("create_bitmap(%d, %d) failed", inner_w, inner_h);
        free(pixel_buf);
        return nullptr;
    }

    void *bmp_pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, result, &bmp_pixels) < 0) {
        LOGE("AndroidBitmap_lockPixels failed");
        free(pixel_buf);
        return nullptr;
    }

    uint8_t *dst      = (uint8_t *)bmp_pixels;
    int      row_size = inner_w * 4;
    for (int row = 0; row < inner_h; row++) {
        const uint8_t *src = pixel_buf + ((inner_y + row) * out_w + inner_x) * 4;
        memcpy(dst, src, row_size);
        dst += row_size;
    }

    AndroidBitmap_unlockPixels(env, result);
    free(pixel_buf);
    return result;
}
