package dev.comfyfluffy.caustica.api.vulkan;

/** Terminal result of one accepted {@link GpuComputeQueue} job. */
public sealed interface GpuComputeCompletion
        permits GpuComputeCompletion.Succeeded, GpuComputeCompletion.Failed,
        GpuComputeCompletion.Cancelled {

    /** Every recorded command completed successfully. */
    record Succeeded() implements GpuComputeCompletion { }

    /** Recording, submission, or execution failed. */
    record Failed(Throwable failure) implements GpuComputeCompletion {
        public Failed {
            java.util.Objects.requireNonNull(failure, "failure");
        }
    }

    /** Cancellation won before this job began recording. */
    record Cancelled() implements GpuComputeCompletion { }
}
