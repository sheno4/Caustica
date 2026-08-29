# Caustica Slang tooling

`caustica-slang-tooling` is an included, independently publishable Gradle plugin build
(`dev.comfyfluffy.caustica:caustica-slang-tooling`). It is build infrastructure, not a runtime dependency
and not part of the public renderer API jar.

Apply `dev.comfyfluffy.caustica.slang-tooling` to obtain:

- `CompileSlangShaders`, a configurable source/include/alias-to-SPIR-V task;
- `ReflectSlang`, a generic one-probe task which publishes raw reflection JSON and validated SPIR-V;
- `GenerateShaderRecords`, the typed Java record generator used by Caustica's reflected buffer ABI;
- `GenerateRtBindings`, the typed Java binding generator used by Caustica's pipeline reflection probes;
- the `slangTooling` extension, which supplies `slangc`, `spirv-val`, SPIR-V profile, and Vulkan target
  conventions to every task above.

The defaults resolve `slangc` from `SLANG_SDK/bin` and `spirv-val` from `VULKAN_SDK/Bin`, falling back to
`PATH`. `slang_spirv_profile` and `slang_vulkan_target` Gradle properties override the defaults
`spirv_1_6` and `vulkan1.4`. Individual tasks or the extension may override every value.

The root build owns the concrete reflection probes and generated Java output packages. The plugin owns
execution, validation, tool discovery, and task input/output contracts. `packages/shader-api` applies the
same plugin independently and compiles a miniature public-ABI consumer during `check`, demonstrating that
the plugin does not depend on root-project build logic.
