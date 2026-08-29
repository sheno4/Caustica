package dev.comfyfluffy.caustica.api.program;

import org.junit.jupiter.api.Test;

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
    void supersededIsATerminalCompletionKind() {
        ProgramTicket.Completion completion = new ProgramTicket.Superseded();

        assertInstanceOf(ProgramTicket.Superseded.class, completion);
        assertSame(ProgramTicket.State.SUPERSEDED, ProgramTicket.State.valueOf("SUPERSEDED"));
    }

    @Test
    void programCompositionExposesVolumesWithoutGlobalSurfaceModifiers() throws NoSuchMethodException {
        assertSame(ProgramUpdate.class,
                ProgramChannel.class.getMethod("addVolume", VolumeDefinition.class).getReturnType());
        assertSame(ProgramTicket.class,
                ProgramChannel.class.getMethod("dropVolume", VolumeId.class).getReturnType());
        assertSame(ProgramTicket.class,
                ProgramChannel.class.getMethod("dropSurface", SurfaceId.class).getReturnType());
        assertSame(ProgramTicket.class,
                ProgramChannel.class.getMethod("dropEnvironment", EnvironmentId.class).getReturnType());
        assertSame(ProgramUpdate.class,
                ProgramChannel.class.getMethod("addEnvironment", EnvironmentDefinition.class).getReturnType());
        assertThrows(NoSuchMethodException.class,
                () -> ProgramChannel.class.getMethod("dropVolume", VolumeId.class, Runnable.class));
        assertThrows(NoSuchMethodException.class,
                () -> ProgramChannel.class.getMethod("addSurfaceModifier", ShaderDefinition.class));
        assertThrows(NoSuchMethodException.class,
                () -> ProgramChannel.class.getMethod("addEnvironment", ShaderDefinition.class));
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
