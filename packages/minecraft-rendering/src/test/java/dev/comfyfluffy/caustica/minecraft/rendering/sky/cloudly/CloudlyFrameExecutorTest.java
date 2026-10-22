package dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly;

import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorIndex;
import dev.comfyfluffy.caustica.minecraft.rendering.TestResource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ReadOnlyBufferException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class CloudlyFrameExecutorTest {
    @TempDir Path directory;

    @Test void frozenInputsAndIndirectOwnershipSurviveProducerChangesAndClose() throws Exception {
        var schedule = fixture(true);
        AtomicInteger retired = new AtomicInteger();
        ResourceOwner producer = TestResource.create(retired::incrementAndGet);
        ByteBuffer constants = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        constants.putInt(0, -1).putInt(4, 17).putInt(8, 23).position(4);
        var bindings = bindings();
        var push = new LinkedHashMap<>(Map.of("channelIndex", 7L));
        try (var plan = CloudlyFrameExecutor.preflight(schedule, schedule.resolve(Map.of()),
                Set.of("Indirect", "Trace", "Prepare"), Map.of(), inputs(constants, bindings, push, List.of(producer), producer, 60))) {
            constants.putInt(8, 999);
            bindings.put("Image", new GpuDescriptorIndex.Resource(777));
            push.put("channelIndex", 9L);
            producer.close();
            assertEquals(0, retired.get());
            assertEquals(List.of("Prepare", "Trace", "Indirect"), plan.steps().stream().map(CloudlyFrameExecutor.Step::name).toList());
            assertEquals(Set.of("fixture"), plan.programIds());
            var trace = (CloudlyFrameExecutor.ComputeStep) plan.steps().get(1);
            assertEquals(99, trace.constants().getInt(0));
            assertEquals(23, trace.constants().getInt(4));
            assertEquals(4, constants.position());
            assertEquals(11, trace.descriptors().get("image").value());
            assertEquals(7L, trace.sourcePush().get("channelIndex"));
            assertThrows(ReadOnlyBufferException.class, () -> trace.constants().putInt(0, 5));
            trace.constants().position(4);
            assertEquals(0, trace.constants().position());
            assertThrows(UnsupportedOperationException.class, () -> trace.descriptors().clear());
            assertThrows(UnsupportedOperationException.class, () -> plan.steps().clear());
            var indirect = (CloudlyFrameExecutor.ComputeStep) plan.steps().getLast();
            assertNotSame(producer, indirect.indirectBuffer().owner());
            try (var reader = indirect.indirectBuffer().owner().retain()) {
                assertEquals(0, retired.get());
            }
        }
        assertEquals(1, retired.get());
    }

    @Test void aRetainFailureRollsBackEveryEarlierPlanClaim() throws Exception {
        var schedule = fixture(true);
        AtomicInteger goodRetired = new AtomicInteger();
        AtomicInteger unavailableRetired = new AtomicInteger();
        var good = TestResource.create(goodRetired::incrementAndGet);
        var unavailable = TestResource.create(unavailableRetired::incrementAndGet);
        unavailable.close();
        assertThrows(IllegalStateException.class, () -> CloudlyFrameExecutor.preflight(schedule, schedule.resolve(Map.of()),
                Set.of("Prepare", "Trace"), Map.of(), inputs(constants(), bindings(), Map.of("channelIndex", 0L),
                        List.of(good, unavailable), good, 60)));
        assertEquals(0, goodRetired.get());
        good.close();
        assertEquals(1, goodRetired.get());
        assertEquals(1, unavailableRetired.get());
    }

    @Test void readinessDependenciesAndAllHostCallbacksAreRequiredBeforeRetaining() throws Exception {
        var incomplete = fixture(false);
        var neverCalled = new CloudlyFrameExecutor.Inputs() {
            @Override public CloudlyFrameExecutor.ComputeInput compute(CloudlyFrameSchedule.Stage stage) { throw new AssertionError("Unready plan consumed inputs"); }
            @Override public CloudlyFrameExecutor.HostOperation host(CloudlyFrameSchedule.Stage stage) { throw new AssertionError("Unready plan consumed host operation"); }
        };
        assertThrows(IllegalStateException.class, () -> CloudlyFrameExecutor.preflight(incomplete, incomplete.resolve(Map.of()),
                Set.of("Trace"), Map.of(), neverCalled));
        var schedule = fixture(true);
        var resolved = schedule.resolve(Map.of());
        var valid = inputs(constants(), bindings(), Map.of("channelIndex", 0L), List.of(), ResourceOwner.none(), 60);
        assertThrows(IllegalArgumentException.class, () -> CloudlyFrameExecutor.preflight(schedule, resolved, Set.of("Trace"), Map.of(), valid));
        try (var plan = CloudlyFrameExecutor.preflight(schedule, resolved, Set.of("Trace"), Map.of("Prepare", ResourceOwner.none()), valid)) {
            assertEquals(List.of("Trace"), plan.steps().stream().map(CloudlyFrameExecutor.Step::name).toList());
        }
        var missingHost = new CloudlyFrameExecutor.Inputs() {
            @Override public CloudlyFrameExecutor.ComputeInput compute(CloudlyFrameSchedule.Stage stage) { return valid.compute(stage); }
            @Override public CloudlyFrameExecutor.HostOperation host(CloudlyFrameSchedule.Stage stage) { return null; }
        };
        assertThrows(NullPointerException.class, () -> CloudlyFrameExecutor.preflight(schedule, resolved,
                Set.of("Prepare", "Trace"), Map.of(), missingHost));
        var missingPublication = new CloudlyFrameExecutor.Inputs() {
            @Override public CloudlyFrameExecutor.ComputeInput compute(CloudlyFrameSchedule.Stage stage) { return valid.compute(stage); }
            @Override public CloudlyFrameExecutor.HostOperation host(CloudlyFrameSchedule.Stage stage) { return valid.host(stage); }
        };
        assertThrows(NullPointerException.class, () -> CloudlyFrameExecutor.preflight(schedule, resolved,
                Set.of("Prepare", "Trace", "Indirect"), Map.of(), missingPublication));
    }

    @Test void wrongHeapManagedPushAndOutOfBoundsIndirectCommandsFailDuringPreflight() throws Exception {
        var schedule = fixture(true);
        var resolved = schedule.resolve(Map.of());
        var wrongHeap = bindings();
        wrongHeap.put("Filter", new GpuDescriptorIndex.Resource(12));
        assertThrows(IllegalArgumentException.class, () -> CloudlyFrameExecutor.preflight(schedule, resolved,
                Set.of("Prepare", "Trace"), Map.of(), inputs(constants(), wrongHeap, Map.of("channelIndex", 0L), List.of(), ResourceOwner.none(), 60)));
        assertThrows(IllegalArgumentException.class, () -> CloudlyFrameExecutor.preflight(schedule, resolved,
                Set.of("Prepare", "Trace"), Map.of(), inputs(constants(), bindings(), Map.of("channelIndex", 0L, "parametersAddress", 123L),
                        List.of(), ResourceOwner.none(), 60)));
        var all = Set.of("Prepare", "Trace", "Indirect");
        assertThrows(IllegalArgumentException.class, () -> CloudlyFrameExecutor.preflight(schedule, resolved, all, Map.of(),
                inputs(constants(), bindings(), Map.of("channelIndex", 0L), List.of(), ResourceOwner.none(), 64)));
        var wrongDispatches = new LinkedHashMap<>(resolved.dispatches());
        wrongDispatches.put("Indirect", new CloudlyFrameSchedule.IndirectDispatch("Args", 52));
        var outOfBounds = new CloudlyFrameSchedule.Resolved(resolved.images(), resolved.buffers(), wrongDispatches);
        assertThrows(IllegalArgumentException.class, () -> CloudlyFrameExecutor.preflight(schedule, outOfBounds, all, Map.of(),
                inputs(constants(), bindings(), Map.of("channelIndex", 0L), List.of(), ResourceOwner.none(), 60)));
    }

    private static CloudlyFrameExecutor.Inputs inputs(ByteBuffer constants, Map<String, GpuDescriptorIndex> bindings,
                                                     Map<String, Long> push, List<ResourceOwner> owners,
                                                     ResourceOwner argumentOwner, long argumentBytes) {
        return new CloudlyFrameExecutor.Inputs() {
            @Override public CloudlyFrameExecutor.ComputeInput compute(CloudlyFrameSchedule.Stage stage) {
                var indirect = stage.name().equals("Indirect") ? new CloudlyFrameExecutor.IndirectBuffer(7, argumentBytes, argumentOwner) : null;
                return new CloudlyFrameExecutor.ComputeInput(constants, (name, field) -> bindings.get(name), push, null, owners, indirect);
            }
            @Override public CloudlyFrameExecutor.HostOperation host(CloudlyFrameSchedule.Stage stage) {
                return new CloudlyFrameExecutor.HostOperation(frame -> { }, owners);
            }
            @Override public CloudlyFrameExecutor.HostOperation afterCompute(CloudlyFrameSchedule.Stage stage) {
                return new CloudlyFrameExecutor.HostOperation(frame -> { }, owners);
            }
        };
    }

    private static ByteBuffer constants() {
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(0, 17).putInt(4, 23);
    }

    private static Map<String, GpuDescriptorIndex> bindings() {
        return new LinkedHashMap<>(Map.of("Image", new GpuDescriptorIndex.Resource(11), "Filter", new GpuDescriptorIndex.Sampler(12)));
    }

    private CloudlyFrameSchedule fixture(boolean ready) throws Exception {
        byte[] code = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x07230203).putInt(0x00010600).putInt(0).putInt(1).putInt(0).array();
        Files.write(directory.resolve("fixture.spv"), code);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code));
        Files.writeString(directory.resolve("library.json"), """
                {"schemaVersion":1,"unifiedResourceHeapStride":true,"programs":[{"id":"fixture","entryPoint":"main","spirv":"fixture.spv",
                "sha256":"%s","sourceSha256":"%s","parametersByteSize":8,"bindingByteSize":8,"pushByteSize":24,
                "pushFields":[{"name":"parametersAddress","offset":0,"size":8},{"name":"bindingsAddress","offset":8,"size":8},
                {"name":"channelIndex","offset":16,"size":4}],"commonFields":[{"name":"frameNumber","type":"uint","offset":0,"size":4}],
                "bindings":[{"name":"image","heap":"Resource","type":"Texture2D<float4>","byteOffset":0},
                {"name":"filter","heap":"Sampler","type":"SamplerState","byteOffset":4}]}]}
                """.formatted(hash, "1".repeat(64)));
        Files.writeString(directory.resolve("frame.json"), """
                {"schemaVersion":1,"executionReady":%s,"images":[],
                "buffers":[{"name":"Args","strideBytes":4,"elements":15,"descriptorType":"STORAGE_TEXEL_BUFFER","format":98}],
                "samplers":{},"externalDescriptors":[{"name":"Image","descriptorType":"SAMPLED_IMAGE"},{"name":"Filter","descriptorType":"SAMPLER"}],
                "stages":[{"name":"Prepare","operation":"HOST_COPY","dependsOn":[]},
                {"name":"Trace","operation":"COMPUTE","dependsOn":["Prepare"],"runtimeProgramId":"fixture",
                "bindingRefs":{"image":"Image","filter":"Filter"},"dispatch":{"groupCounts":[1,1,1]},"commonOverrides":{"frameNumber":99}},
                {"name":"Indirect","operation":"COMPUTE","dependsOn":["Trace"],"runtimeProgramId":"fixture",
                "bindingRefs":{"image":"Image","filter":"Filter"},"dispatch":{"indirectBuffer":"Args","byteOffset":48},"postCpuOperation":"PUBLISH"}]}
                """.formatted(ready));
        return CloudlyFrameSchedule.load(directory.resolve("frame.json"), CloudlyShaderLibrary.load(directory.resolve("library.json")));
    }
}
