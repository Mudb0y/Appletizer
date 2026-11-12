cmake_minimum_required(VERSION 3.22.1)
project(appletizer C)

set(NATIVE "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../native")

add_library(appletizer SHARED appletizer_jni.c "${NATIVE}/engine.c")
target_include_directories(appletizer PRIVATE "${NATIVE}")
target_compile_options(appletizer PRIVATE -Wall -Wextra)
target_link_libraries(appletizer dl log)
