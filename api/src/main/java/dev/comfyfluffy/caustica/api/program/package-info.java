/**
 * Dynamically composed world-program implementations. Java definitions and the public
 * {@code caustica/shaders/api/caustica_*} Slang modules are one versioned ABI; neither may be upgraded
 * independently. Extension implementation types use extension-unique namespaces, and their qualified names
 * identify them within a composition. Opaque shader words use extension-defined
 * {@link dev.comfyfluffy.caustica.api.program.ShaderDataType} identities so Java bindings remain typed while
 * the Slang ABI stays {@code uint64_t}. See
 * {@link dev.comfyfluffy.caustica.api.program.ProgramAbi#VERSION}.
 */
package dev.comfyfluffy.caustica.api.program;
