#include "caustica_slang.h"

#include <slang-com-ptr.h>
#include <slang.h>

#include <cstring>
#include <memory>
#include <mutex>
#include <new>
#include <string>
#include <unordered_set>
#include <vector>

struct CausticaSlangBlob
{
    std::vector<uint8_t> bytes;
};

struct CausticaSlangRuntime
{
    Slang::ComPtr<slang::IGlobalSession> global_session;
    // Sessions share global compiler state; serialize compilation and session creation here.
    std::mutex mutex;
};

struct CausticaSlangSession
{
    CausticaSlangRuntime* runtime = nullptr;
    Slang::ComPtr<slang::ISession> session;
};

namespace
{
CausticaSlangBlob* copy_bytes(const void* data, size_t size)
{
    auto result = std::make_unique<CausticaSlangBlob>();
    if (data && size)
    {
        const auto* first = static_cast<const uint8_t*>(data);
        result->bytes.assign(first, first + size);
    }
    return result.release();
}

CausticaSlangBlob* copy_string(const char* value)
{
    return value ? copy_bytes(value, std::strlen(value)) : nullptr;
}

CausticaSlangBlob* copy_slang_blob(slang::IBlob* blob)
{
    return blob ? copy_bytes(blob->getBufferPointer(), blob->getBufferSize()) : nullptr;
}

void append_diagnostics(std::string& destination, slang::IBlob* diagnostics)
{
    if (!diagnostics || diagnostics->getBufferSize() == 0)
        return;
    if (!destination.empty() && destination.back() != '\n')
        destination.push_back('\n');
    const auto* text = static_cast<const char*>(diagnostics->getBufferPointer());
    destination.append(text, diagnostics->getBufferSize());
}

void publish_diagnostics(const std::string& diagnostics, CausticaSlangBlob** output)
{
    if (output)
        *output = diagnostics.empty() ? nullptr : copy_bytes(diagnostics.data(), diagnostics.size());
}

void clear_outputs(
    CausticaSlangBlob** spirv,
    CausticaSlangBlob** reflection,
    CausticaSlangBlob** diagnostics)
{
    if (spirv)
        *spirv = nullptr;
    if (reflection)
        *reflection = nullptr;
    if (diagnostics)
        *diagnostics = nullptr;
}

slang::CompilerOptionEntry int_option(slang::CompilerOptionName name, int32_t value)
{
    slang::CompilerOptionEntry result{};
    result.name = name;
    result.value.kind = slang::CompilerOptionValueKind::Int;
    result.value.intValue0 = value;
    return result;
}

slang::CompilerOptionEntry string_option(slang::CompilerOptionName name, const char* value)
{
    slang::CompilerOptionEntry result{};
    result.name = name;
    result.value.kind = slang::CompilerOptionValueKind::String;
    result.value.stringValue0 = value;
    return result;
}

SlangResult emit_component(
    slang::IComponentType* component,
    std::string& diagnostics_text,
    CausticaSlangBlob** out_spirv,
    CausticaSlangBlob** out_reflection_json,
    CausticaSlangBlob** out_diagnostics)
{
    Slang::ComPtr<slang::IBlob> diagnostics;
    Slang::ComPtr<slang::IComponentType> linked;
    SlangResult result = component->link(linked.writeRef(), diagnostics.writeRef());
    append_diagnostics(diagnostics_text, diagnostics);
    if (SLANG_FAILED(result))
    {
        publish_diagnostics(diagnostics_text, out_diagnostics);
        return result;
    }

    Slang::ComPtr<slang::IBlob> code;
    diagnostics.setNull();
    result = linked->getEntryPointCode(0, 0, code.writeRef(), diagnostics.writeRef());
    append_diagnostics(diagnostics_text, diagnostics);
    if (SLANG_FAILED(result))
    {
        publish_diagnostics(diagnostics_text, out_diagnostics);
        return result;
    }
    *out_spirv = copy_slang_blob(code);

    if (out_reflection_json)
    {
        diagnostics.setNull();
        slang::ProgramLayout* layout = linked->getLayout(0, diagnostics.writeRef());
        append_diagnostics(diagnostics_text, diagnostics);
        if (!layout)
        {
            if (diagnostics_text.empty())
                diagnostics_text = "Could not obtain Slang program reflection";
            publish_diagnostics(diagnostics_text, out_diagnostics);
            return SLANG_FAIL;
        }
        Slang::ComPtr<slang::IBlob> reflection;
        result = layout->toJson(reflection.writeRef());
        if (SLANG_FAILED(result))
        {
            if (!diagnostics_text.empty() && diagnostics_text.back() != '\n')
                diagnostics_text.push_back('\n');
            diagnostics_text += "Could not serialize Slang program reflection";
            publish_diagnostics(diagnostics_text, out_diagnostics);
            return result;
        }
        *out_reflection_json = copy_slang_blob(reflection);
    }

    publish_diagnostics(diagnostics_text, out_diagnostics);
    return SLANG_OK;
}

SlangResult validate_implementation(
    slang::IEntryPoint* entry,
    slang::IComponentType* composite,
    slang::IModule* implementation,
    std::string& diagnostics_text)
{
    // The pinned SDK exposes transitive dependencies on entry points and composites through this
    // interface; querying an IModule directly returns an empty dependency list.
    Slang::ComPtr<slang::IModulePrecompileService_Experimental> engine_dependencies;
    Slang::ComPtr<slang::IModulePrecompileService_Experimental> dependencies;
    SLANG_RETURN_ON_FAIL(entry->queryInterface(SLANG_IID_PPV_ARGS(engine_dependencies.writeRef())));
    SLANG_RETURN_ON_FAIL(composite->queryInterface(SLANG_IID_PPV_ARGS(dependencies.writeRef())));

    // The input components retain these modules throughout validation.
    std::unordered_set<slang::IModule*> engine_modules;
    for (SlangInt index = 0; index < engine_dependencies->getModuleDependencyCount(); ++index)
    {
        Slang::ComPtr<slang::IModule> module;
        SLANG_RETURN_ON_FAIL(engine_dependencies->getModuleDependency(index, module.writeRef()));
        engine_modules.insert(module.get());
    }

    for (SlangInt index = 0; index < dependencies->getModuleDependencyCount(); ++index)
    {
        Slang::ComPtr<slang::IModule> module;
        SLANG_RETURN_ON_FAIL(dependencies->getModuleDependency(index, module.writeRef()));
        // Engine dependencies own resource declarations; the selected implementation is never exempt.
        if (module.get() != implementation && engine_modules.contains(module.get()))
            continue;
        if (module->getDefinedEntryPointCount() != 0)
        {
            diagnostics_text = std::string("Implementation module ") + module->getName()
                + " must not define shader entry points";
            return SLANG_FAIL;
        }
        Slang::ComPtr<slang::IBlob> diagnostics;
        slang::ProgramLayout* layout = module->getLayout(0, diagnostics.writeRef());
        append_diagnostics(diagnostics_text, diagnostics);
        if (!layout)
            return SLANG_FAIL;
        if (layout->getParameterCount() != 0)
        {
            diagnostics_text = std::string("Implementation module ") + module->getName()
                + " must not declare global shader parameters";
            return SLANG_FAIL;
        }
    }
    return SLANG_OK;
}
}

