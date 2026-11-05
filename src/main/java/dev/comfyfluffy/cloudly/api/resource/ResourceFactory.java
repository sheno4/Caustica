/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.comfyfluffy.cloudly.api.resource;

/** Thread-safe shared ownership; callers retain claims until their exact GPU use completes. */
public final class ResourceFactory {
    public ResourceOwner create() { return create(() -> { }); }
    public ResourceOwner create(Runnable retired) { return new Claim(new Shared(retired)); }

    private static final class Shared {
        final Runnable retired;
        int references = 1;
        Shared(Runnable retired) { this.retired = java.util.Objects.requireNonNull(retired); }
    }

    private static final class Claim implements ResourceOwner {
        private final Shared shared;
        private boolean closed;
        Claim(Shared shared) { this.shared = shared; }
        @Override public ResourceOwner retain() {
            synchronized (shared) {
                if (closed) throw new IllegalStateException("Resource claim is closed");
                shared.references++;
                return new Claim(shared);
            }
        }
        @Override public void close() {
            boolean destroy;
            synchronized (shared) {
                if (closed) return;
                closed = true;
                destroy = --shared.references == 0;
            }
            if (destroy) shared.retired.run();
        }
    }
}
