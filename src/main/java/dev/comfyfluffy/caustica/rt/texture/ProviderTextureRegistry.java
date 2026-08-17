package dev.comfyfluffy.caustica.rt.texture;

import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.gpu.BorrowedVulkanTexture;
import dev.comfyfluffy.caustica.api.provider.CpuTextureResource;
import dev.comfyfluffy.caustica.api.provider.SceneMesh;
import dev.comfyfluffy.caustica.api.provider.TextureResource;
import dev.comfyfluffy.caustica.api.provider.TextureSink;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Activation-owned table mapping source-local texture references to private append-only descriptor slots. */
public final class ProviderTextureRegistry implements AutoCloseable {
    @FunctionalInterface
    public interface CpuUploader {
        UploadedTexture upload(CpuTextureResource texture, String label);
    }

    public interface UploadedTexture {
        long imageView();

        int imageLayout();

        void destroy();
    }

    @FunctionalInterface
    public interface DescriptorWriter {
        void write(int slot, long imageView, int imageLayout);
    }

    private record Key(ResourceId source, SceneMesh.TextureReference reference) {
        private Key {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(reference, "reference");
        }
    }

    private record Entry(int slot, long imageView, int imageLayout, Runnable retire) {
    }

    private final int capacity;
    private final CpuUploader uploader;
    private final Map<Key, Entry> entries = new LinkedHashMap<>();
    private final Set<BorrowedVulkanTexture> borrowedResources =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final UploadedTexture fallback;
    private DescriptorWriter descriptors;
    private int nextSlot = 1;
    private boolean closed;

    /** Slot zero is reserved for the renderer's fallback texture. */
    public ProviderTextureRegistry(int capacity, CpuUploader uploader, DescriptorWriter descriptors) {
        if (capacity < 2) throw new IllegalArgumentException("texture table needs a fallback and provider slot");
        this.capacity = capacity;
        this.uploader = Objects.requireNonNull(uploader, "uploader");
        this.descriptors = Objects.requireNonNull(descriptors, "descriptors");
        fallback = uploader.upload(new CpuTextureResource(1, 1, CpuTextureResource.Encoding.SRGB,
                new byte[] { -1, -1, -1, -1 }), "renderer texture fallback");
        try {
            descriptors.write(0, fallback.imageView(), fallback.imageLayout());
        } catch (Throwable failure) {
            fallback.destroy();
            throw failure;
        }
    }

    /** A one-contribution convenience sink. Multi-contribution providers use {@link #submission}. */
    public TextureSink sink(ResourceId source) {
        Objects.requireNonNull(source, "source");
        return (reference, resource) -> {
            try (Submission submission = submission(source)) {
                submission.submit(reference, resource);
                submission.commit();
            }
        };
    }

    /** Stage one provider callback so failure cannot mutate shared slots or capacity. */
    public Submission submission(ResourceId source) {
        if (closed) throw new IllegalStateException("texture registry is closed");
        return new Submission(Objects.requireNonNull(source, "source"));
    }

    public final class Submission implements TextureSink, AutoCloseable {
        private final ResourceId source;
        private final Map<SceneMesh.TextureReference, TextureResource> staged = new LinkedHashMap<>();
        private final Set<BorrowedVulkanTexture> stagedBorrowed =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private final List<BorrowedVulkanTexture> rejectedBorrowed = new ArrayList<>();
        private boolean finished;
        private boolean invalid;

        private Submission(ResourceId source) {
            this.source = source;
        }

        @Override
        public void submit(SceneMesh.TextureReference reference, TextureResource resource) {
            if (finished) throw new IllegalStateException("texture submission is finished");
            Objects.requireNonNull(reference, "reference");
            Objects.requireNonNull(resource, "resource");
            if (!(resource instanceof CpuTextureResource) && !(resource instanceof BorrowedVulkanTexture)) {
                invalid = true;
                throw new IllegalArgumentException("unsupported texture resource " + resource.getClass());
            }
            if (resource instanceof BorrowedVulkanTexture borrowed && borrowedResources.contains(borrowed)) {
                invalid = true;
                throw new IllegalArgumentException("borrowed texture resource was already submitted");
            }
            TextureResource previous = staged.putIfAbsent(reference, resource);
            if (previous != null) {
                if (resource instanceof BorrowedVulkanTexture borrowed && borrowed != previous) {
                    rejectedBorrowed.add(borrowed);
                }
                invalid = true;
                throw new IllegalArgumentException("duplicate texture contribution " + source + "/" + reference);
            }
            if (resource instanceof BorrowedVulkanTexture borrowed && !stagedBorrowed.add(borrowed)) {
                staged.remove(reference);
                invalid = true;
                throw new IllegalArgumentException("borrowed texture resource was submitted more than once");
            }
        }

