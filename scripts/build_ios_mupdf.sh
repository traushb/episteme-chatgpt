#!/bin/sh
set -eu

# Builds MuPDF and the reader's bridge (reader_mupdf_bridge.c) into one static
# archive, libreadermupdf.a, for the static ReaderShared framework.
#
# MuPDF bundles its own libjpeg, zlib, FreeType and others, and Skia (in the
# same framework) ships libjpeg-turbo under the same jpeg_* names. A plain
# archive would let the final link bind one library's calls to the other's
# functions. So everything is prelinked into a single object that exports only
# the bridge's rmu_* functions; MuPDF and its libraries stay private to it.

if [ "$#" -ne 3 ]; then
  echo "usage: $0 <iphoneos|iphonesimulator> <arch> <output-directory>" >&2
  exit 2
fi

sdk_name="$1"
arch_name="$2"
output_directory="$3"
repository_root="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
source_directory="$repository_root/third_party/mupdf"
bridge_directory="$repository_root/shared/src/nativeInterop/cinterop"
developer_directory="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"

if [ ! -f "$source_directory/include/mupdf/fitz.h" ]; then
  echo "MuPDF source is missing at $source_directory (the iOS workflow fetches it)" >&2
  exit 1
fi

export DEVELOPER_DIR="$developer_directory"
# make imports the environment; flags meant for the app build must not leak in.
unset CFLAGS CPPFLAGS CXXFLAGS LDFLAGS LIBS
sdk_path="$(xcrun --sdk "$sdk_name" --show-sdk-path)"
deployment_flag="-miphoneos-version-min=16.0"
if [ "$sdk_name" = "iphonesimulator" ]; then
  deployment_flag="-mios-simulator-version-min=16.0"
fi
compiler="$(xcrun --sdk "$sdk_name" --find clang) -arch $arch_name -isysroot $sdk_path $deployment_flag"
build_directory="$output_directory/build"

rm -rf "$output_directory"
mkdir -p "$build_directory"

# MuPDF includes <sys/random.h> on Apple platforms for getentropy(), but the
# iOS SDK has no such header. Its only use is seeding MuPDF's stream cipher;
# arc4random_buf() is the platform's own secure source.
shim_directory="$build_directory/include-shim"
mkdir -p "$shim_directory/sys"
cat > "$shim_directory/sys/random.h" <<'SHIM'
#ifndef READER_MUPDF_SYS_RANDOM_H
#define READER_MUPDF_SYS_RANDOM_H
#include <stdlib.h>
#define getentropy reader_mupdf_getentropy
static inline int reader_mupdf_getentropy(void *buffer, size_t length)
{
    arc4random_buf(buffer, length);
    return 0;
}
#endif
SHIM

# Only the PDF interpreter and the rasterizer: no JavaScript, HTML/EPUB, XPS,
# SVG, CBZ or image documents, and no CJK or Noto fallback fonts (about 50 MB).
# The 14 standard PDF fonts stay built in.
make -C "$source_directory" -j"$(sysctl -n hw.ncpu)" libs \
  OS=ios \
  build=release \
  OUT="$build_directory" \
  CC="$compiler" \
  AR="$(xcrun --sdk "$sdk_name" --find ar)" \
  RANLIB="$(xcrun --sdk "$sdk_name" --find ranlib)" \
  XCFLAGS="-fPIC -I$shim_directory -DFZ_ENABLE_CBZ=0 -DFZ_ENABLE_IMG=0" \
  html=no mujs=no brotli=no extract=no xps=no svg=no tofu=yes tofu_cjk=yes barcode=no \
  HAVE_X11=no HAVE_GLUT=no HAVE_CURL=no HAVE_LIBCRYPTO=no

$compiler -std=c99 -O2 -fPIC \
  -I "$source_directory/include" \
  -I "$bridge_directory" \
  -c "$bridge_directory/reader_mupdf_bridge.c" \
  -o "$build_directory/reader_mupdf_bridge.o"

printf '_rmu_*\n' > "$build_directory/exported_symbols.txt"
$compiler -r -nostdlib \
  -Wl,-exported_symbols_list,"$build_directory/exported_symbols.txt" \
  -o "$build_directory/readermupdf.o" \
  "$build_directory/reader_mupdf_bridge.o" \
  "$build_directory/libmupdf.a" \
  "$build_directory/libmupdf-third.a"

# MuPDF must be inside the object, not left for the app link to find.
missing="$(xcrun --sdk "$sdk_name" nm -u "$build_directory/readermupdf.o" | grep -E '^_(fz|pdf)_' || true)"
if [ -n "$missing" ]; then
  echo "The prelinked MuPDF object still needs:" >&2
  printf '%s\n' "$missing" | head -n 20 >&2
  exit 1
fi

xcrun --sdk "$sdk_name" libtool -static \
  -o "$output_directory/libreadermupdf.a" \
  "$build_directory/readermupdf.o"

# Fail here rather than at the app link if anything but the bridge leaked out.
exported="$(xcrun --sdk "$sdk_name" nm -gU "$output_directory/libreadermupdf.a" | awk 'NF == 3 { print $3 }')"
leaked="$(printf '%s\n' "$exported" | grep -v '^_rmu_' || true)"
if [ -n "$leaked" ]; then
  echo "libreadermupdf.a exports more than the bridge:" >&2
  printf '%s\n' "$leaked" | head -n 20 >&2
  exit 1
fi
if ! printf '%s\n' "$exported" | grep -qx '_rmu_render_bgra'; then
  echo "libreadermupdf.a does not export the bridge" >&2
  exit 1
fi
rm -rf "$build_directory"
