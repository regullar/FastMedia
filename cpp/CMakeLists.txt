cmake_minimum_required(VERSION 4.1)
project(FastMedia)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Statically link the MSVC runtime so the DLL is self-contained: a Release
# build then loads on any machine without the VC++ Redistributable installed
# (the usual reason a Release DLL "only works in Debug" when loaded by the JVM).
cmake_policy(SET CMP0091 NEW)
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")

add_library(FastMedia SHARED
        FastMedia.cpp)

if (WIN32)
    # windowsapp     -> WinRT (SMTC)
    # winhttp        -> lyrics retrieval
    # ole32/oleaut32 -> COM + WASAPI loopback capture
    target_link_libraries(FastMedia PRIVATE windowsapp winhttp ole32 oleaut32)
endif ()

if (MSVC)
    # Aggressive size optimization flags for MSVC
    target_compile_options(FastMedia PRIVATE
        $<$<CONFIG:Release>:
            /O1        # Minimize size
            /Os        # Favor small code
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

# Find local UPX packer
set(UPX_EXE "${CMAKE_CURRENT_SOURCE_DIR}/upx.exe")

# Post-build: Compress with UPX (in Release) and deploy to Java resource directories
add_custom_command(TARGET FastMedia POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory "${CMAKE_CURRENT_SOURCE_DIR}/../resource"
    COMMAND ${CMAKE_COMMAND} -E make_directory "${CMAKE_CURRENT_SOURCE_DIR}/../resource/assets"
    COMMAND ${CMAKE_COMMAND} -E make_directory "${CMAKE_CURRENT_SOURCE_DIR}/../native"
    COMMAND "$<$<CONFIG:Release>:${UPX_EXE}>" "$<$<CONFIG:Release>:--best>" "$<$<CONFIG:Release>:-f>" "$<$<CONFIG:Release>:$<TARGET_FILE:FastMedia>>"
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
        $<TARGET_FILE:FastMedia>
        "${CMAKE_CURRENT_SOURCE_DIR}/../resource/FastMedia.dll"
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
        $<TARGET_FILE:FastMedia>
        "${CMAKE_CURRENT_SOURCE_DIR}/../resource/assets/FastMedia.dll"
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
        $<TARGET_FILE:FastMedia>
        "${CMAKE_CURRENT_SOURCE_DIR}/../native/FastMedia.dll"
    COMMENT "Optimizing, compressing with UPX, and deploying FastMedia.dll")


