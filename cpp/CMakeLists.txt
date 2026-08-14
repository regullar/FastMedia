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

# After every build (CLion Debug/Release included) drop the freshly built DLL
# into the Java resources so the library picks it up without a manual copy.
add_custom_command(TARGET FastMedia POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
        $<TARGET_FILE:FastMedia>
        "${CMAKE_CURRENT_SOURCE_DIR}/../java/resource/assets/FastMedia.dll"
    COMMENT "Deploying FastMedia.dll -> java/resource/assets")
