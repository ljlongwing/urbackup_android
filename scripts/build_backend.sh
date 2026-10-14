#!/bin/bash
# Builds the UrBackup client backend (urbackupclientbackend) with the Android NDK
# and copies it into app/src/main/jniLibs/<abi>/libubbackend.so.
#
# usage: scripts/build_backend.sh <path to urbackup_backend checkout> [abi...]
#   abi: arm64-v8a (default), x86_64
#
# Needs: ANDROID_NDK_HOME (NDK r27 or newer), VCPKG_ROOT, cmake, ninja.
# The backend checkout must be the 2.6.x branch (CMake build).
set -e

SRC=$(realpath "${1:?usage: $0 <urbackup_backend checkout> [abi...]}")
shift
ABIS=("$@")
[ ${#ABIS[@]} -eq 0 ] && ABIS=(arm64-v8a)

: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME}"
: "${VCPKG_ROOT:?set VCPKG_ROOT}"

ROOT=$(realpath "$(dirname "$0")/..")
API=30

for ABI in "${ABIS[@]}"; do
	case $ABI in
		arm64-v8a) TRIPLET=arm64-android-ub ;;
		x86_64) TRIPLET=x64-android-ub ;;
		*) echo "unknown abi $ABI"; exit 1 ;;
	esac

	B="$ROOT/backend-build/$ABI"
	echo "Building backend for $ABI in $B"
	# a build folder can only be used for the source it was configured for
	if [ -f "$B/CMakeCache.txt" ] && ! grep -q "^CMAKE_HOME_DIRECTORY:INTERNAL=$SRC\$" "$B/CMakeCache.txt"; then
		echo "Build folder was configured for another source, starting over"
		rm -rf "$B"
	fi

	# libc++ removed std::auto_ptr (C++17), which the backend still uses
	# (cmake/toolchains/zig.cmake in the backend does the same)
	cmake -S "$SRC" -B "$B" -G Ninja -DCMAKE_BUILD_TYPE=RelWithDebInfo \
		-DCMAKE_TOOLCHAIN_FILE="$VCPKG_ROOT/scripts/buildsystems/vcpkg.cmake" \
		-DVCPKG_CHAINLOAD_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
		-DVCPKG_OVERLAY_TRIPLETS="$ROOT/scripts/triplets" -DVCPKG_TARGET_TRIPLET=$TRIPLET \
		-DVCPKG_INSTALL_OPTIONS=--clean-after-build \
		-DANDROID_ABI=$ABI -DANDROID_PLATFORM=android-$API \
		-DURBACKUP_BUILD_SERVER=OFF -DURBACKUP_BUILD_CLIENT=ON -DURBACKUP_CLIENT_HEADLESS=ON \
		"-DCMAKE_CXX_FLAGS=-D_LIBCPP_ENABLE_CXX17_REMOVED_AUTO_PTR -D_LIBCPP_DISABLE_DEPRECATION_WARNINGS"
	cmake --build "$B" --target urbackupclientbackend

	mkdir -p "$ROOT/app/src/main/jniLibs/$ABI"
	"$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" \
		-o "$ROOT/app/src/main/jniLibs/$ABI/libubbackend.so" "$B/urbackupclientbackend"
	ls -la "$ROOT/app/src/main/jniLibs/$ABI/libubbackend.so"
done
