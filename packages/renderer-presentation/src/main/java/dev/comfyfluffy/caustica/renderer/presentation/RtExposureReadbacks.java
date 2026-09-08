package dev.comfyfluffy.caustica.renderer.presentation;

import dev.comfyfluffy.caustica.renderer.presentation.gen.ExposureStateData;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/** GPU completion returns writable slots and publishes immutable exposure feedback. */
final class RtExposureReadbacks<T> implements AutoCloseable {
    private final ArrayList<T> available;
    private final Function<T, ExposureStateData> read;
    private final Consumer<T> destroy;
    private Feedback completed;
    private boolean closed;

    RtExposureReadbacks(List<T> buffers, Function<T, ExposureStateData> read, Consumer<T> destroy) {
        available = new ArrayList<>(buffers);
        this.read = read;
        this.destroy = destroy;
    }

    synchronized Reservation acquire() {
        if (closed) throw new IllegalStateException("exposure readbacks are closed");
        return available.isEmpty() ? null : new Reservation(available.removeLast());
    }

    synchronized Feedback latest(int resetSequence) {
        return completed != null && completed.resetSequence == resetSequence ? completed : null;
    }

    @Override public void close() {
        List<T> released;
        synchronized (this) {
            closed = true;
            completed = null;
            released = List.copyOf(available);
            available.clear();
        }
        released.forEach(destroy);
    }

    record Feedback(long frameId, float preExposure, int resetSequence, ExposureStateData state) { }

    final class Reservation implements AutoCloseable {
        private T buffer;
        private boolean submitted;
        private long frameId;
        private float preExposure;
        private int resetSequence;

        private Reservation(T buffer) { this.buffer = buffer; }

        T buffer() { return buffer; }

        /** Only a successfully submitted copy can become controller feedback. */
        void submitted(long frameId, float preExposure, int resetSequence) {
            this.frameId = frameId;
            this.preExposure = preExposure;
            this.resetSequence = resetSequence;
            submitted = true;
        }

        /** Called when accepted GPU work completes, or when an unsubmitted frame is abandoned. */
        @Override public void close() {
            T released = buffer;
            if (released == null) return;
            buffer = null;
            Feedback feedback = null;
            boolean recycle;
            try {
                if (submitted) feedback = new Feedback(frameId, preExposure, resetSequence, read.apply(released));
            } finally {
                synchronized (RtExposureReadbacks.this) {
                    recycle = !closed;
                    if (recycle) {
                        if (feedback != null && (completed == null || feedback.frameId > completed.frameId)) {
                            completed = feedback;
                        }
                        available.add(released);
                    }
                }
                if (!recycle) destroy.accept(released);
            }
        }
    }
}
