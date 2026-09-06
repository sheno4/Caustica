package dev.comfyfluffy.caustica.example.gltfviewer;

import dev.comfyfluffy.caustica.api.resource.ResourceOwner;

/** In-memory provider graph for testing ownership carried by API values. */
public final class TestResource {
    private final Runnable release;
    private int references;

    private TestResource(Runnable release) {
        this.release = release;
    }

    public static ResourceOwner create(Runnable release) {
        TestResource resource = new TestResource(release);
        resource.references = 1;
        return resource.new Owner();
    }

    private ResourceOwner retain() {
        if (references == 0) throw new IllegalStateException("resource is released");
        references++;
        return new Owner();
    }

    private final class Owner implements ResourceOwner {
        private boolean closed;
        @Override public ResourceOwner retain() {
            if (closed) throw new IllegalStateException("owner is closed");
            return TestResource.this.retain();
        }
        @Override public void close() {
            if (closed) return;
            closed = true;
            if (--references == 0) release.run();
        }
    }
}
