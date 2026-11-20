cmake_minimum_required(VERSION 3.22.1)
project(stadium_dsp_android LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Reuse portable clean-room dsp_core library directly
add_subdirectory(${CMAKE_CURRENT_LIST_DIR}/../../../../dsp_core ${CMAKE_CURRENT_BINARY_DIR}/dsp_core_build)

# Google Oboe Prefab AAR
find_package(oboe REQUIRED CONFIG)

add_library(stadium_dsp SHARED
    OboeGuitarEngine.cpp
    NativeDspBridge.cpp
)

target_include_directories(stadium_dsp PRIVATE
    ${CMAKE_CURRENT_LIST_DIR}
)

if (ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_options(stadium_dsp PRIVATE -O3 -fno-math-errno -march=armv8.2-a+fp16+dotprod)
    target_compile_options(stadium_dsp_core PRIVATE -O3 -fno-math-errno -march=armv8.2-a+fp16+dotprod)
else()
    target_compile_options(stadium_dsp PRIVATE -O3 -fno-math-errno)
    target_compile_options(stadium_dsp_core PRIVATE -O3 -fno-math-errno)
endif()

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(stadium_dsp PRIVATE
    stadium_dsp_core
    oboe::oboe
    ${log-lib}
    ${android-lib}
)
