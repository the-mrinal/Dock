cmake_minimum_required(VERSION 3.22.1)
project(receiver_playfair)

add_library(playfair STATIC
    playfair/playfair.c
    playfair/omg_hax.c
    playfair/modified_md5.c
    playfair/sap_hash.c
    playfair/hand_garble.c
)

target_include_directories(playfair PUBLIC playfair)

# Apple's reference ALAC decoder (APSL 2.0 / Apache 2.0). Vendored under
# alac/. Used as a fallback path when MediaCodec lacks `audio/alac` —
# specifically Fire TV AFTKA, see AlacSoftwareDecoder.kt and issue #14.
add_library(alac STATIC
    alac/ALACDecoder.cpp
    alac/ALACBitUtilities.c
    alac/ag_dec.c
    alac/dp_dec.c
    alac/matrix_dec.c
    alac/EndianPortable.c
)

# The vendored sources rely on `#include "ALACDecoder.h"`-style relative
# paths internally; PUBLIC so the JNI bridge below can `#include "alac/..."`.
target_include_directories(alac PUBLIC alac)

# The codec was written for both 32-bit and 64-bit POSIX targets; silence
# the `register` storage-class warning that's now a C++17 error.
target_compile_options(alac PRIVATE -Wno-deprecated-register -Wno-multichar)

add_library(receiver_playfair SHARED
    receiver_playfair.c
    alac_jni.cpp
)
target_link_libraries(receiver_playfair playfair alac log)
