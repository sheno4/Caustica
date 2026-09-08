cmake_minimum_required(VERSION 3.30)
project(nrd_dependencies LANGUAGES NONE)
include(FetchContent)

set(CAUSTICA_DEPENDENCY_ROOT "" CACHE PATH "Directory for pinned NRD build dependencies")

# Existing checkouts are read-only inputs. Revision changes require explicitly
# replacing them; preparation must not discard local SDK changes.
function(prepare_git name directory repository revision)
    set(source "${CAUSTICA_DEPENDENCY_ROOT}/${directory}")
    if(IS_DIRECTORY "${source}")
        string(TOUPPER "${name}" upper_name)
        set(FETCHCONTENT_SOURCE_DIR_${upper_name} "${source}")
    endif()
    FetchContent_Declare(${name}
        GIT_REPOSITORY "${repository}"
        GIT_TAG "${revision}"
        SOURCE_DIR "${source}"
        SOURCE_SUBDIR "dependency-sources-only")
    FetchContent_MakeAvailable(${name})
    execute_process(COMMAND git -C "${source}" rev-parse HEAD
        OUTPUT_VARIABLE actual OUTPUT_STRIP_TRAILING_WHITESPACE
        RESULT_VARIABLE result)
    if(NOT result EQUAL 0 OR NOT actual STREQUAL revision)
        message(FATAL_ERROR "${name} must be ${revision}; found '${actual}'")
    endif()
endfunction()

prepare_git(shadermake shadermake https://github.com/NVIDIA-RTX/ShaderMake.git "${CAUSTICA_SHADERMAKE_REVISION}")
prepare_git(mathlib mathlib https://github.com/NVIDIA-RTX/MathLib.git "${CAUSTICA_MATHLIB_REVISION}")
prepare_git(nri nri https://github.com/NVIDIA-RTX/NRI.git "${CAUSTICA_NRI_REVISION}")
prepare_git(vulkan_headers vulkan-headers https://github.com/KhronosGroup/Vulkan-Headers.git "${CAUSTICA_VULKAN_HEADERS_REVISION}")
prepare_git(vma vma https://github.com/GPUOpen-LibrariesAndSDKs/VulkanMemoryAllocator.git "${CAUSTICA_VMA_REVISION}")

if(WIN32)
    set(dxc_archive dxc_2025_05_24.zip)
    set(dxc_sha256 81380f3eca156d902d6404fd6df9f4b0886f576ff3e18b2cc10d3075ffc9d119)
else()
    set(dxc_archive linux_dxc_2025_05_24.x86_64.tar.gz)
    set(dxc_sha256 b99655f65215287825fcdd49102b17e2a1608eff79ffaf9457514c2676892aa5)
endif()
if(IS_DIRECTORY "${CAUSTICA_DEPENDENCY_ROOT}/dxc")
    set(FETCHCONTENT_SOURCE_DIR_DXC "${CAUSTICA_DEPENDENCY_ROOT}/dxc")
endif()
FetchContent_Declare(dxc
    URL "https://github.com/microsoft/DirectXShaderCompiler/releases/download/v1.8.2505/${dxc_archive}"
    URL_HASH "SHA256=${dxc_sha256}"
    SOURCE_DIR "${CAUSTICA_DEPENDENCY_ROOT}/dxc")
FetchContent_MakeAvailable(dxc)
