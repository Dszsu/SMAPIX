cmake_minimum_required(VERSION 3.22.1)

project("smapix" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_library(log-lib log)

add_library(
    smapix
    SHARED
    arm64_hook.cpp
    bcl_swap.cpp
    native_hooks.cpp
)

target_compile_options(smapix PRIVATE -O2 -Wall -Wextra -Wno-unused-parameter)

target_link_libraries(
    smapix
    ${log-lib}
    dl
)
