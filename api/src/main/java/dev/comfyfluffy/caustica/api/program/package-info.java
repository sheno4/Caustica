/**
 * What an extension compiles into the world ray-tracing program: surface implementations, environments,
 * emission profiles, projected surface modifiers, and anchored Slang modules.
 *
 * <p>These used to be declared once at startup and fixed for the process. They are retained objects now,
 * added and dropped through {@link dev.comfyfluffy.caustica.api.program.ProgramChannel} like anything else
 * — which is what makes a feature being switched off mean it is simply not in the program, with no gate to
 * consult and no way for a material to name something that was compiled but disabled.
 */
package dev.comfyfluffy.caustica.api.program;