uint32_t caustica_slang_abi_version(void)
{
    return CAUSTICA_SLANG_ABI_VERSION;
}

const char* caustica_slang_compiler_version(void)
{
    return spGetBuildTagString();
}

int32_t caustica_slang_runtime_create(
    CausticaSlangRuntime** out_runtime,
    CausticaSlangBlob** out_diagnostics)
{
    if (out_runtime)
        *out_runtime = nullptr;
    if (out_diagnostics)
        *out_diagnostics = nullptr;
    if (!out_runtime)
        return SLANG_E_INVALID_ARG;

    try
    {
        auto runtime = std::make_unique<CausticaSlangRuntime>();
        const SlangResult result = slang_createGlobalSession(
            SLANG_API_VERSION,
            runtime->global_session.writeRef());
        if (SLANG_FAILED(result))
        {
            if (out_diagnostics)
            {
                const char* internal_error = slang_getLastInternalErrorMessage();
                *out_diagnostics = copy_string(
                    internal_error && *internal_error ? internal_error : "Could not create Slang global session");
            }
            return result;
        }
        *out_runtime = runtime.release();
        return SLANG_OK;
    }
    catch (const std::bad_alloc&)
    {
        if (out_diagnostics)
            *out_diagnostics = copy_string("Out of memory while creating Slang runtime");
        return SLANG_E_OUT_OF_MEMORY;
    }
    catch (...)
    {
        if (out_diagnostics)
            *out_diagnostics = copy_string("Unexpected exception while creating Slang runtime");
        return SLANG_FAIL;
    }
}

