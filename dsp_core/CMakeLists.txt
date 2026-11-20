cmake_minimum_required(VERSION 3.22)
project(stadium_dsp_core LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

file(GLOB STADIUM_DSP_SOURCES
    "${CMAKE_CURRENT_SOURCE_DIR}/src/*.cpp"
)

if(STADIUM_DSP_SOURCES)
    add_library(stadium_dsp_core STATIC ${STADIUM_DSP_SOURCES})
    target_include_directories(stadium_dsp_core PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/include")
    if(CMAKE_CXX_COMPILER_ID MATCHES "Clang|GNU")
        target_compile_options(stadium_dsp_core PRIVATE -O3 -Wall -Wextra -Wno-unused-parameter)
        if(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64|arm64")
            target_compile_options(stadium_dsp_core PRIVATE -march=armv8.2-a+fp16+dotprod)
        endif()
    endif()
else()
    add_library(stadium_dsp_core INTERFACE)
    target_include_directories(stadium_dsp_core INTERFACE "${CMAKE_CURRENT_SOURCE_DIR}/include")
endif()
