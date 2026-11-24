#!/usr/bin/env bash
# Lint (and with --fix, auto-fix) the non-Kotlin code of SSIV: C++ (clang-format + cppcheck),
# GLSL ES 3.00 shaders (glslangValidator) and Python (ruff).
# Needs: clang-format, cppcheck, glslangValidator (KhronosGroup/glslang), ruff.   Usage: scripts/lint-native.sh [--fix]
set -euo pipefail
cd "$(dirname "$0")/.."
FIX=0; [ "${1:-}" = "--fix" ] && FIX=1
cpp=library/src/main/cpp
shaders=library/src/main/assets
if [ "$FIX" = 1 ]; then
  clang-format -i "$cpp"/*.cpp "$cpp"/*.h
  ruff check --fix scripts
  ruff format scripts
else
  clang-format --dry-run -Werror "$cpp"/*.cpp "$cpp"/*.h
  ruff check scripts
  ruff format --check scripts
fi
# Warning-level static analysis; JNI headers come from the JDK, turbojpeg.h is not required.
jni=${JAVA_HOME:-/usr/lib/jvm/default-java}/include
cppcheck --language=c++ --std=c++17 --enable=warning,performance,portability --inline-suppr \
  --suppress=missingIncludeSystem --suppress=unusedFunction -q --error-exitcode=1 \
  -I"$jni" -I"$jni/linux" "$cpp"/*.cpp "$cpp"/*.h
for f in "$shaders"/*.frag; do glslangValidator -S frag "$f"; done
echo "native lint OK"
