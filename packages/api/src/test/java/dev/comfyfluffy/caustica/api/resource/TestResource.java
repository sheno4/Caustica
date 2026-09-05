package dev.comfyfluffy.caustica.api.resource;

/** In-memory provider graph for testing ownership carried by API values. */
public final class TestResource implements ResourceRef {
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

    @Override public ResourceOwner retain() {
        if (references == 0) throw new IllegalStateException("resource is released");
        references++;
        return new Owner();
    }

    private final class Owner implements ResourceOwner {
        private boolean closed;
        @Override public ResourceRef reference() { return TestResource.this; }
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
