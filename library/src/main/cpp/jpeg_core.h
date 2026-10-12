// Platform-independent JPEG region decoding on top of libjpeg-turbo (TurboJPEG 3 API).
// The file is memory-mapped (never copied into the Java heap); a decode only touches the pages
// that hold the MCU rows it needs. Kept free of JNI so it can be unit-tested on the host.
#pragma once

#include <cstddef>
#include <cstdint>

struct JpegSource {
    uint8_t *map_base;   // mmap base (page aligned) or nullptr for caller-owned memory
    size_t map_len;
    const uint8_t *data; // start of the JPEG stream
    size_t size;
    int width;
    int height;
    int subsamp;         // TJSAMP_*
    int progressive;     // 1 for progressive / multi-scan
};

/** Everything needed to decode one tile; computed once, shared by the direct and the staged path. */
struct JpegPlan {
    int aligned_left, aligned_top, crop_w, crop_h;  // MCU aligned crop in source pixels
    int num, denom;                                 // chosen scaling factor
    int out_w, out_h;                               // size of the decoded (aligned) crop
    int inner_x, inner_y, inner_w, inner_h;         // exact tile inside the decoded crop
    bool exact;                                     // the crop already equals the tile (no staging)
};

/** Maps [offset, offset+length) of fd read-only and parses the JPEG header. nullptr: not a JPEG / error. */
JpegSource *jpeg_open_fd(int fd, int64_t offset, int64_t length);

/** Wraps caller-owned memory (tests). The memory must outlive the source. */
JpegSource *jpeg_open_memory(const uint8_t *data, size_t size);

void jpeg_close(JpegSource *src);

/** Plans a decode of the source rectangle at 1/sample_size scale. false when the rectangle is invalid. */
bool jpeg_plan(const JpegSource *src, int sample_size, int left, int top, int right, int bottom, JpegPlan *plan);

/**
 * Decodes the planned (aligned) crop as RGBA into [dst] with row pitch [pitch] bytes
 * (at least plan.out_w * 4, dst must hold plan.out_h rows). 0 on success.
 */
int jpeg_decode(const JpegSource *src, const JpegPlan &plan, uint8_t *dst, size_t pitch);
