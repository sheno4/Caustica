package dev.comfyfluffy.caustica.example.showcase;

import dev.comfyfluffy.caustica.api.geometry.GeometryChannel;
import dev.comfyfluffy.caustica.api.geometry.InstanceId;
import dev.comfyfluffy.caustica.api.geometry.MeshId;
import dev.comfyfluffy.caustica.api.light.LightChannel;
import dev.comfyfluffy.caustica.api.light.LightId;
import dev.comfyfluffy.caustica.api.pass.*;
import dev.comfyfluffy.caustica.api.program.*;
import dev.comfyfluffy.caustica.api.retained.RetainedBatch;
import dev.comfyfluffy.caustica.api.resource.ResourceChannel;
import dev.comfyfluffy.caustica.api.scene.EnvironmentBinding;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.api.session.RenderSessionContext;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorHeap;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.minecraft.api.*;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VkDevice;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ShowcaseSessionLifecycleTest {
    @Test
    void sameWorldSessionHandsSelectionsFromOneOwnerToAnotherThenDrainsBoth() {
        Programs programs = new Programs();
        Geometry geometry = new Geometry();
        Lights lights = new Lights();
        Passes passes = new Passes();
        AtomicReference<EnvironmentBinding<?>> selected = new AtomicReference<>();
        SceneId scene = new SceneId() { };
        RenderSessionContext selectionOwner = new RenderSessionContext() {
            @Override public GpuDevice gpu() { return GPU; }
            @Override public ProgramChannel program() { return programs; }
            @Override public PassChannel passes() { throw new AssertionError("selection owner has no passes"); }
            @Override public GeometryChannel geometry() {
                throw new AssertionError("selection owner has no geometry mutation authority");
            }
            @Override public LightChannel lights() { return lights; }
            @Override public ResourceChannel resources() { return null; }
        };
        RenderSessionContext geometryOwner = new RenderSessionContext() {
            @Override public GpuDevice gpu() { return GPU; }
            @Override public ProgramChannel program() {
                throw new AssertionError("geometry owner consumes handed-off program ids");
            }
            @Override public PassChannel passes() { return passes; }
            @Override public GeometryChannel geometry() { return geometry; }
            @Override public LightChannel lights() {
                throw new AssertionError("geometry owner cannot mutate handed-off lights");
            }
            @Override public ResourceChannel resources() { return null; }
        };
        MinecraftWorldSessionContext selectionWorld = world(selectionOwner, scene, selected);
        MinecraftWorldSessionContext geometryWorld = world(geometryOwner, scene, selected);
        ShowcaseHandoff handoff = new ShowcaseHandoff();

        ShowcaseSelectionContribution selectionContribution =
                new ShowcaseSelectionContribution(selectionWorld, handoff);
        ShowcaseSession session = new ShowcaseSession(
                geometryWorld, null, handoff.require(scene));
        programs.completeReady();

        assertSame(programs.exports.netherSky(), selected.get().implementation());
        assertEquals(11L, selected.get().bindingData().bits());
        assertEquals(3, lights.batches.getFirst().operations().size());

        selectionContribution.stop();
        session.stop();
        selectionContribution.close();
        session.close();

        assertThrows(IllegalStateException.class, () -> handoff.require(scene));
        assertEquals(3, passes.closed.get());
        assertEquals(1, geometry.batches.size());
        assertEquals(2, lights.batches.size());
        assertEquals(3, lights.batches.getLast().operations().size());
        assertEquals(1, programs.closed.get());
    }

    private static MinecraftWorldSessionContext world(RenderSessionContext renderer, SceneId scene,
                                                       AtomicReference<EnvironmentBinding<?>> selected) {
        return new MinecraftWorldSessionContext() {
            @Override public RenderSessionContext renderSession() { return renderer; }
            @Override public SceneId scene() { return scene; }
            @Override public MinecraftDimensionKey dimension() {
                return MinecraftDimensionKey.of("minecraft", "the_nether");
            }
            @Override public ResourcePackEpoch resourcePackEpoch() { return new ResourcePackEpoch(11L); }
            @Override public MinecraftEnvironmentSelector environment() { return selected::set; }
        };
    }

    private static final GpuDevice GPU = new GpuDevice() {
        @Override public VkDevice vk() { throw new AssertionError("test does not instantiate pass factories"); }
        @Override public long vmaAllocator() { throw new AssertionError(); }
        @Override public GpuDescriptorHeap descriptorHeap() { throw new AssertionError(); }
        @Override public void retireAfterUse(Runnable cleanup) { throw new AssertionError(); }
    };

    private static final class Passes implements PassChannel {
        private PassFactory<WorldResourceSetup, PassFrame> worldFactory;
        private final AtomicInteger closed = new AtomicInteger();

        private PassRegistration registration() { return closed::incrementAndGet; }
        @Override public PassRegistration addWorldResourcePass(PassFactory<WorldResourceSetup, PassFrame> factory) {
            worldFactory = factory;
            return registration();
        }
        @Override public PassRegistration addPostEffectPass(PassId id,
                PassFactory<PostEffectSetup, PostEffectFrame> factory) { return registration(); }
        @Override public PassRegistration addPostEffectPass(PassId id, PassPlacement placement,
                PassFactory<PostEffectSetup, PostEffectFrame> factory) { return registration(); }
        @Override public PassRegistration addUiPass(PassId id,
                PassFactory<UiSetup, UiFrame> factory) { return registration(); }
        @Override public PassRegistration addUiPass(PassId id, PassPlacement placement,
                PassFactory<UiSetup, UiFrame> factory) { return registration(); }
    }

    private static final class Geometry implements GeometryChannel {
        private final List<RetainedBatch<Operation>> batches = new ArrayList<>();
        @Override public <N> MeshId<N> newMesh(ShaderDataType<N> instanceDataType) {
            return new MeshId<>() { };
        }
        @Override public InstanceId newInstance() { return new InstanceId() { }; }
        @Override public dev.comfyfluffy.caustica.api.geometry.GeometryPublication submit(
                RetainedBatch<Operation> batch) {
            batches.add(batch);
            return dev.comfyfluffy.caustica.api.geometry.GeometryPublication.alreadyVisible();
        }
        @Override public dev.comfyfluffy.caustica.api.geometry.GeometryPublication submitGroup(
                List<RetainedBatch<Operation>> accepted) {
            batches.addAll(accepted);
            return dev.comfyfluffy.caustica.api.geometry.GeometryPublication.alreadyVisible();
        }
        @Override public dev.comfyfluffy.caustica.api.geometry.GeometryPublication submitWithLights(
                List<RetainedBatch<Operation>> geometryBatches, LightChannel lights,
                RetainedBatch<LightChannel.Operation> lightBatch) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class Lights implements LightChannel {
        private final List<RetainedBatch<Operation>> batches = new ArrayList<>();
        @Override public LightId newLight() { return new LightId() { }; }
        @Override public void submit(RetainedBatch<Operation> batch) { batches.add(batch); }
    }

    private static final class Programs implements ProgramChannel, ProgramBuilder {
        private final AtomicInteger closed = new AtomicInteger();
        private final List<Consumer<ProgramRegistration.Completion>> completions = new ArrayList<>();
        private ShowcasePrograms.Exports exports;

        @Override public <E> ProgramRegistration<E> register(
                Function<? super ProgramBuilder, ? extends E> declaration) {
            E value = declaration.apply(this);
            exports = (ShowcasePrograms.Exports) value;
            return new ProgramRegistration<>() {
                @Override public E exports() { return value; }
                @Override public void whenComplete(Consumer<? super Completion> callback) {
                    completions.add(callback::accept);
                }
                @Override public void close() { closed.incrementAndGet(); }
            };
        }

        void completeReady() {
            var ready = new ProgramRegistration.Ready();
            List.copyOf(completions).forEach(callback -> callback.accept(ready));
        }
        @Override public <B, N> SurfaceId<B, N> surface(SurfaceDefinition<B, N> definition) {
            return new SurfaceId<>() { };
        }
        @Override public <B, N> VolumeId<B, N> volume(VolumeDefinition<B, N> definition) {
            return new VolumeId<>() { };
        }
        @Override public <B> EnvironmentId<B> environment(EnvironmentDefinition<B> definition) {
            return new EnvironmentId<>() { };
        }
    }
}
