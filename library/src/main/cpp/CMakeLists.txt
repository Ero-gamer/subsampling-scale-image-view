cmake_minimum_required(VERSION 3.22)
project(ssiv_jpeg_decoder)

include(ExternalProject)

# ── libjpeg-turbo via ExternalProject_Add ─────────────────────────────────────
# libjpeg-turbo's own CMakeLists.txt explicitly forbids add_subdirectory() /
# FetchContent_MakeAvailable(). ExternalProject_Add builds it as a fully
# isolated sub-build, then we import the resulting static library.
set(LJPEG_INSTALL_DIR "${CMAKE_BINARY_DIR}/libjpeg-turbo-install")
set(LJPEG_LIB         "${LJPEG_INSTALL_DIR}/lib/libturbojpeg.a")
set(LJPEG_INCLUDE_DIR "${LJPEG_INSTALL_DIR}/include")

ExternalProject_Add(libjpeg_turbo_ext
    URL https://github.com/libjpeg-turbo/libjpeg-turbo/releases/download/3.1.4.1/libjpeg-turbo-3.1.4.1.tar.gz
    URL_HASH SHA256=ecae8008e2cc9ade2f2c1bb9d5e6d4fb73e7c433866a056bd82980741571a022

    CMAKE_ARGS
        # Forward the Android toolchain so libjpeg-turbo cross-compiles correctly
        -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
        -DANDROID_ABI=${ANDROID_ABI}
        -DANDROID_PLATFORM=${ANDROID_PLATFORM}
        -DANDROID_NDK=${ANDROID_NDK}
        -DCMAKE_ANDROID_NDK=${CMAKE_ANDROID_NDK}
        -DCMAKE_BUILD_TYPE=${CMAKE_BUILD_TYPE}
        -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}
        # Build only what we need
        -DENABLE_SHARED=OFF
        -DENABLE_STATIC=ON
        -DWITH_SIMD=ON        # NEON on arm64-v8a, SSE on x86_64
        -DWITH_TURBOJPEG=ON   # TurboJPEG high-level API (tjDecompress2)
        -DWITH_JAVA=OFF
        -DWITH_JPEG8=ON       # Standard 8-bit JPEG samples
        # Install into our local prefix
        -DCMAKE_INSTALL_PREFIX=${LJPEG_INSTALL_DIR}

    # Only build + install; skip test and package steps
    BUILD_BYPRODUCTS ${LJPEG_LIB}
    INSTALL_COMMAND  ${CMAKE_MAKE_PROGRAM} install
    TEST_COMMAND     ""
)

# Import the static library so our target can depend on it
add_library(turbojpeg_static STATIC IMPORTED GLOBAL)
add_dependencies(turbojpeg_static libjpeg_turbo_ext)
set_target_properties(turbojpeg_static PROPERTIES
    IMPORTED_LOCATION ${LJPEG_LIB}
)

# ── JNI bridge ────────────────────────────────────────────────────────────────
add_library(ssiv_jpeg_decoder SHARED jpeg_region_decoder.cpp)

# The ExternalProject installs headers into LJPEG_INCLUDE_DIR — but that
# directory doesn't exist at configure time, only after the build step.
# file(MAKE_DIRECTORY) ensures CMake doesn't complain about a missing include dir.
file(MAKE_DIRECTORY ${LJPEG_INCLUDE_DIR})

target_include_directories(ssiv_jpeg_decoder PRIVATE ${LJPEG_INCLUDE_DIR})

target_link_libraries(ssiv_jpeg_decoder
    turbojpeg_static
    jnigraphics        # AndroidBitmap_* API
    android
    log
)

add_dependencies(ssiv_jpeg_decoder libjpeg_turbo_ext)
