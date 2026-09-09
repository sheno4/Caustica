package dev.comfyfluffy.caustica.engine.resource;

import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.resource.FrameResources;
import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.program.ShaderData;
import dev.comfyfluffy.caustica.api.scene.EnvironmentBinding;


import java.util.IdentityHashMap;
import java.util.List;

/** Deduplicated strong ownership for captured inputs or resources attached to one frame execution. */
public final class ResourceOwners implements FrameResources, AutoCloseable {
    private final IdentityHashMap<ResourceOwner, ResourceOwner> owners = new IdentityHashMap<>();
    private boolean closed;

    @Override public synchronized void retain(ResourceOwner reference) {
        if (closed) throw new IllegalStateException("resource collection is closed");
        if (reference != ResourceOwner.none() && !owners.containsKey(reference)) {
            owners.put(reference, reference.retain());
        }
    }

    /** Retain and return the consumer's handle, never the producer's borrowed handle. */
    private synchronized ResourceOwner acquire(ResourceOwner resource) {
        retain(resource);
        return resource == ResourceOwner.none() ? resource : owners.get(resource);
    }

    @SuppressWarnings("unchecked")
    public <T> ShaderData<T> data(
            ShaderData<T> value) {
        return (ShaderData<T>) acquire(value);
    }

    public <T> EnvironmentBinding<T> environment(
            EnvironmentBinding<T> value) {
        return new EnvironmentBinding<>(value.implementation(), data(value.bindingData()));
    }

    public <N> MeshBuild<N> mesh(
            MeshBuild<N> source) {
        var geometries = source.geometries().stream().map(geometry -> {
            var surface = geometry.surface();
            var volume = geometry.volume();
            return new MeshBuild.Geometry<N>(
                    surface == null ? null : surface(surface), volume == null ? null : volume(volume),
                    geometry.firstIndex(), geometry.indexCount(), geometry.opacityMicromap());
        }).toList();
        return new MeshBuild<>(stream(source.positions()), stream(source.indices()),
                source.vertexCount(), source.indexRevision(), source.buildPolicy(), geometries);
    }

    private MeshBuild.Stream stream(
            MeshBuild.Stream source) {
        return new MeshBuild.Stream(source.bytes(), source.byteStride(), acquire(source.resource()));
    }

    private <B, N> MeshBuild.SurfaceSlot<B, N> surface(
            MeshBuild.SurfaceSlot<B, N> source) {
        return new MeshBuild.SurfaceSlot<>(source.surface(), data(source.bindingData()), source.coverage());
    }

    private <B, N> MeshBuild.VolumeSlot<B, N> volume(
            MeshBuild.VolumeSlot<B, N> source) {
        return new MeshBuild.VolumeSlot<>(source.volume(), data(source.bindingData()));
    }

    public static ResourceOwners capture(Iterable<? extends ResourceOwner> references) {
        ResourceOwners result = new ResourceOwners();
        try {
            for (ResourceOwner reference : references) {
                result.retain(reference);
            }
            return result;
        } catch (Throwable failure) {
            try {
                result.close();
            } catch (Throwable cleanup) {
                if (failure != cleanup) failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    /** Borrow a captured claim while this collection remains retained. */
    public synchronized ResourceOwner borrowed(ResourceOwner reference) { return owners.get(reference); }

    @Override public void close() {
        List<ResourceOwner> released;
        synchronized (this) {
            if (closed) return;
            closed = true;
            released = List.copyOf(owners.values());
            owners.clear();
        }
        Throwable failure = null;
        for (ResourceOwner owner : released) {
            try {
                owner.close();
            } catch (RuntimeException | Error cleanup) {
                if (failure == null) failure = cleanup;
                else if (failure != cleanup) failure.addSuppressed(cleanup);
            }
        }
        if (failure instanceof RuntimeException exception) throw exception;
        if (failure instanceof Error error) throw error;
    }
}
