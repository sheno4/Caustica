package dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly;

import dev.comfyfluffy.caustica.api.pass.PassFrame;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorIndex;
import dev.comfyfluffy.caustica.vulkan.ResourceLifetime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Freezes one ordered source frame before recording its first GPU command. */
public final class CloudlyFrameExecutor {
    private CloudlyFrameExecutor() { }

    /**
     * Selected stages execute in source order. An omitted dependency must have an explicit retained
     * producer, including retained history from a prior frame. Descriptor roles are resolved here;
     * the caller supplies the role each stage sees after any earlier source ring rotation.
     */
    public static Plan preflight(CloudlyFrameSchedule schedule, CloudlyFrameSchedule.Resolved resolved,
                                 Set<String> selectedStages, Map<String, ResourceOwner> satisfiedDependencies,
                                 Inputs inputs) {
        schedule.requireExecutionReady();
        Set<String> names = new LinkedHashSet<>();
        schedule.stages().forEach(stage -> names.add(stage.name()));
        if (selectedStages.isEmpty() || !names.containsAll(selectedStages)
                || !names.containsAll(satisfiedDependencies.keySet())) {
            throw new IllegalArgumentException("Source frame selection contains missing stages or no work");
        }
        List<Step> steps = new ArrayList<>();
        List<ResourceOwner> borrowedOwners = new ArrayList<>();
        Set<String> programIds = new LinkedHashSet<>();
        Set<String> preceding = new LinkedHashSet<>();
        for (var stage : schedule.stages()) {
            if (!selectedStages.contains(stage.name())) continue;
            for (String dependency : stage.dependsOn()) {
                if (preceding.contains(dependency)) continue;
                ResourceOwner owner = satisfiedDependencies.get(dependency);
                if (owner == null) throw new IllegalArgumentException(
                        "Selected source stage has no executed or retained dependency: " + stage.name() + " <- " + dependency);
                borrowedOwners.add(owner);
            }
            if (stage.program() == null) {
                HostOperation host = Objects.requireNonNull(inputs.host(stage),
                        "Source host operation needs an explicit implementation: " + stage.name());
                borrowedOwners.addAll(host.owners());
                steps.add(new HostStep(stage.name(), host));
            } else {
                ComputeInput input = Objects.requireNonNull(inputs.compute(stage), "Missing source compute inputs: " + stage.name());
                ByteBuffer constants = schedule.constants(stage.name(), input.constants());
                var table = schedule.bindings(stage.name(), input.bindings());
                Map<String, Long> sourcePush = Map.copyOf(input.sourcePush());
                validatePush(stage.program(), sourcePush, input.camera());
                var dispatch = Objects.requireNonNull(resolved.dispatches().get(stage.name()),
                        "Missing resolved source dispatch: " + stage.name());
                if (dispatch instanceof CloudlyFrameSchedule.DirectDispatch direct) {
                    if (direct.x() < 1 || direct.y() < 1 || direct.z() < 1 || input.indirectBuffer() != null) {
                        throw new IllegalArgumentException("Invalid direct source dispatch: " + stage.name());
                    }
                } else if (dispatch instanceof CloudlyFrameSchedule.IndirectDispatch indirect) {
                    IndirectBuffer buffer = Objects.requireNonNull(input.indirectBuffer(), "Missing source indirect argument buffer");
                    var extent = Objects.requireNonNull(resolved.buffers().get(indirect.argumentBuffer()), "Missing source indirect buffer extent");
                    if (buffer.handle() == 0 || buffer.byteSize() != extent.byteSize() || indirect.byteOffset() < 0
                            || (indirect.byteOffset() & 3) != 0 || indirect.byteOffset() > buffer.byteSize() - 12) {
                        throw new IllegalArgumentException("Source indirect arguments leave their actual buffer: " + stage.name());
                    }
                    borrowedOwners.add(buffer.owner());
                }
                HostOperation after = null;
                if (stage.spec().has("postCpuOperation")) {
                    after = Objects.requireNonNull(inputs.afterCompute(stage),
                            "Source post-compute operation needs an explicit implementation: " + stage.name());
                    borrowedOwners.addAll(after.owners());
                }
                borrowedOwners.addAll(input.owners());
                steps.add(new ComputeStep(stage.name(), stage.program().id(), constants, table.descriptors(),
                        sourcePush, input.camera(), dispatch, input.indirectBuffer(), after));
                programIds.add(stage.program().id());
            }
            preceding.add(stage.name());
        }
        List<ResourceOwner> claims = new ArrayList<>();
        try {
            Map<ResourceOwner, ResourceOwner> retained = new IdentityHashMap<>();
            for (ResourceOwner owner : borrowedOwners) {
                if (retained.containsKey(owner)) continue;
                ResourceOwner claim = owner.retain();
                claims.add(claim);
                retained.put(owner, claim);
            }
            List<Step> ownedSteps = new ArrayList<>(steps.size());
            for (Step step : steps) {
                if (step instanceof ComputeStep compute && compute.indirectBuffer() != null) {
                    IndirectBuffer borrowed = compute.indirectBuffer();
                    var buffer = new IndirectBuffer(borrowed.handle(), borrowed.byteSize(), retained.get(borrowed.owner()));
                    ownedSteps.add(new ComputeStep(compute.name(), compute.programId(), compute.constants(), compute.descriptors(),
                            compute.sourcePush(), compute.camera(), compute.dispatch(), buffer, compute.afterCompute()));
                } else ownedSteps.add(step);
            }
            return new Plan(ownedSteps, programIds, claims);
        } catch (RuntimeException | Error failure) {
            ResourceLifetime.closeAfterFailure(failure, () -> closeClaims(claims));
            throw failure;
        }
    }

