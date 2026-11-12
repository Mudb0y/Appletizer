#!/usr/bin/env bash
# Convert both engines for linux-arm64 or android-arm64, and on linux-arm64
# also build vesay. Run inside `nix develop`, after fetch-firmware.sh.
#   libve131.so  VE 1.3.1 from iOS 7.1.2's voiced: iOS 7 and 8 voices
#   libve151.so  VE 1.5.1 from iOS 10.3.3's TTSSpeechBundle: iOS 9 to 15 voices
set -euo pipefail

target=${1:?usage: build-engine.sh linux-arm64|android-arm64}
root=$(cd "$(dirname "$0")/.." && pwd)
fw=$root/work/firmware
macho2elf=${MACHO2ELF:-$root/scripts/macho2elf.py}
out=$root/work/build/$target
ndk_bin=${ANDROID_NDK_ROOT:-}/toolchains/llvm/prebuilt/linux-x86_64/bin

# Gradle packages everything under jniLibs; vesay finds the engines next to it.
case $target in
linux-arm64)
    os=linux cc=aarch64-unknown-linux-gnu-gcc libs=$out
    cxx=(--lib-dir "$AARCH64_LIBCXX") ;;
android-arm64)
    os=android cc=$ndk_bin/aarch64-linux-android26-clang libs=$out/jniLibs/arm64-v8a
    cxx=() ;;
*) echo "build-engine: unknown target $target" >&2; exit 2 ;;
esac

rm -rf "$out"
mkdir -p "$libs"

# convert LIB SOURCE MACHO2ELF_FLAGS...
convert() {
    local lib=$1 src=$2 log=$out/${1%.so}.log
    shift 2
    [ -f "$src" ] || { echo "build-engine: no $src; run fetch-firmware.sh" >&2; exit 1; }
    echo "build-engine: converting $lib for $target"
    "$M2E_PYTHON" -I "$macho2elf" "$src" -o "$libs/$lib" --workdir "$out/m2e-${lib%.so}" \
        --os "$os" --cc "$cc" --trap-missing "$@" > "$log" 2>&1 || { tail -20 "$log" >&2; exit 1; }
}

# VE 1.3.1's own code calls only libc; Apple's daemon code around it never runs.
convert libve131.so "$fw/ios7/voiced" --skip-initializers --no-cxx-runtime
# VE 1.5.1 runs Apple's C++ Gryphon components, whose globals its initializers build.
convert libve151.so "$fw/ios10/TTSSpeechBundle.arm64" "${cxx[@]}"

if [ "$target" = linux-arm64 ]; then
    $cc -O2 -Wall -Wextra -o "$out/vesay" "$root/native/vesay.c" "$root/native/engine.c" \
        -Wl,-rpath,'$ORIGIN' -ldl -lpthread
fi

# The iOS 5/6 engine is 32-bit ARM, which arm64-only phones cannot run, so
# libvauto.so carries it as an image and runs it inside dynarmic.
echo "build-engine: building libvauto.so for $target"
"$M2E_PYTHON" -I "$root/scripts/armv7-image.py" "$fw/ios6/voiced" "$out/vauto.img" > /dev/null
bridge=("$root/native/vauto/bridge.cpp" -x assembler-with-cpp "-DIMAGE=\"$out/vauto.img\""
        "$root/native/vauto/image.S" -x none)
case $target in
android-arm64)
    # Kept between builds, since dynarmic takes minutes; rebuilt when its source changes.
    dyn=$root/work/build/dynarmic-android
    cm=$ANDROID_HOME/cmake/3.22.1/bin
    if [ "$(cat "$dyn/.source" 2> /dev/null)" != "$DYNARMIC_SRC" ]; then
        echo "build-engine: building dynarmic for $target"
        rm -rf "$dyn"
        { "$cm/cmake" -S "$DYNARMIC_SRC" -B "$dyn" -G Ninja -DCMAKE_MAKE_PROGRAM="$cm/ninja" \
              -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake" \
              -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=26 -DANDROID_STL=c++_shared \
              -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
              -DDYNARMIC_USE_BUNDLED_EXTERNALS=ON -DDYNARMIC_TESTS=OFF -DDYNARMIC_WARNINGS_AS_ERRORS=OFF \
              -DDYNARMIC_USE_PRECOMPILED_HEADERS=OFF -DBoost_INCLUDE_DIR="$BOOST_INCLUDE" -DBoost_NO_BOOST_CMAKE=ON \
              -DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=BOTH -DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=BOTH &&
          "$cm/cmake" --build "$dyn" -j"$(nproc)"; } > "$out/dynarmic.log" 2>&1 ||
            { tail -20 "$out/dynarmic.log" >&2; exit 1; }
        echo "$DYNARMIC_SRC" > "$dyn/.source"
    fi
    # Only atzb_* is exported; the static C++ runtime and dynarmic stay private.
    "$ndk_bin/aarch64-linux-android26-clang++" -std=c++20 -O2 -fPIC -shared -Wall -Wextra \
        -I"$root/native" -I"$DYNARMIC_SRC/src" -I"$DYNARMIC_SRC/externals/mcl/include" -I"$BOOST_INCLUDE" \
        "${bridge[@]}" "$dyn/src/dynarmic/libdynarmic.a" "$dyn/externals/mcl/src/libmcl.a" \
        "$dyn/externals/fmt/libfmt.a" -static-libstdc++ \
        -Wl,--version-script="$root/native/vauto/exports.map" -Wl,--gc-sections \
        -Wl,-z,max-page-size=16384 -Wl,-s -o "$libs/libvauto.so"
    ;;
linux-arm64)
    # Tested on this machine's own architecture, where nixpkgs builds dynarmic.
    mkdir -p "$out/host"
    c++ -std=c++20 -O2 -fPIC -shared -Wall -Wextra -I"$root/native" -I"$HOST_DYNARMIC/include" \
        "${bridge[@]}" -L"$HOST_DYNARMIC/lib" -ldynarmic -Wl,-rpath,"$HOST_DYNARMIC/lib" \
        -o "$out/host/libvauto.so"
    cc -O2 -Wall -Wextra -o "$out/host/vesay" "$root/native/vesay.c" "$root/native/engine.c" \
        -Wl,-rpath,'$ORIGIN' -ldl -lpthread
    ;;
esac
echo "build-engine: $(ls "$libs"/*.so | tr '\n' ' ')"
