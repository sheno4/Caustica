#pragma once

#include <stddef.h>
#include <stdint.h>

#if defined(_WIN32)
#  if defined(CAUSTICA_SLANG_BUILD)
#    define CAUSTICA_SLANG_API __declspec(dllexport)
#  else
#    define CAUSTICA_SLANG_API __declspec(dllimport)
#  endif
#else
#  define CAUSTICA_SLANG_API __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

typedef struct CausticaSlangRuntime CausticaSlangRuntime;
typedef struct CausticaSlangSession CausticaSlangSession;
typedef struct CausticaSlangBlob CausticaSlangBlob;

enum
{
    CAUSTICA_SLANG_ABI_VERSION = 2,
    CAUSTICA_SLANG_SESSION_DEBUG_INFO = 1u << 0,
    CAUSTICA_SLANG_SESSION_WARNINGS_AS_ERRORS = 1u << 1,
};

CAUSTICA_SLANG_API uint32_t caustica_slang_abi_version(void);
CAUSTICA_SLANG_API const char* caustica_slang_compiler_version(void);

CAUSTICA_SLANG_API int32_t caustica_slang_runtime_create(
    CausticaSlangRuntime** out_runtime,
    CausticaSlangBlob** out_diagnostics);
CAUSTICA_SLANG_API void caustica_slang_runtime_destroy(CausticaSlangRuntime* runtime);

CAUSTICA_SLANG_API int32_t caustica_slang_session_create(
    CausticaSlangRuntime* runtime,
    const char* const* search_paths,
    size_t search_path_count,
    uint32_t flags,
    CausticaSlangSession** out_session,
    CausticaSlangBlob** out_diagnostics);
CAUSTICA_SLANG_API void caustica_slang_session_destroy(CausticaSlangSession* session);

CAUSTICA_SLANG_API int32_t caustica_slang_compile_entry_point(
    CausticaSlangSession* session,
    const char* module_name,
    const char* source_path,
    const char* source,
    const char* entry_point,
    CausticaSlangBlob** out_spirv,
    CausticaSlangBlob** out_reflection_json,
    CausticaSlangBlob** out_diagnostics);

CAUSTICA_SLANG_API int32_t caustica_slang_compile_specialized_entry_point(
    CausticaSlangSession* session,
    const char* engine_module,
    const char* entry_point,
    const char* pack_module,
    const char* pack_type,
    CausticaSlangBlob** out_spirv,
    CausticaSlangBlob** out_reflection_json,
    CausticaSlangBlob** out_diagnostics);

CAUSTICA_SLANG_API const void* caustica_slang_blob_data(const CausticaSlangBlob* blob);
CAUSTICA_SLANG_API size_t caustica_slang_blob_size(const CausticaSlangBlob* blob);
CAUSTICA_SLANG_API void caustica_slang_blob_destroy(CausticaSlangBlob* blob);

#ifdef __cplusplus
}
#endif
