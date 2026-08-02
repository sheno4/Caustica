cmake_minimum_required(VERSION 3.20)
project(causticaslang LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(NOT CMAKE_CONFIGURATION_TYPES AND NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release CACHE STRING "Build type" FORCE)
  set_property(CACHE CMAKE_BUILD_TYPE PROPERTY STRINGS Debug Release RelWithDebInfo MinSizeRel)
endif()

if(NOT DEFINED ENV{VULKAN_SDK})
  message(FATAL_ERROR "VULKAN_SDK environment variable is not set")
endif()

get_filename_component(CAUSTICA_SLANG_REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../.." ABSOLUTE)
set(CAUSTICA_SLANG_OUTPUT_ROOT "${CAUSTICA_SLANG_REPO_ROOT}/build/native/slang_shim"
    CACHE PATH "Directory for built Slang shim artifacts")
set(CAUSTICA_SLANG_OUTPUT_DIR "${CAUSTICA_SLANG_OUTPUT_ROOT}/$<LOWER_CASE:$<CONFIG>>")
set(SLANG_SDK_ROOT "$ENV{VULKAN_SDK}")

add_library(causticaslang SHARED caustica_slang.cpp)
target_include_directories(causticaslang PRIVATE "${SLANG_SDK_ROOT}/include")

if(WIN32)
  target_link_libraries(causticaslang PRIVATE "${SLANG_SDK_ROOT}/Lib/slang.lib")
  set_property(TARGET causticaslang PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreadedDLL")
else()
  find_library(SLANG_LIBRARY NAMES slang PATHS "${SLANG_SDK_ROOT}/lib" NO_DEFAULT_PATH REQUIRED)
  target_link_libraries(causticaslang PRIVATE "${SLANG_LIBRARY}" ${CMAKE_DL_LIBS})
  set_target_properties(causticaslang PROPERTIES
      BUILD_RPATH "$ORIGIN"
      INSTALL_RPATH "$ORIGIN")
endif()

target_compile_definitions(causticaslang PRIVATE CAUSTICA_SLANG_BUILD)
set_target_properties(causticaslang PROPERTIES
    OUTPUT_NAME "causticaslang"
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN YES
    RUNTIME_OUTPUT_DIRECTORY "${CAUSTICA_SLANG_OUTPUT_DIR}"
    LIBRARY_OUTPUT_DIRECTORY "${CAUSTICA_SLANG_OUTPUT_DIR}"
    ARCHIVE_OUTPUT_DIRECTORY "${CAUSTICA_SLANG_OUTPUT_DIR}")