void caustica_slang_runtime_destroy(CausticaSlangRuntime* runtime)
{
    delete runtime;
}

int32_t caustica_slang_session_create(
    CausticaSlangRuntime* runtime,
    const char* const* search_paths,
    size_t search_path_count,
    uint32_t flags,
    CausticaSlangSession** out_session,
    CausticaSlangBlob** out_diagnostics)
{
    if (out_session)
        *out_session = nullptr;
    if (out_diagnostics)
        *out_diagnostics = nullptr;
    if (!runtime || !out_session || (search_path_count && !search_paths))
        return SLANG_E_INVALID_ARG;

    try
    {
        std::vector<slang::CompilerOptionEntry> options;
        options.push_back(int_option(slang::CompilerOptionName::EmitSpirvDirectly, 1));
        options.push_back(int_option(slang::CompilerOptionName::DiagnosticColor, SLANG_DIAGNOSTIC_COLOR_NEVER));
        options.push_back(string_option(slang::CompilerOptionName::DisableWarnings, "41012"));
        const SlangCapabilityID descriptor_heap =
            runtime->global_session->findCapability("spvDescriptorHeapEXT");
        if (descriptor_heap == SLANG_CAPABILITY_UNKNOWN)
        {
            if (out_diagnostics)
                *out_diagnostics = copy_string("Slang does not expose spvDescriptorHeapEXT");
            return SLANG_FAIL;
        }
        options.push_back(int_option(
            slang::CompilerOptionName::Capability,
            static_cast<int32_t>(descriptor_heap)));
        options.push_back(int_option(
            slang::CompilerOptionName::SPIRVUnifiedDescriptorHeapStride,
            1));
        if ((flags & CAUSTICA_SLANG_SESSION_DEBUG_INFO) != 0)
            options.push_back(int_option(slang::CompilerOptionName::DebugInformation, SLANG_DEBUG_INFO_LEVEL_STANDARD));
        if ((flags & CAUSTICA_SLANG_SESSION_WARNINGS_AS_ERRORS) != 0)
            options.push_back(string_option(slang::CompilerOptionName::WarningsAsErrors, "all"));

        slang::TargetDesc target{};
        target.format = SLANG_SPIRV;
        target.profile = runtime->global_session->findProfile("spirv_1_6");
        target.compilerOptionEntries = options.data();
        target.compilerOptionEntryCount = static_cast<uint32_t>(options.size());

        slang::SessionDesc description{};
        description.targets = &target;
        description.targetCount = 1;
        description.defaultMatrixLayoutMode = SLANG_MATRIX_LAYOUT_COLUMN_MAJOR;
        description.searchPaths = search_paths;
        description.searchPathCount = static_cast<SlangInt>(search_path_count);
        description.skipSPIRVValidation = false;

        auto session = std::make_unique<CausticaSlangSession>();
        session->runtime = runtime;
        SlangResult result;
        {
            std::lock_guard lock(runtime->mutex);
            result = runtime->global_session->createSession(description, session->session.writeRef());
        }
        if (SLANG_FAILED(result))
        {
            if (out_diagnostics)
                *out_diagnostics = copy_string("Could not create Slang compilation session");
            return result;
        }
        *out_session = session.release();
        return SLANG_OK;
    }
    catch (const std::bad_alloc&)
    {
        if (out_diagnostics)
            *out_diagnostics = copy_string("Out of memory while creating Slang compilation session");
        return SLANG_E_OUT_OF_MEMORY;
    }
    catch (...)
    {
        if (out_diagnostics)
            *out_diagnostics = copy_string("Unexpected exception while creating Slang compilation session");
        return SLANG_FAIL;
    }
}

