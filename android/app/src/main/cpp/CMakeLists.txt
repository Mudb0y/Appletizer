cmake_minimum_required(VERSION 3.22.1)
project(appletizer C)

# libvoiced.so comes from scripts/stage-android.sh, not from this build.
set(ENGINE_DIR "" CACHE PATH "Directory holding the converted libvoiced.so")
if(NOT EXISTS "${ENGINE_DIR}/libvoiced.so")
    message(FATAL_ERROR "No ${ENGINE_DIR}/libvoiced.so; run scripts/stage-android.sh first")
endif()

set(NATIVE "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../native")

add_library(voiced SHARED IMPORTED)
set_target_properties(voiced PROPERTIES IMPORTED_LOCATION "${ENGINE_DIR}/libvoiced.so")

add_library(appletizer SHARED appletizer_jni.c "${NATIVE}/engine.c")
target_include_directories(appletizer PRIVATE "${NATIVE}")
target_compile_options(appletizer PRIVATE -Wall -Wextra)
target_link_libraries(appletizer voiced log)
