#include "jpeg_core.h"

#include <turbojpeg.h>

#include <cmath>
#include <cstdlib>
#include <new>

#if defined(__unix__) || defined(__APPLE__)
#include <sys/mman.h>
#include <unistd.h>
#endif

namespace {

/** One TurboJPEG handle per decoding thread: avoids re-allocating the decompressor for every tile. */
struct ThreadHandle {
    tjhandle h = nullptr;

    ~ThreadHandle() {
        if (h) tj3Destroy(h);
    }

    tjhandle get() {
        if (!h) h = tj3Init(TJINIT_DECOMPRESS);
        return h;
    }

    /** After any error the instance is dropped so the next tile starts from a clean decompressor. */
    void reset() {
        if (h) tj3Destroy(h);
        h = nullptr;
    }
};

thread_local ThreadHandle tls_handle;

bool parse_header(JpegSource *src) {
    if (src->size < 4 || src->data[0] != 0xFF || src->data[1] != 0xD8 || src->data[2] != 0xFF) return false;
    tjhandle tj = tls_handle.get();
    if (!tj) return false;
    if (tj3DecompressHeader(tj, src->data, src->size) != 0) {
        tls_handle.reset();
        return false;
    }
    src->width = tj3Get(tj, TJPARAM_JPEGWIDTH);
    src->height = tj3Get(tj, TJPARAM_JPEGHEIGHT);
    src->subsamp = tj3Get(tj, TJPARAM_SUBSAMP);
    src->progressive = tj3Get(tj, TJPARAM_PROGRESSIVE) ? 1 : 0;
    return src->width > 0 && src->height > 0 && tj3Get(tj, TJPARAM_PRECISION) == 8;
}

tjscalingfactor pick_scale(int sample_size) {
    int n = 0;
    const tjscalingfactor *factors = tj3GetScalingFactors(&n);
    const double target = 1.0 / (double) sample_size;
    tjscalingfactor best = {1, sample_size};
    double best_val = 0.0;
    for (int i = 0; i < n; i++) {
        const double v = (double) factors[i].num / (double) factors[i].denom;
        if (v <= target + 1e-9 && v > best_val) {
            best_val = v;
            best = factors[i];
        }
    }
    return best;
}

}  // namespace

JpegSource *jpeg_open_memory(const uint8_t *data, size_t size) {
    JpegSource *src = new (std::nothrow) JpegSource();
    if (!src) return nullptr;
    src->map_base = nullptr;
    src->map_len = 0;
    src->data = data;
    src->size = size;
    if (!parse_header(src)) {
        delete src;
        return nullptr;
    }
    return src;
}

#if defined(__unix__) || defined(__APPLE__)
JpegSource *jpeg_open_fd(int fd, int64_t offset, int64_t length) {
    if (fd < 0 || offset < 0 || length < 4) return nullptr;
    const long page = sysconf(_SC_PAGESIZE) > 0 ? sysconf(_SC_PAGESIZE) : 4096;
    const int64_t delta = offset % page;
    const size_t map_len = (size_t) (length + delta);
    void *base = mmap(nullptr, map_len, PROT_READ, MAP_PRIVATE, fd, (off_t) (offset - delta));
    if (base == MAP_FAILED) return nullptr;
    JpegSource *src = new (std::nothrow) JpegSource();
    if (!src) {
        munmap(base, map_len);
        return nullptr;
    }
    src->map_base = (uint8_t *) base;
    src->map_len = map_len;
    src->data = (const uint8_t *) base + delta;
    src->size = (size_t) length;
    if (!parse_header(src)) {
        munmap(base, map_len);
        delete src;
        return nullptr;
    }
    return src;
}
#else
JpegSource *jpeg_open_fd(int, int64_t, int64_t) { return nullptr; }
#endif

void jpeg_close(JpegSource *src) {
    if (!src) return;
#if defined(__unix__) || defined(__APPLE__)
    if (src->map_base) munmap(src->map_base, src->map_len);
#endif
    delete src;
}

