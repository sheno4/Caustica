# Caustica Slang tooling

`caustica-slang-tooling` is an included, independently publishable Gradle plugin build
(`dev.comfyfluffy.caustica:caustica-slang-tooling`). It is build infrastructure, not a runtime dependency
and not part of the public renderer API jar.

Apply `dev.comfyfluffy.caustica.slang-tooling` to obtain:

- `CompileSlangShaders`, a configurable source/include/alias-to-SPIR-V task;
- `ReflectSlang`, a generic one-probe task which publishes raw reflection JSON and validated SPIR-V;
- `GenerateShaderRecords`, a typed Java record generator whose tasks declare their buffer and
  push-constant records as `kind|probe|struct|package|class|reader` specifications;
- `GenerateRtBindings`, the typed Java binding generator used by Caustica's pipeline reflection probes;
- the `slangTooling` extension, which supplies `slangc`, `spirv-val`, SPIR-V profile, and Vulkan target
  conventions to every task above.

The defaults resolve `slangc` from `SLANG_SDK/bin` and `spirv-val` from `VULKAN_SDK/Bin`, falling back to
`PATH`. `slang_spirv_profile` and `slang_vulkan_target` Gradle properties override the defaults
`spirv_1_6` and `vulkan1.4`. Individual tasks or the extension may override every value.

Source checkouts make the plugin available from `settings.gradle`:

```groovy
pluginManagement {
    includeBuild("path/to/caustica/packages/slang-tooling")
}
```

Normal binary consumption uses the versioned Gradle plugin marker
`dev.comfyfluffy.caustica.slang-tooling:dev.comfyfluffy.caustica.slang-tooling.gradle.plugin` once that
marker is published to the consumer's configured plugin repository. The source-composite path does not imply
that the marker has been uploaded to a public repository.

`CompileSlangShaders.includeDirectories`, `ReflectSlang.includeDirectories`, and
`GenerateShaderRecords.includeDirectories` accept both directories and JAR artifacts. JAR inputs expose only
their `.slang` resources as temporary include roots, so a package can use the published
`caustica-shader-api` artifact without reaching into another project's source tree.

Each consuming project owns its concrete reflection probes, record specifications, and generated Java
output package. The plugin owns execution, validation, tool discovery, and task input/output contracts.
`packages/shader-api` applies the
same plugin independently and compiles a miniature public-ABI consumer during `check`, demonstrating that
the plugin does not depend on root-project build logic.
