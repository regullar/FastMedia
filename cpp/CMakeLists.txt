cmake_minimum_required(VERSION 3.20)
project(FastMedia)

set(CMAKE_CXX_STANDARD 20)  # C++/WinRT needs real coroutines; MSVC removed the C++17 /await shim
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Statically link the MSVC runtime so the DLL is self-contained: a Release
# build then loads on any machine without the VC++ Redistributable installed
cmake_policy(SET CMP0091 NEW)
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")

add_library(FastMedia SHARED
        FastMedia.cpp)

if (WIN32)
    # windowsapp     -> WinRT (SMTC)
    # ole32/oleaut32 -> COM + WASAPI loopback capture
    # winmm          -> high-resolution timer
    target_link_libraries(FastMedia PRIVATE windowsapp ole32 oleaut32 winmm)
endif ()

if (MSVC)
    # Maximize speed and performance for audio processing & WinRT interop
    target_compile_options(FastMedia PRIVATE
        $<$<CONFIG:Release>:
            /O2        # Maximize speed
            /Oi        # Enable intrinsic functions
            /Ot        # Favor fast code
            /GF        # Eliminate duplicate strings
            /GL        # Whole Program Optimization
            /Gy        # Function-level linking
            /Gw        # Optimize Global Data
        >
    )
    target_link_options(FastMedia PRIVATE
        $<$<CONFIG:Release>:
            /LTCG      # Link-Time Code Generation
            /OPT:REF   # Eliminate unreferenced functions and data
            /OPT:ICF   # Identical COMDAT folding
        >
    )
endif ()

# Deploy to Java resource directories and native/
add_custom_command(TARGET FastMedia POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory "${CMAKE_CURRENT_SOURCE_DIR}/../resource"
    COMMAND ${CMAKE_COMMAND} -E make_directory "${CMAKE_CURRENT_SOURCE_DIR}/../native"
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
        $<TARGET_FILE:FastMedia>
        "${CMAKE_CURRENT_SOURCE_DIR}/../resource/FastMedia.dll"
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
        $<TARGET_FILE:FastMedia>
        "${CMAKE_CURRENT_SOURCE_DIR}/../native/FastMedia.dll"
    COMMENT "Deploying optimized FastMedia.dll to resource and native directories")
