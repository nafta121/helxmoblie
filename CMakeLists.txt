cmake_minimum_required(VERSION 3.22)
project(stadium_dsp_host LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_subdirectory(dsp_core)

option(STADIUM_BUILD_TESTS "Build host C++20 DSP verification test suite" ON)
if(STADIUM_BUILD_TESTS AND EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/tests/test_dsp_suite.cpp")
    add_executable(stadium_dsp_tests tests/test_dsp_suite.cpp)
    target_link_libraries(stadium_dsp_tests PRIVATE stadium_dsp_core)
    if(CMAKE_CXX_COMPILER_ID MATCHES "Clang|GNU")
        target_compile_options(stadium_dsp_tests PRIVATE -O3 -Wall -Wextra -Wno-unused-parameter)
    endif()
endif()