void caustica_slang_session_destroy(CausticaSlangSession* session)
{
    delete session;
}

int32_t caustica_slang_compile_entry_point(
    CausticaSlangSession* session,
    const char* module_name,
    const char* source_path,
    const char* source,
    const char* entry_point,
    CausticaSlangBlob** out_spirv,
    CausticaSlangBlob** out_reflection_json,
    CausticaSlangBlob** out_diagnostics)
{
    clear_outputs(out_spirv, out_reflection_json, out_diagnostics);
    if (!session || !module_name || !source_path || !source || !entry_point || !out_spirv)
        return SLANG_E_INVALID_ARG;

    try
    {
        std::lock_guard lock(session->runtime->mutex);
        std::string diagnostics_text;
        Slang::ComPtr<slang::IBlob> diagnostics;

        // The session owns loaded modules and returns borrowed pointers without adding a reference.
        slang::IModule* module = session->session->loadModuleFromSourceString(
            module_name,
            source_path,
            source,
            diagnostics.writeRef());
        append_diagnostics(diagnostics_text, diagnostics);
        if (!module)
        {
            publish_diagnostics(diagnostics_text, out_diagnostics);
            return SLANG_FAIL;
        }

        Slang::ComPtr<slang::IEntryPoint> entry;
        SlangResult result = module->findEntryPointByName(entry_point, entry.writeRef());
        if (SLANG_FAILED(result))
        {
            if (diagnostics_text.empty())
                diagnostics_text = std::string("Entry point not found: ") + entry_point;
            publish_diagnostics(diagnostics_text, out_diagnostics);
            return result;
        }

        slang::IComponentType* components[] = {module, entry.get()};
        Slang::ComPtr<slang::IComponentType> composite;
        diagnostics.setNull();
        result = session->session->createCompositeComponentType(
            components,
            2,
            composite.writeRef(),
            diagnostics.writeRef());
        append_diagnostics(diagnostics_text, diagnostics);
        if (SLANG_FAILED(result))
        {
            publish_diagnostics(diagnostics_text, out_diagnostics);
            return result;
        }

        return emit_component(composite, diagnostics_text, out_spirv,
            out_reflection_json, out_diagnostics);
    }
    catch (const std::bad_alloc&)
    {
        if (out_diagnostics)
            *out_diagnostics = copy_string("Out of memory while compiling Slang entry point");
        return SLANG_E_OUT_OF_MEMORY;
    }
    catch (...)
    {
        if (out_diagnostics)
            *out_diagnostics = copy_string("Unexpected exception while compiling Slang entry point");
        return SLANG_FAIL;
    }
}