        public void commit() {
            if (finished) throw new IllegalStateException("texture submission is finished");
            if (invalid) throw new IllegalStateException("texture submission was rejected");
            if (closed) throw new IllegalStateException("texture registry is closed");
            for (SceneMesh.TextureReference reference : staged.keySet()) {
                if (entries.containsKey(new Key(source, reference))) {
                    throw new IllegalArgumentException("duplicate texture contribution " + source + "/" + reference);
                }
            }
            if (staged.size() > capacity - nextSlot) {
                throw new IllegalStateException("provider texture table is full");
            }
            for (TextureResource resource : staged.values()) {
                if (resource instanceof BorrowedVulkanTexture borrowed && borrowedResources.contains(borrowed)) {
                    throw new IllegalArgumentException("borrowed texture resource was already submitted");
                }
            }

            ArrayList<Map.Entry<Key, Entry>> materialized = new ArrayList<>(staged.size());
            int slot = nextSlot;
            try {
                for (Map.Entry<SceneMesh.TextureReference, TextureResource> contribution : staged.entrySet()) {
                    TextureResource resource = contribution.getValue();
                    long imageView;
                    int imageLayout;
                    Runnable retire;
                    switch (resource) {
                        case CpuTextureResource cpu -> {
                            UploadedTexture uploaded = uploader.upload(cpu, source + "/" + contribution.getKey());
                            imageView = uploaded.imageView();
                            imageLayout = uploaded.imageLayout();
                            retire = uploaded::destroy;
                        }
                        case BorrowedVulkanTexture borrowed -> {
                            imageView = borrowed.imageView();
                            imageLayout = borrowed.imageLayout();
                            retire = borrowed.retired();
                        }
                        default -> throw new AssertionError(resource);
                    }
                    materialized.add(Map.entry(new Key(source, contribution.getKey()),
                            new Entry(slot++, imageView, imageLayout, retire)));
                }
                for (Map.Entry<Key, Entry> contribution : materialized) {
                    Entry entry = contribution.getValue();
                    descriptors.write(entry.slot(), entry.imageView(), entry.imageLayout());
                }
            } catch (Throwable failure) {
                finished = true;
                try {
                    retireFailedSubmission(materialized);
                } catch (Throwable retirementFailure) {
                    failure.addSuppressed(retirementFailure);
                }
                throw failure;
            }

            for (Map.Entry<Key, Entry> contribution : materialized) {
                entries.put(contribution.getKey(), contribution.getValue());
            }
            staged.values().stream().filter(BorrowedVulkanTexture.class::isInstance)
                    .map(BorrowedVulkanTexture.class::cast).forEach(borrowedResources::add);
            nextSlot = slot;
            staged.clear();
            rejectedBorrowed.clear();
            finished = true;
        }

        private void retireFailedSubmission(List<Map.Entry<Key, Entry>> materialized) {
            Set<TextureResource> materializedResources = Collections.newSetFromMap(new IdentityHashMap<>());
            int index = 0;
            for (TextureResource resource : staged.values()) {
                if (index < materialized.size()) {
                    materializedResources.add(resource);
                    index++;
                }
            }
            ArrayList<Runnable> retirements = new ArrayList<>();
            for (int i = materialized.size() - 1; i >= 0; i--) {
                retirements.add(materialized.get(i).getValue().retire());
            }
            for (TextureResource resource : staged.values()) {
                if (!materializedResources.contains(resource) && resource instanceof BorrowedVulkanTexture borrowed) {
                    retirements.add(borrowed.retired());
                }
            }
            staged.clear();
            for (BorrowedVulkanTexture borrowed : rejectedBorrowed) retirements.add(borrowed.retired());
            rejectedBorrowed.clear();
            retireAll(retirements);
        }

        @Override
        public void close() {
            if (finished) return;
            finished = true;
            ArrayList<Runnable> retirements = new ArrayList<>();
            for (TextureResource resource : staged.values()) {
                if (resource instanceof BorrowedVulkanTexture borrowed) retirements.add(borrowed.retired());
            }
            for (BorrowedVulkanTexture borrowed : rejectedBorrowed) retirements.add(borrowed.retired());
            staged.clear();
            rejectedBorrowed.clear();
            retireAll(retirements);
        }
    }

    /** Resolve a submitted source-local reference while packing that source's geometry. */
    public int requireSlot(ResourceId source, SceneMesh.TextureReference reference) {
        Entry entry = entries.get(new Key(source, reference));
        if (entry == null) throw new IllegalArgumentException("texture was not submitted: " + source + "/" + reference);
        return entry.slot();
    }

    /** Populate a replacement descriptor table without changing geometry-visible slots. */
    public void rebind(DescriptorWriter replacement) {
        if (closed) throw new IllegalStateException("texture registry is closed");
        descriptors = Objects.requireNonNull(replacement, "replacement");
        descriptors.write(0, fallback.imageView(), fallback.imageLayout());
        entries.values().forEach(entry -> descriptors.write(entry.slot(), entry.imageView(), entry.imageLayout()));
    }

    public int size() {
        return entries.size();
    }

    /**
     * Retire all resources after the runtime has drained GPU work referencing this table. Owned uploads are
     * destroyed; borrowed resources receive their provider callback. Slots are never reused within an epoch.
     */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        Entry[] retired = entries.values().toArray(Entry[]::new);
        entries.clear();
        borrowedResources.clear();
        ArrayList<Runnable> retirements = new ArrayList<>(retired.length + 1);
        for (int i = retired.length - 1; i >= 0; i--) retirements.add(retired[i].retire());
        retirements.add(fallback::destroy);
        retireAll(retirements);
    }

    private static void retireAll(List<Runnable> retirements) {
        Throwable failure = null;
        for (Runnable retirement : retirements) {
            try {
                retirement.run();
            } catch (Throwable current) {
                if (failure == null) failure = current;
                else failure.addSuppressed(current);
            }
        }
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new IllegalStateException("texture retirement failed", failure);
    }
}
