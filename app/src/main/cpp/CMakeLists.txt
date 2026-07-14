cmake_minimum_required(VERSION 3.22.1)

project(XenSynthNative)

enable_language(ASM)

set(CMAKE_SHARED_LINKER_FLAGS
        "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384")

set(FLUID_SYNTH_LIB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/fluidsynth/lib/${ANDROID_ABI})
set(FLUID_SYNTH_LIB_FILES
        libfluidsynth.so
        libglib-2.0.so
        libgthread-2.0.so
        libpcre2-8.so
        libiconv.so
        libintl.so
        libomp.so
        libc++_shared.so
        )

include_directories(
        ${CMAKE_CURRENT_SOURCE_DIR}/fluidsynth/include
        ${CMAKE_CURRENT_SOURCE_DIR}/fluidsynth/include/glib-2.0
        ${CMAKE_CURRENT_SOURCE_DIR}/fluidsynth/lib/glib-2.0/include
        ${CMAKE_CURRENT_SOURCE_DIR}/oboe/include
)

set(SOUNDFONT_BLOB "${CMAKE_CURRENT_SOURCE_DIR}/soundfont/7f3a9c1d.bin")
if (NOT EXISTS "${SOUNDFONT_BLOB}")
    message(FATAL_ERROR "Encrypted SoundFont blob is missing: ${SOUNDFONT_BLOB}")
endif ()

file(READ "${SOUNDFONT_BLOB}" SOUNDFONT_BLOB_MAGIC OFFSET 0 LIMIT 8 HEX)
if (NOT SOUNDFONT_BLOB_MAGIC STREQUAL "9d72b41e43e80da6")
    message(FATAL_ERROR "Refusing to embed an unrecognized or unencrypted SoundFont blob")
endif ()

set(EMBEDDED_SOUNDFONT_ASM "${CMAKE_CURRENT_BINARY_DIR}/EmbeddedSoundFont.S")
configure_file(
        ${CMAKE_CURRENT_SOURCE_DIR}/EmbeddedSoundFont.S.in
        ${EMBEDDED_SOUNDFONT_ASM}
        @ONLY
)
set_source_files_properties(
        ${EMBEDDED_SOUNDFONT_ASM}
        PROPERTIES OBJECT_DEPENDS "${SOUNDFONT_BLOB}"
)

add_library(xenaudio SHARED
        XenAudioEngine.cpp
        ${EMBEDDED_SOUNDFONT_ASM}
        )

add_library(QmBlur SHARED
        qmblurview/BlurNative.c
        )

foreach (FLUID_SYNTH_LIB_FILE ${FLUID_SYNTH_LIB_FILES})
    add_library(${FLUID_SYNTH_LIB_FILE} SHARED IMPORTED)
    set_target_properties(${FLUID_SYNTH_LIB_FILE} PROPERTIES
            IMPORTED_LOCATION "${FLUID_SYNTH_LIB_DIR}/${FLUID_SYNTH_LIB_FILE}"
            )
    target_link_libraries(xenaudio ${FLUID_SYNTH_LIB_FILE})
endforeach ()

add_library(oboe SHARED IMPORTED)
set_target_properties(oboe PROPERTIES
        IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/oboe/libs/android.${ANDROID_ABI}/liboboe.so"
        )

target_link_libraries(xenaudio
        oboe
        android
        log
        )

target_link_libraries(QmBlur
        android
        jnigraphics
        log
        )

target_link_options(QmBlur PRIVATE "-Wl,-z,max-page-size=16384")
