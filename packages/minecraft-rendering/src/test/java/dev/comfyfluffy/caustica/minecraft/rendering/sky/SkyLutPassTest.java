package dev.comfyfluffy.caustica.minecraft.rendering.sky;

import dev.comfyfluffy.caustica.api.program.EnvironmentId;
import dev.comfyfluffy.caustica.api.resource.ResourceFactory;
import dev.comfyfluffy.caustica.api.resource.ResourceGeneration;
import dev.comfyfluffy.caustica.api.resource.ResourceRef;
import dev.comfyfluffy.caustica.api.retained.RetainedPublication;
import dev.comfyfluffy.caustica.api.scene.EnvironmentBinding;
import dev.comfyfluffy.caustica.minecraft.api.program.MinecraftProgramTypes;
import dev.comfyfluffy.caustica.minecraft.rendering.sky.gen.MinecraftEnvironmentBindingData;
import dev.comfyfluffy.caustica.minecraft.rendering.sky.gen.SkyInputsData;
import dev.comfyfluffy.caustica.minecraft.rendering.sky.gen.SkyLutPushData;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftCelestialFrame;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftLightingCalibration;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftDimensionKey;
import dev.comfyfluffy.caustica.settings.Option;
import dev.comfyfluffy.caustica.settings.OptionValues;
import dev.comfyfluffy.caustica.settings.ResourceId;
import dev.comfyfluffy.caustica.support.SharedResource;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK13;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SkyLutPassTest {
    private static final SkyLutPass.SkyState SKY = new SkyLutPass.SkyState(
            .1f, .2f, .3f, .4f, 100_000, .2f, .003f, 1.5f, .5f, .01f, .02f,
            .1f, .03f, .04f, 1.25f, 2, .3f, .05f);
    private static final SkyLutPass.AtlasSnapshot ATLAS = new SkyLutPass.AtlasSnapshot(null, 0, 1,
            new SkyInputsData.Float4(.1f, .2f, .3f, .4f),
            new SkyInputsData.Float4(.5f, .6f, .7f, .8f));

    @Test void generatedSkyInputsMapStateAndAtlasRects() {
        ByteBuffer bytes = ByteBuffer.allocate(SkyInputsData.BYTE_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        SkyLutPass.skyInputs(SKY, ATLAS).write(bytes);
        assertEquals(112, SkyInputsData.BYTE_SIZE);
        assertEquals(SKY.sunAngleRadians(), bytes.getFloat(0));
        assertEquals(SKY.groundAlbedo(), bytes.getFloat(64));
        assertEquals(.1f, bytes.getFloat(80));
        assertEquals(.8f, bytes.getFloat(108));
    }

    @Test void generatedRootsMatchDescriptorHeapAbi() {
        assertEquals(144, SkyLutPushData.BYTE_SIZE);
        assertEquals(32, MinecraftEnvironmentBindingData.BYTE_SIZE);
        assertEquals(1, SkyLutPass.groups(1));
        assertEquals(2, SkyLutPass.groups(9));
    }

    @Test void environmentBindingChangesOnlyWithAtlasIdentityOrEpoch() {
        assertTrue(SkyLutPass.sameBindingEpoch(7, 11, 7, 11));
        assertFalse(SkyLutPass.sameBindingEpoch(7, 11, 8, 11));
        assertFalse(SkyLutPass.sameBindingEpoch(7, 11, 7, 12));
    }

    @Test void persistentSkyResourcesWaitForPriorRayReadsBeforeComputeWrites() {
        assertEquals(KHRSynchronization2.VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR,
                SkyLutPass.PRIOR_SKY_READ_STAGE);
        assertEquals(VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT,
                SkyLutPass.PRIOR_SKY_READ_ACCESS);
        assertEquals(VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, SkyLutPass.SKY_WRITE_STAGE);
        assertEquals(VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT, SkyLutPass.SKY_WRITE_ACCESS);
    }

    @Test void selectedBindingLeaseDefersLutResourceClosure() {
        AtomicInteger closes = new AtomicInteger();
        var lifetime = SharedResource.owned(new Object(), ignored -> closes.incrementAndGet());
        var bindingLease = lifetime.retain();

        lifetime.close();
        assertEquals(0, closes.get());
        bindingLease.close();
        assertEquals(1, closes.get());
    }

    @Test void publishedBindingsCarryExactDistinctSealedGenerationReferences() {
        var factory = new TestResourceFactory();
        var environment = new EnvironmentId<MinecraftProgramTypes.EnvironmentBindingData>() { };
        List<EnvironmentBinding<?>> selected = new ArrayList<>();
        var first = factory.create();
        var second = factory.create();

        SkyLutPass.publishBinding(first, environment, binding -> {
            assertTrue(((TestGeneration) first).sealed);
            selected.add(binding);
            return RetainedPublication.alreadyVisible();
        }, 0x1000L);
        SkyLutPass.publishBinding(second, environment, binding -> {
            assertTrue(((TestGeneration) second).sealed);
            selected.add(binding);
            return RetainedPublication.alreadyVisible();
        }, 0x1000L);

        assertNotSame(ResourceRef.none(), first.reference());
        assertNotSame(first.reference(), second.reference());
        assertSame(first.reference(), selected.get(0).bindingData().resource());
        assertSame(second.reference(), selected.get(1).bindingData().resource());
    }

    @Test void producerHandlesWaitForScopeDropAndFrameReleaseBeforeRetiringResources() {
        AtomicInteger closes = new AtomicInteger();
        var lifetime = SharedResource.owned(new Object(), ignored -> closes.incrementAndGet());
        var factory = new TestResourceFactory();
        var first = (TestGeneration) factory.create(lifetime.retain()::close);
        var second = (TestGeneration) factory.create(lifetime.retain()::close);
        first.borrow();
        second.borrow();
        List<ResourceGeneration> handles = new ArrayList<>(List.of(first, second));

        SkyLutPass.dropAll(handles);
        SkyLutPass.dropAll(handles);
        lifetime.close();

        assertTrue(first.dropped);
        assertTrue(second.dropped);
        assertEquals(0, closes.get());
        first.releaseBorrow();
        assertEquals(0, closes.get());
        second.releaseBorrow();
        assertEquals(1, closes.get());
    }

    @Test void displacedGenerationsDropOnlyWhenReplacementBecomesVisible() {
        var factory = new TestResourceFactory();
        var first = (TestGeneration) factory.create();
        var second = (TestGeneration) factory.create();
        var third = (TestGeneration) factory.create();
        var firstVisible = new TestPublication();
        var secondVisible = new TestPublication();
        var thirdVisible = new TestPublication();
        List<ResourceGeneration> owned = new ArrayList<>();

        SkyLutPass.trackReplacementPublication(owned, first, firstVisible);
        SkyLutPass.trackReplacementPublication(owned, second, secondVisible);
        assertFalse(first.dropped);
        secondVisible.makeVisible();
        assertTrue(first.dropped);
        assertFalse(second.dropped);

        SkyLutPass.trackReplacementPublication(owned, third, thirdVisible);
        assertFalse(second.dropped);
        thirdVisible.makeVisible();
        assertTrue(second.dropped);
        assertFalse(third.dropped);

        SkyLutPass.dropAll(owned);
        SkyLutPass.dropAll(owned);
        assertTrue(third.dropped);
        firstVisible.makeVisible();
    }


    @Test void lutDimensionsMatchAtmosphereConstants() {
        assertEquals(256, SkyLutPass.TRANSMITTANCE_WIDTH);
        assertEquals(64, SkyLutPass.TRANSMITTANCE_HEIGHT);
        assertEquals(192, SkyLutPass.SKY_VIEW_WIDTH);
        assertEquals(216, SkyLutPass.SKY_VIEW_HEIGHT);
    }

    @Test void convertsSceneAltitudeToKilometresUsingFrameScale() {
        assertEquals(1.0f, SkyLutPass.viewerAltitudeKm(1063.0, 63.0, 1.0));
        assertEquals(1.0f, SkyLutPass.viewerAltitudeKm(2063.0, 63.0, 0.5));
        assertEquals(0.0f, SkyLutPass.viewerAltitudeKm(20.0, 63.0, 1.0));
    }

    @Test void dimensionCatalogSelectsOnlyTheBuiltInOverworldSky() {
        MinecraftSkyCatalog catalog = new MinecraftSkyCatalog();
        assertTrue(catalog.supports(new MinecraftDimensionKey(ResourceId.of("minecraft", "overworld"))));
        assertFalse(catalog.supports(new MinecraftDimensionKey(ResourceId.of("minecraft", "the_nether"))));
    }

    @Test void skyStateUsesOneCapturedHostFrame() {
        var lighting = new MinecraftLightingCalibration(100, 2, 3, 4, 5, .25f);
        var captured = new MinecraftCelestialFrame(.1f, .2f, .3f, .4f,
                6, 63, 1063, 1, lighting);
        OptionValues defaults = new OptionValues() {
            @Override public <T> T get(Option<T> option) { return option.defaultValue(); }
        };

        SkyLutPass.SkyState state = SkyLutPass.gather(defaults, captured);

        assertEquals(.1f, state.sunAngleRadians());
        assertEquals(.3f, state.starAngleRadians());
        assertEquals(1f, state.viewerAltitudeKm());
        assertEquals(6f, state.moonPhaseIndex());
    }

    private static final class TestResourceFactory implements ResourceFactory {
        @Override public ResourceGeneration create(Runnable retired) {
            return new TestGeneration(retired);
        }
    }

    private static final class TestGeneration implements ResourceGeneration {
        private final ResourceRef reference = new ResourceRef() { };
        private final Runnable retired;
        private int borrows;
        private boolean sealed, dropped, callbackRun;
        TestGeneration(Runnable retired) { this.retired = retired; }
        @Override public ResourceRef reference() { return reference; }
        @Override public void seal() { sealed = true; }
        @Override public void drop() {
            if (dropped) throw new AssertionError("generation dropped more than once");
            dropped = true;
            retireIfReady();
        }
        void borrow() { borrows++; }
        void releaseBorrow() { borrows--; retireIfReady(); }
        private void retireIfReady() {
            if (dropped && borrows == 0 && !callbackRun) {
                callbackRun = true;
                retired.run();
            }
        }
    }

    private static final class TestPublication implements RetainedPublication {
        private final List<Runnable> callbacks = new ArrayList<>();
        private boolean visible;
        @Override public boolean isVisible() { return visible; }
        @Override public void whenVisible(Runnable callback) {
            if (visible) callback.run();
            else callbacks.add(callback);
        }
        void makeVisible() {
            visible = true;
            List.copyOf(callbacks).forEach(Runnable::run);
            callbacks.clear();
        }
    }
}