int32_t caustica_slang_compile_specialized_entry_point(
    CausticaSlangSession* session,
    const char* engine_module,
    const char* entry_point,
    const char* implementation_module,
    const char* implementation_type,
    CausticaSlangBlob** out_spirv,
    CausticaSlangBlob** out_reflection_json,
    CausticaSlangBlob** out_diagnostics)
{
    clear_outputs(out_spirv, out_reflection_json, out_diagnostics);
    if (!session || !engine_module || !entry_point || !implementation_module
        || !implementation_type || !out_spirv)
        return SLANG_E_INVALID_ARG;

    try
    {
        std::lock_guard lock(session->runtime->mutex);
        std::string diagnostics_text;
        Slang::ComPtr<slang::IBlob> diagnostics;

        // Both modules are borrowed from the session's module cache.
        slang::IModule* engine = session->session->loadModule(engine_module, diagnostics.writeRef());
        append_diagnostics(diagnostics_text, diagnostics);
        if (!engine)
        {
            publish_diagnostics(diagnostics_text, out_diagnostics);
            return SLANG_FAIL;
        }

        diagnostics.setNull();
        slang::IModule* implementation = session->session->loadModule(implementation_module,
            diagnostics.writeRef());
        append_diagnostics(diagnostics_text, diagnostics);
        if (!implementation)
        {
            publish_diagnostics(diagnostics_text, out_diagnostics);
            return SLANG_FAIL;
        }

        Slang::ComPtr<slang::IEntryPoint> entry;
        SlangResult result = engine->findEntryPointByName(entry_point, entry.writeRef());
        if (SLANG_FAILED(result))
        {
            if (diagnostics_text.empty())
                diagnostics_text = std::string("Engine entry point not found: ") + entry_point;
            publish_diagnostics(diagnostics_text, out_diagnostics);
            return result;
        }

        diagnostics.setNull();
        slang::ProgramLayout* implementation_layout = implementation->getLayout(0, diagnostics.writeRef());
        append_diagnostics(diagnostics_text, diagnostics);
        if (!implementation_layout)
        {
            if (diagnostics_text.empty())
                diagnostics_text = std::string("Could not reflect implementation module: ")
                    + implementation_module;
            publish_diagnostics(diagnostics_text, out_diagnostics);
            return SLANG_FAIL;
        }
        slang::TypeReflection* type = implementation_layout->findTypeByName(implementation_type);
        if (!type)
        {
            diagnostics_text = std::string("Implementation type not found: ") + implementation_type;
            publish_diagnostics(diagnostics_text, out_diagnostics);
            return SLANG_FAIL;
        }

        const slang::SpecializationArg argument = slang::SpecializationArg::fromType(type);
        Slang::ComPtr<slang::IComponentType> specialized_entry;
        diagnostics.setNull();
        result = entry->specialize(&argument, 1, specialized_entry.writeRef(), diagnostics.writeRef());
        append_diagnostics(diagnostics_text, diagnostics);
        if (SLANG_FAILED(result))
        {
            publish_diagnostics(diagnostics_text, out_diagnostics);
            return result;
        }

        slang::IComponentType* components[] = {engine, implementation, specialized_entry.get()};
        Slang::ComPtr<slang::IComponentType> composite;
        diagnostics.setNull();
        result = session->session->createCompositeComponentType(
            components,
            3,
            composite.writeRef(),
            diagnostics.writeRef());
        append_diagnostics(diagnostics_text, diagnostics);
        if (SLANG_FAILED(result))
        {
            publish_diagnostics(diagnostics_text, out_diagnostics);
            return result;
        }

        result = validate_implementation(entry, composite, implementation, diagnostics_text);
        if (SLANG_FAILED(result))
        {
            if (diagnostics_text.empty())
                diagnostics_text = "Could not inspect implementation module dependencies";
            publish_diagnostics(diagnostics_text, out_diagnostics);
            return result;
        }

        return emit_component(composite, diagnostics_text, out_spirv,
            out_reflection_json, out_diagnostics);
    }
    catch (const std::bad_alloc&)
    {
        if (out_diagnostics)
            *out_diagnostics = copy_string("Out of memory while compiling specialized Slang entry point");
        return SLANG_E_OUT_OF_MEMORY;
    }
    catch (...)
    {
        if (out_diagnostics)
            *out_diagnostics = copy_string("Unexpected exception while compiling specialized Slang entry point");
        return SLANG_FAIL;
    }
}

const void* caustica_slang_blob_data(const CausticaSlangBlob* blob)
{
    return blob && !blob->bytes.empty() ? blob->bytes.data() : nullptr;
}

size_t caustica_slang_blob_size(const CausticaSlangBlob* blob)
{
    return blob ? blob->bytes.size() : 0;
}

void caustica_slang_blob_destroy(CausticaSlangBlob* blob)
{
    delete blob;
}
