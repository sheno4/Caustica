package dev.comfyfluffy.caustica.minecraft.api.program;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class MinecraftProgramTypesTest {
    @Test
    void tokensRetainNominalTypeIdentity() {
        var implementation = MinecraftProgramTypes.IMPLEMENTATION_DATA.data(17L);

        assertSame(MinecraftProgramTypes.IMPLEMENTATION_DATA, implementation.type());
        assertSame(implementation, MinecraftProgramTypes.IMPLEMENTATION_DATA.require(implementation));
        assertThrows(IllegalArgumentException.class,
                () -> MinecraftProgramTypes.PRIMITIVE_DATA.require(implementation));
    }
}