bool jpeg_plan(const JpegSource *src, int sample_size, int left, int top, int right, int bottom, JpegPlan *plan) {
    if (!src || !plan) return false;
    if (left < 0 || top < 0 || right > src->width || bottom > src->height || right <= left || bottom <= top) {
        return false;
    }
    const int ss = src->subsamp;
    // MCU size for the detected chroma subsampling (the crop must start on an MCU boundary).
    const int mcu_w = (ss == TJSAMP_444 || ss == TJSAMP_GRAY || ss == TJSAMP_440) ? 8 : 16;
    const int mcu_h = (ss == TJSAMP_420 || ss == TJSAMP_440) ? 16 : 8;
    // Use the largest MCU for both axes only when the sampling is unusual (411/441): always safe.
    const int mw = (ss == TJSAMP_411 || ss == TJSAMP_441) ? 32 : mcu_w;
    const int mh = (ss == TJSAMP_411 || ss == TJSAMP_441) ? 32 : mcu_h;

    int al = (left / mw) * mw;
    int at = (top / mh) * mh;
    int ar = ((right + mw - 1) / mw) * mw;
    int ab = ((bottom + mh - 1) / mh) * mh;
    if (ar > src->width) ar = src->width;
    if (ab > src->height) ab = src->height;

    const tjscalingfactor sf = pick_scale(sample_size < 1 ? 1 : sample_size);
    plan->aligned_left = al;
    plan->aligned_top = at;
    plan->crop_w = ar - al;
    plan->crop_h = ab - at;
    plan->num = sf.num;
    plan->denom = sf.denom;
    plan->out_w = (int) std::ceil((double) plan->crop_w * sf.num / sf.denom);
    plan->out_h = (int) std::ceil((double) plan->crop_h * sf.num / sf.denom);
    if (plan->out_w < 1) plan->out_w = 1;
    if (plan->out_h < 1) plan->out_h = 1;

    const double scale = (double) sf.num / sf.denom;
    int ix = (int) std::lround((left - al) * scale);
    int iy = (int) std::lround((top - at) * scale);
    int iw = (int) std::ceil((right - left) * scale);
    int ih = (int) std::ceil((bottom - top) * scale);
    if (ix < 0) ix = 0;
    if (iy < 0) iy = 0;
    if (ix + iw > plan->out_w) iw = plan->out_w - ix;
    if (iy + ih > plan->out_h) ih = plan->out_h - iy;
    if (iw < 1) iw = 1;
    if (ih < 1) ih = 1;
    plan->inner_x = ix;
    plan->inner_y = iy;
    plan->inner_w = iw;
    plan->inner_h = ih;
    plan->exact = ix == 0 && iy == 0 && iw == plan->out_w && ih == plan->out_h;
    return true;
}

int jpeg_decode(const JpegSource *src, const JpegPlan &plan, uint8_t *dst, size_t pitch) {
    tjhandle tj = tls_handle.get();
    if (!tj || !src || !dst || pitch < (size_t) plan.out_w * 4) return -1;
    // Header first: it (re)initialises the instance for this image, so a handle used for another page is safe.
    if (tj3DecompressHeader(tj, src->data, src->size) != 0) {
        tls_handle.reset();
        return -1;
    }
    // Nearest-neighbour chroma upsampling: "fancy" upsampling needs context pixels across the crop
    // edge, so neighbouring tiles would disagree on their shared chroma column (visible seams).
    // The fast path is tile-independent (bit-exact with a full decode) and cheaper on the CPU.
    tj3Set(tj, TJPARAM_FASTUPSAMPLE, 1);
    // The scaling factor must be set BEFORE the cropping region, and the region is expressed in SCALED
    // coordinates (libjpeg-turbo >= 3 rejects a source-coordinate region once a scale is applied).
    tjscalingfactor sf = {plan.num, plan.denom};
    if (tj3SetScalingFactor(tj, sf) != 0) {
        tls_handle.reset();
        return -1;
    }
    tjregion region = {plan.aligned_left * plan.num / plan.denom, plan.aligned_top * plan.num / plan.denom,
                       plan.out_w, plan.out_h};
    if (tj3SetCroppingRegion(tj, region) != 0) {
        tls_handle.reset();
        return -1;
    }
    if (tj3Decompress8(tj, src->data, src->size, dst, (int) (pitch), TJPF_RGBA) != 0) {
        tls_handle.reset();
        return -1;
    }
    return 0;
}