    private static void validatePush(CloudlyShaderLibrary.Program program, Map<String, Long> sourcePush,
                                     CloudlyFrameCamera camera) {
        Map<String, Long> push = new LinkedHashMap<>(sourcePush);
        for (String managed : List.of("parametersAddress", "bindingsAddress", "cameraAddress")) {
            if (sourcePush.containsKey(managed)) throw new IllegalArgumentException("Frame upload owns source push address " + managed);
        }
        push.put("parametersAddress", 16L);
        push.put("bindingsAddress", 32L);
        boolean usesCamera = program.pushLayout().fields().stream().anyMatch(field -> field.name().equals("cameraAddress"));
        if (usesCamera) {
            Objects.requireNonNull(camera, "Source camera bridge is required by " + program.id());
            camera.write(ByteBuffer.allocate(CloudlyFrameCamera.BYTE_SIZE));
            push.put("cameraAddress", 48L);
        }
        program.pushLayout().write(ByteBuffer.allocate(program.pushLayout().byteSize()), push);
    }

    private static ByteBuffer immutable(ByteBuffer bytes) {
        ByteBuffer copy = ByteBuffer.allocate(bytes.remaining()).order(ByteOrder.LITTLE_ENDIAN);
        copy.put(bytes.duplicate()).flip();
        return copy.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
    }

    private static void closeClaims(List<ResourceOwner> claims) {
        new ResourceLifetime(claims.stream().<Runnable>map(owner -> owner::close).toArray(Runnable[]::new)).close();
    }

    public interface Inputs {
        ComputeInput compute(CloudlyFrameSchedule.Stage stage);
        HostOperation host(CloudlyFrameSchedule.Stage stage);
        default HostOperation afterCompute(CloudlyFrameSchedule.Stage stage) { return null; }
    }

    /** Every descriptor and address reachable by this stage must be covered by its owners. */
    public record ComputeInput(ByteBuffer constants, CloudlyFrameSchedule.BindingResolver bindings,
                               Map<String, Long> sourcePush, CloudlyFrameCamera camera,
                               List<ResourceOwner> owners, IndirectBuffer indirectBuffer) {
        public ComputeInput { owners = List.copyOf(owners); sourcePush = Map.copyOf(sourcePush); }
    }

    public record IndirectBuffer(long handle, long byteSize, ResourceOwner owner) {
        public IndirectBuffer { Objects.requireNonNull(owner); }
    }

    /**
     * Callback inputs must already be frozen. Publication and history callbacks own submission and
     * abandonment handling; recording a command is not proof that its output has completed on the GPU.
     */
    public record HostOperation(Recorder recorder, List<ResourceOwner> owners) {
        public HostOperation { Objects.requireNonNull(recorder); owners = List.copyOf(owners); }
    }

    @FunctionalInterface public interface Recorder { void record(PassFrame frame); }

    public sealed interface Step permits ComputeStep, HostStep { String name(); }

    public record HostStep(String name, HostOperation operation) implements Step { }

    public record ComputeStep(String name, String programId, ByteBuffer constants,
                              Map<String, GpuDescriptorIndex> descriptors, Map<String, Long> sourcePush,
                              CloudlyFrameCamera camera, CloudlyFrameSchedule.Dispatch dispatch,
                              IndirectBuffer indirectBuffer, HostOperation afterCompute) implements Step {
        public ComputeStep {
            constants = immutable(constants);
            descriptors = Map.copyOf(descriptors);
            sourcePush = Map.copyOf(sourcePush);
        }
        @Override public ByteBuffer constants() { return constants.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN); }
    }

    /** Owns immutable dispatch inputs and independent resource claims until recorded or discarded. */
    public static final class Plan implements AutoCloseable {
        private final List<Step> steps;
        private final Set<String> programIds;
        private final List<ResourceOwner> owners;
        private boolean recorded;
        private boolean closed;

        private Plan(List<Step> steps, Set<String> programIds, List<ResourceOwner> owners) {
            this.steps = List.copyOf(steps);
            this.programIds = Set.copyOf(programIds);
            this.owners = List.copyOf(owners);
        }

        public List<Step> steps() { return steps; }
        public Set<String> programIds() { return programIds; }

        /** All programs are checked and all owners retained before the first host or compute command. */
        public void record(PassFrame frame, CloudlyFramePrograms programs) {
            if (closed || recorded) throw new IllegalStateException("Source frame plan is closed or already recorded");
            programs.requirePrograms(programIds);
            recorded = true;
            owners.forEach(frame::retain);
            for (Step step : steps) {
                if (step instanceof HostStep host) {
                    host.operation().recorder().record(frame);
                } else if (step instanceof ComputeStep compute) {
                    if (compute.dispatch() instanceof CloudlyFrameSchedule.DirectDispatch direct) {
                        programs.record(frame, compute.programId(), compute.constants(), compute.descriptors(),
                                compute.sourcePush(), compute.camera(), List.of(), direct.x(), direct.y(), direct.z());
                    } else if (compute.dispatch() instanceof CloudlyFrameSchedule.IndirectDispatch indirect) {
                        var buffer = compute.indirectBuffer();
                        programs.recordIndirect(frame, compute.programId(), compute.constants(), compute.descriptors(),
                                compute.sourcePush(), compute.camera(), List.of(), buffer.handle(), buffer.byteSize(),
                                indirect.byteOffset(), buffer.owner());
                    }
                    if (compute.afterCompute() != null) compute.afterCompute().recorder().record(frame);
                }
            }
        }

        @Override public void close() {
            if (!closed) {
                closed = true;
                closeClaims(owners);
            }
        }
    }
}
