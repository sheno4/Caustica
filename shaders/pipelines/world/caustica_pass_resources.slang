// Build-time-only stand-in for the pass-resource anchor module WorldShaderCompiler generates per
// composition at runtime (see WorldShaderCompiler.passResourceAnchorModule). Build-time reflection
// compiles primary_reflection.rgen.slang against the built-in surface and sky only, so no selected
// feature contributes a set-2 binding module here and the anchor is legitimately empty. It stays in the
// build-only `shaders/` tree so it can never shadow the generated module on the runtime search path.
module caustica_pass_resources;
