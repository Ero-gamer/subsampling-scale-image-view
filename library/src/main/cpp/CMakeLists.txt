cmake_minimum_required(VERSION 3.22)
project(ssiv_jpeg_decoder)

include(ExternalProject)

# ── libjpeg-turbo 3.1.4.1 ────────────────────────────────────────────────────
# FetchContent / add_subdirectory() are explicitly blocked by libjpeg-turbo's
# own CMakeLists.txt. ExternalProject_Add builds it in a fully isolated
# sub-build so it never gets included as a subdirectory.
set(LJPEG_PREFIX      "${CMAKE_BINARY_DIR}/ljt")
set(LJPEG_INSTALL_DIR "${LJPEG_PREFIX}/install")
set(LJPEG_INCLUDE_DIR "${LJPEG_INSTALL_DIR}/include")
set(LJPEG_LIB         "${LJPEG_INSTALL_DIR}/lib/libturbojpeg.a")

ExternalProject_Add(libjpeg_turbo_ext
    PREFIX      "${LJPEG_PREFIX}"
    URL         https://github.com/libjpeg-turbo/libjpeg-turbo/releases/download/3.1.4.1/libjpeg-turbo-3.1.4.1.tar.gz
    URL_HASH    SHA256=ecae8008e2cc9ade2f2c1bb9d5e6d4fb73e7c433866a056bd82980741571a022

    CMAKE_ARGS
        # Android cross-compilation — forward every variable AGP passes to us
        -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
        -DCMAKE_SYSTEM_NAME=Android
        -DCMAKE_SYSTEM_VERSION=${ANDROID_PLATFORM}
        -DANDROID_ABI=${ANDROID_ABI}
        -DANDROID_PLATFORM=${ANDROID_PLATFORM}
        -DANDROID_NDK=${ANDROID_NDK}
        -DCMAKE_ANDROID_ARCH_ABI=${ANDROID_ABI}
        -DCMAKE_ANDROID_NDK=${CMAKE_ANDROID_NDK}
        -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}
        -DCMAKE_BUILD_TYPE=${CMAKE_BUILD_TYPE}
        # Only build what we need
        -DENABLE_SHARED=OFF
        -DENABLE_STATIC=ON
        -DWITH_SIMD=ON
        -DWITH_TURBOJPEG=ON
        -DWITH_JAVA=OFF
        -DWITH_JPEG8=ON
        -DCMAKE_INSTALL_PREFIX=${LJPEG_INSTALL_DIR}

    # Tell Ninja about the output so it knows this target produces the .a file
    BUILD_BYPRODUCTS "${LJPEG_LIB}"

    # Use cmake --install (works regardless of generator — Ninja or Make)
    INSTALL_COMMAND
        ${CMAKE_COMMAND} --install "${LJPEG_PREFIX}/src/libjpeg_turbo_ext-build"
            --prefix "${LJPEG_INSTALL_DIR}"

    TEST_COMMAND ""
)

# Import the static library as a CMake target so our JNI lib can link it
add_library(turbojpeg_static STATIC IMPORTED GLOBAL)
add_dependencies(turbojpeg_static libjpeg_turbo_ext)
set_target_properties(turbojpeg_static PROPERTIES
    IMPORTED_LOCATION "${LJPEG_LIB}"
)

# Create the include dir at configure time so target_include_directories
# doesn't fail because the directory doesn't exist yet (it is created at build time)
file(MAKE_DIRECTORY "${LJPEG_INCLUDE_DIR}")

# ── JNI bridge ────────────────────────────────────────────────────────────────
add_library(ssiv_jpeg_decoder SHARED jpeg_region_decoder.cpp)

add_dependencies(ssiv_jpeg_decoder libjpeg_turbo_ext)

target_include_directories(ssiv_jpeg_decoder PRIVATE "${LJPEG_INCLUDE_DIR}")

target_link_libraries(ssiv_jpeg_decoder
    turbojpeg_static
    jnigraphics
    android
    log
)
