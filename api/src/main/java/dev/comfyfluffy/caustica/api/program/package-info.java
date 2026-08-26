/**
 * What an extension compiles into the world ray-tracing program: surface implementations, environments,
 * emission profiles, and projected surface modifiers. Implementations are retained objects added and
 * dropped through {@link dev.comfyfluffy.caustica.api.program.ProgramChannel}; a disabled feature is absent
 * from the program rather than compiled behind a runtime gate.
 */
package dev.comfyfluffy.caustica.api.program;
