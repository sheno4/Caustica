package dev.comfyfluffy.caustica.api.program;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ProgramContractTest {
    @Test
    void cancelledIsATerminalCompletionKind() {
        ProgramTicket.Completion completion = new ProgramTicket.Cancelled();

        assertInstanceOf(ProgramTicket.Cancelled.class, completion);
        assertSame(ProgramTicket.State.CANCELLED, ProgramTicket.State.valueOf("CANCELLED"));
    }

    @Test
    void readinessHasOnlyOwnerOutcomes() {
        assertEquals(Set.of("PENDING", "READY", "FAILED", "CANCELLED"),
                Arrays.stream(ProgramTicket.State.values()).map(Enum::name).collect(Collectors.toSet()));
        assertEquals(Set.of(ProgramTicket.Ready.class, ProgramTicket.Failed.class,
                        ProgramTicket.Cancelled.class),
                Set.of(ProgramTicket.Completion.class.getPermittedSubclasses()));
    }

    @Test
    void programCompositionIsOneAtomicTypedRegistration() throws NoSuchMethodException {
        Method register = ProgramChannel.class.getMethod("register", Function.class);
        assertSame(ProgramRegistration.class, register.getReturnType());
        assertEquals("dev.comfyfluffy.caustica.api.program.ProgramRegistration<E>",
                register.getGenericReturnType().getTypeName());
        assertSame(SurfaceId.class,
                ProgramBuilder.class.getMethod("surface", SurfaceDefinition.class).getReturnType());
        assertSame(VolumeId.class,
                ProgramBuilder.class.getMethod("volume", VolumeDefinition.class).getReturnType());
        assertSame(EnvironmentId.class,
                ProgramBuilder.class.getMethod("environment", EnvironmentDefinition.class).getReturnType());
        Method exports = ProgramRegistration.class.getMethod("exports");
        assertSame(Object.class, exports.getReturnType());
        assertEquals("E", exports.getGenericReturnType().getTypeName());
        assertSame(ProgramTicket.class, ProgramRegistration.class.getMethod("readiness").getReturnType());
        assertSame(void.class, ProgramRegistration.class.getMethod("close").getReturnType());

        Set<String> channelMethods = Arrays.stream(ProgramChannel.class.getMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());
        assertEquals(Set.of("register"), channelMethods);
        assertThrows(NoSuchMethodException.class,
                () -> ProgramBuilder.class.getMethod("surfaceModifier", ShaderDefinition.class));
        assertThrows(NoSuchMethodException.class,
                () -> ProgramBuilder.class.getMethod("environment", ShaderDefinition.class));
    }

    @Test
    void registrationAndReadinessExposeNoBlockingWait() {
        assertFalse(Arrays.stream(ProgramRegistration.class.getMethods())
                .map(Method::getName)
                .anyMatch(name -> name.startsWith("await") || name.startsWith("join")));
        assertFalse(Arrays.stream(ProgramTicket.class.getMethods())
                .map(Method::getName)
                .anyMatch(name -> name.startsWith("await") || name.startsWith("join")));
    }


    @Test
    void implementationDefinitionsOwnTheirRetirementCallbacks() throws NoSuchMethodException {
        assertSame(ShaderDefinition.class, SurfaceDefinition.class.getMethod("surface").getReturnType());
        assertSame(ShaderDefinition.class, SurfaceDefinition.class.getMethod("coverage").getReturnType());
        assertSame(ShaderData.class, SurfaceDefinition.class.getMethod("implementationData").getReturnType());
        assertSame(ShaderDataType.class, SurfaceDefinition.class.getMethod("bindingDataType").getReturnType());
        assertSame(ShaderDataType.class, SurfaceDefinition.class.getMethod("instanceDataType").getReturnType());
        assertSame(Runnable.class, SurfaceDefinition.class.getMethod("retired").getReturnType());
        assertSame(ShaderDefinition.class, VolumeDefinition.class.getMethod("implementation").getReturnType());
        assertSame(ShaderData.class, VolumeDefinition.class.getMethod("implementationData").getReturnType());
        assertSame(ShaderDataType.class, VolumeDefinition.class.getMethod("bindingDataType").getReturnType());
        assertSame(ShaderDataType.class, VolumeDefinition.class.getMethod("instanceDataType").getReturnType());
        assertSame(Runnable.class, VolumeDefinition.class.getMethod("retired").getReturnType());
    }

    @Test
    void implementationDefinitionsOfferNoopRetirementFactories() {
        ShaderSource source = ShaderSource.classpath(ProgramContractTest.class, "/caustica/shaders/api");
        ShaderDefinition surface = new ShaderDefinition(source, "caustica_surface", "ISurfaceModel");
        ShaderDefinition coverage = new ShaderDefinition(source, "caustica_coverage", "ICoverageModel");
        ShaderDefinition volume = new ShaderDefinition(source, "caustica_volume", "IVolumeModel");
        ShaderDataType<Object> implementationType = ShaderDataType.create("implementation");
        ShaderDataType<Object> bindingType = ShaderDataType.create("binding");
        ShaderDataType<Object> instanceType = ShaderDataType.create("instance");
        ShaderData<Object> implementationData = implementationType.data(7L);

        SurfaceDefinition.of(surface, coverage, implementationData, bindingType, instanceType)
                .retired().run();
        assertNull(SurfaceDefinition.opaque(surface, implementationData, bindingType, instanceType)
                .coverage());
        VolumeDefinition.of(volume, implementationData, bindingType, instanceType).retired().run();
    }

    @Test
    void shaderDataTypesAreIdentityTokensAndPreserveAllBits() {
        ShaderDataType<Object> first = ShaderDataType.create("same name");
        ShaderDataType<Object> second = ShaderDataType.create("same name");
        ShaderData<Object> data = first.data(-1L);

        assertSame(first, data.type());
        assertSame(data, first.require(data));
        assertThrows(IllegalArgumentException.class, () -> second.require(data));
        org.junit.jupiter.api.Assertions.assertNotSame(first, second);
        org.junit.jupiter.api.Assertions.assertEquals(-1L, data.bits());
    }
}
