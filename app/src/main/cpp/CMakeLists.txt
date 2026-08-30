cmake_minimum_required(VERSION 3.4.1)
project(zomdroid)
cmake_policy(SET CMP0079 NEW)
set(CMAKE_C_STANDARD 11)

#add_subdirectory(gl4es)
#set_target_properties(GL PROPERTIES
#        LIBRARY_OUTPUT_DIRECTORY ${CMAKE_LIBRARY_OUTPUT_DIRECTORY} # originally ${CMAKE_SOURCE_DIR}/lib
#        SUFFIX .so # originally so.1
#        #OUTPUT_NAME gl4es
#)
##target_compile_definitions(GL PRIVATE DEBUG)
#target_compile_definitions(GL PRIVATE NOX11)
#target_compile_definitions(GL PRIVATE NO_GBM)
#target_compile_definitions(GL PRIVATE NOEGL) # EGL context creation is handled by glfw

set(GLFW_BUILD_WAYLAND OFF CACHE BOOL "Disable Wayland support" FORCE)
set(GLFW_BUILD_X11 OFF CACHE BOOL "Disable X11 support" FORCE)
set(BUILD_SHARED_LIBS ON CACHE BOOL "Build shared libs" FORCE)
set(GLFW_BUILD_DOCS OFF CACHE BOOL "Build docs" FORCE)
set(GLFW_INSTALL OFF CACHE BOOL "Install" FORCE)
set(GLFW_BUILD_ZOMDROID ON)
add_subdirectory(glfw)
set_target_properties(glfw PROPERTIES
        LIBRARY_OUTPUT_DIRECTORY ${CMAKE_LIBRARY_OUTPUT_DIRECTORY} # originally ${CMAKE_SOURCE_DIR}/lib
)
target_include_directories(glfw PRIVATE .)
target_link_libraries(glfw PRIVATE log zomdroid EGL nativewindow)

add_subdirectory(liblinkernsbypass)

add_library(zomdroid SHARED zomdroid.c zomdroid_jni.c)
target_link_libraries(zomdroid log android linkernsbypass)

add_library(zomdroidlinker SHARED linker.c emulation.c wrapped_jni.c)
target_link_libraries(zomdroidlinker PRIVATE c log box64 zomdroid) # link to libc before box64 to prevent mmap override
target_link_options(zomdroidlinker PRIVATE "-Wl,-z,global")

# AB3-compatible ARM64 Lighting path restored in OPT-LAB R2.  The shim has a
# distinct SONAME because Java installs it as libLighting64.so while it dlopens
# libLightingLegacy64.so, whose embedded SONAME is libLighting64.so.
add_library(lighting_shim SHARED lighting_shim.c)
set_target_properties(lighting_shim PROPERTIES OUTPUT_NAME LightingShim64)
target_link_libraries(lighting_shim PRIVATE dl log)
target_compile_options(lighting_shim PRIVATE -Wall -Wextra -Werror)
target_link_options(lighting_shim PRIVATE "-Wl,-soname,libLightingShim64.so")

# Build 42.20/42.20.3 ship the same official ARM64 PopMan payload, but omit the
# n_saveCell JNI wrapper while retaining all underlying ManagerWorker, ManagerMain,
# Cell, ArrayList and ObjectPool code. This exact-build bridge reconstructs the
# complete saveCell lifecycle; payload build-id and launcher identity gates prevent
# it from running with any other binary.
add_library(popman_savecell_bridge SHARED popman_savecell_bridge.c)
set_target_properties(popman_savecell_bridge PROPERTIES OUTPUT_NAME PZPopManSaveCellBridge)
target_link_libraries(popman_savecell_bridge PRIVATE dl log)
target_compile_options(popman_savecell_bridge PRIVATE -Wall -Wextra -Werror)
target_link_options(popman_savecell_bridge PRIVATE
        "-Wl,-soname,libPZPopManSaveCellBridge.so")

set(NOGIT ON)
set(ARM_DYNAREC ON)
add_subdirectory(box64)
target_compile_definitions(box64 PRIVATE BUILD_DYNAMIC)
