cmake_minimum_required(VERSION 3.22)
project(ssiv_jpeg_decoder)

# ── libjpeg-turbo ─────────────────────────────────────────────────────────────
# Downloaded and built from source at JitPack build time.
# Building as a static library so the final .so is self-contained — no
# libturbojpeg.so dependency needs to be shipped separately.
include(FetchContent)
FetchContent_Declare(
    libjpeg_turbo
    URL https://github.com/libjpeg-turbo/libjpeg-turbo/releases/download/3.1.4.1/libjpeg-turbo-3.1.4.1.tar.gz
    URL_HASH SHA256=ecae8008e2cc9ade2f2c1bb9d5e6d4fb73e7c433866a056bd82980741571a022
)
set(ENABLE_SHARED  OFF CACHE BOOL "" FORCE)
set(ENABLE_STATIC  ON  CACHE BOOL "" FORCE)
set(WITH_SIMD      ON  CACHE BOOL "" FORCE)  # NEON on arm64, SSE on x86
set(WITH_TURBOJPEG ON  CACHE BOOL "" FORCE)  # TurboJPEG high-level API
set(WITH_JAVA      OFF CACHE BOOL "" FORCE)
set(WITH_JPEG8     ON  CACHE BOOL "" FORCE)  # 8-bit samples (standard JPEG)
FetchContent_MakeAvailable(libjpeg_turbo)

# ── JNI bridge ────────────────────────────────────────────────────────────────
add_library(ssiv_jpeg_decoder SHARED jpeg_region_decoder.cpp)

target_include_directories(ssiv_jpeg_decoder PRIVATE
    ${libjpeg_turbo_SOURCE_DIR}
    ${libjpeg_turbo_BINARY_DIR}   # generated jconfig.h / turbojpeg.h land here
)

target_link_libraries(ssiv_jpeg_decoder
    turbojpeg-static   # libjpeg-turbo static target name
    jnigraphics        # AndroidBitmap_* functions
    android
    log
)
