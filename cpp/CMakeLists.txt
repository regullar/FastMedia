cmake_minimum_required(VERSION 3.20)
project(FastMedia)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

cmake_policy(SET CMP0091 NEW)
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")

add_library(FastMedia SHARED FastMedia.cpp)

if (WIN32)
    target_link_libraries(FastMedia PRIVATE windowsapp ole32 oleaut32 winmm)
endif ()

if (MSVC)
    target_compile_options(FastMedia PRIVATE
        $<$<CONFIG:Release>:/O2 /Oi /Ot /GF /GL /Gy /Gw>)
    target_link_options(FastMedia PRIVATE
        $<$<CONFIG:Release>:/LTCG /OPT:REF /OPT:ICF>)
endif ()

add_custom_command(TARGET FastMedia POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory "${CMAKE_CURRENT_SOURCE_DIR}/../resource"
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
        $<TARGET_FILE:FastMedia>
        "${CMAKE_CURRENT_SOURCE_DIR}/../resource/FastMedia.dll")
