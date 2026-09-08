cmake_minimum_required(VERSION 3.30)
project(nrdshim LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(NRD_SDK "" CACHE PATH "Pinned NVIDIA NRD subrepository checkout")
set(CAUSTICA_NRD_REVISION "" CACHE STRING "Expected NVIDIA NRD source revision")
set(CAUSTICA_NRI_REVISION "" CACHE STRING "Expected NVIDIA NRI source revision")
set(CAUSTICA_SHADERMAKE_REVISION "" CACHE STRING "Expected ShaderMake source revision")
set(CAUSTICA_MATHLIB_REVISION "" CACHE STRING "Expected MathLib source revision")
set(CAUSTICA_VULKAN_HEADERS_REVISION "" CACHE STRING "Expected Vulkan-Headers source revision")
set(CAUSTICA_VMA_REVISION "" CACHE STRING "Expected Vulkan Memory Allocator source revision")
if(NOT IS_DIRECTORY "${NRD_SDK}")
    message(FATAL_ERROR "NRD_SDK must name the initialized NVIDIA NRD subrepository")
endif()

function(require_git_revision source revision name)
    execute_process(COMMAND git -C "${source}" rev-parse HEAD
        OUTPUT_VARIABLE actual OUTPUT_STRIP_TRAILING_WHITESPACE
        RESULT_VARIABLE result)
    if(NOT result EQUAL 0 OR NOT actual STREQUAL revision)
        message(FATAL_ERROR "${name} source must be ${revision}; found '${actual}'")
    endif()
endfunction()

file(READ "${CMAKE_CURRENT_SOURCE_DIR}/NRD_REVISION" PINNED_NRD_REVISION)
string(STRIP "${PINNED_NRD_REVISION}" PINNED_NRD_REVISION)
if(NOT CAUSTICA_NRD_REVISION STREQUAL PINNED_NRD_REVISION)
    message(FATAL_ERROR "CAUSTICA_NRD_REVISION must equal ${PINNED_NRD_REVISION}")
endif()
require_git_revision("${NRD_SDK}" "${PINNED_NRD_REVISION}" "NRD")

file(READ "${CMAKE_CURRENT_SOURCE_DIR}/NRI_REVISION" PINNED_NRI_REVISION)
string(STRIP "${PINNED_NRI_REVISION}" PINNED_NRI_REVISION)
if(NOT CAUSTICA_NRI_REVISION STREQUAL PINNED_NRI_REVISION)
    message(FATAL_ERROR "CAUSTICA_NRI_REVISION must equal ${PINNED_NRI_REVISION}")
endif()
require_git_revision("${FETCHCONTENT_SOURCE_DIR_NRI}" "${PINNED_NRI_REVISION}" "NRI")
require_git_revision("${FETCHCONTENT_SOURCE_DIR_SHADERMAKE}" "${CAUSTICA_SHADERMAKE_REVISION}" "ShaderMake")
require_git_revision("${FETCHCONTENT_SOURCE_DIR_MATHLIB}" "${CAUSTICA_MATHLIB_REVISION}" "MathLib")
require_git_revision("${FETCHCONTENT_SOURCE_DIR_VULKAN_HEADERS}" "${CAUSTICA_VULKAN_HEADERS_REVISION}" "Vulkan-Headers")
require_git_revision("${FETCHCONTENT_SOURCE_DIR_VMA}" "${CAUSTICA_VMA_REVISION}" "Vulkan Memory Allocator")

function(require_file_sha256 source expected)
    file(SHA256 "${source}" actual)
    if(NOT actual STREQUAL expected)
        message(FATAL_ERROR "DXC v1.8.2505 file mismatch: ${source}")
    endif()
endfunction()

# The official Windows and Linux binaries report different version formats.
if(WIN32)
    require_file_sha256("${SHADERMAKE_DXC_VK_PATH}" "52a3aa8150bdd49071fef302f6556c99a697f9a85770940c7edd92f8c7fd7e1f")
    require_file_sha256("${FETCHCONTENT_SOURCE_DIR_DXC}/bin/x64/dxcompiler.dll" "75c019e3541980eea8ae661d81998d06f99627e3004c800532475a1c3551c6fb")
else()
    require_file_sha256("${SHADERMAKE_DXC_VK_PATH}" "25927f0c2eb2eb22ebdf72ecf4798345a53b997ef1b05fea0280410d220427bb")
    require_file_sha256("${FETCHCONTENT_SOURCE_DIR_DXC}/lib/libdxcompiler.so" "19d03419d8cd45cd3c15bf23b0e1bc5346095106187c2f3f75d8f5aab40e0fc7")
endif()
execute_process(COMMAND "${SHADERMAKE_DXC_VK_PATH}" --version
    OUTPUT_VARIABLE DXC_VERSION OUTPUT_STRIP_TRAILING_WHITESPACE
    RESULT_VARIABLE DXC_RESULT)
if(NOT DXC_RESULT EQUAL 0)
    message(FATAL_ERROR "Pinned DXC cannot run: '${DXC_VERSION}'")
endif()

# NRD and NRI remain static implementation details. The build has no network fallback.
set(NRD_EMBEDS_DXBC_SHADERS OFF CACHE BOOL "" FORCE)
set(NRD_EMBEDS_DXIL_SHADERS OFF CACHE BOOL "" FORCE)
set(NRD_EMBEDS_SPIRV_SHADERS ON CACHE BOOL "" FORCE)
set(NRD_NRI ON CACHE BOOL "" FORCE)
set(NRD_STATIC_LIBRARY ON CACHE BOOL "" FORCE)
set(NRD_NORMAL_ENCODING 4 CACHE STRING "" FORCE)
set(NRD_ROUGHNESS_ENCODING 1 CACHE STRING "" FORCE)
set(NRI_STATIC_LIBRARY ON CACHE BOOL "" FORCE)
set(NRI_ENABLE_D3D11_SUPPORT OFF CACHE BOOL "" FORCE)
set(NRI_ENABLE_D3D12_SUPPORT OFF CACHE BOOL "" FORCE)
set(NRI_ENABLE_VK_SUPPORT ON CACHE BOOL "" FORCE)
set(NRI_ENABLE_VALIDATION_SUPPORT OFF CACHE BOOL "" FORCE)
add_subdirectory("${NRD_SDK}" "${CMAKE_CURRENT_BINARY_DIR}/nrd" EXCLUDE_FROM_ALL)

# NRD and NRI both expose a global StdAllocator template with incompatible
# storage. Keep NRI's private symbols distinct so static linking cannot merge
# vector methods compiled for NRD's allocator layout.
target_compile_definitions(NRI PRIVATE StdAllocator=NriStdAllocator)
target_compile_definitions(NRI_Shared PRIVATE StdAllocator=NriStdAllocator)
target_compile_definitions(NRI_VK PRIVATE StdAllocator=NriStdAllocator)

add_library(nrdshim SHARED nrd_shim.cpp)
target_include_directories(nrdshim PRIVATE
    "${NRD_SDK}/Include"
    "${NRD_SDK}/Integration"
    "${FETCHCONTENT_SOURCE_DIR_NRI}/Include")
target_link_libraries(nrdshim PRIVATE NRD NRDIntegration NRI)
target_compile_definitions(nrdshim PRIVATE NRD_STATIC_LIBRARY)
set_target_properties(nrdshim PROPERTIES OUTPUT_NAME "nrdshim")
if(DEFINED CAUSTICA_NRD_OUTPUT_ROOT)
    set_target_properties(nrdshim PROPERTIES
        RUNTIME_OUTPUT_DIRECTORY "${CAUSTICA_NRD_OUTPUT_ROOT}/$<LOWER_CASE:$<CONFIG>>"
        LIBRARY_OUTPUT_DIRECTORY "${CAUSTICA_NRD_OUTPUT_ROOT}/$<LOWER_CASE:$<CONFIG>>"
        ARCHIVE_OUTPUT_DIRECTORY "${CAUSTICA_NRD_OUTPUT_ROOT}/$<LOWER_CASE:$<CONFIG>>")
endif()

if(MSVC)
    set_property(TARGET nrdshim PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreadedDLL")
    target_compile_options(nrdshim PRIVATE /W4 /WX)
else()
    target_compile_options(nrdshim PRIVATE -Wall -Wextra -Werror)
endif()
