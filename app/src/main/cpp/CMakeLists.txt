cmake_minimum_required(VERSION 3.22.1)

project(XenSynthNative)

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

add_library(xenaudio SHARED
        XenAudioEngine.cpp
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
