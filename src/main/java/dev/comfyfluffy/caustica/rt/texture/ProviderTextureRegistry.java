package dev.comfyfluffy.caustica.rt.texture;

import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.gpu.BorrowedVulkanTexture;
import dev.comfyfluffy.caustica.api.provider.CpuTextureResource;
import dev.comfyfluffy.caustica.api.provider.SceneMesh;
import dev.comfyfluffy.caustica.api.provider.TextureRegistrar;
import dev.comfyfluffy.caustica.api.provider.TextureResource;
import dev.comfyfluffy.caustica.api.provider.TextureSink;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Activation-owned append-only bindless texture table. */
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

    private record Staged(SceneMesh.TextureReference reference, TextureResource resource, int slot) {
    }

    private final int capacity;
    private final CpuUploader uploader;
    private final DescriptorWriter descriptors;
    private final Map<Key, Entry> references = new LinkedHashMap<>();
    private final List<Entry> entries = new ArrayList<>();
    private final Set<BorrowedVulkanTexture> borrowedResources =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final UploadedTexture fallback;
    private int nextSlot = 1;
    private boolean submissionActive;
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

    /** Start one atomic source contribution. Slots returned by it become live only when it commits. */
    public Submission submission(ResourceId source) {
        if (closed) throw new IllegalStateException("texture registry is closed");
        if (submissionActive) throw new IllegalStateException("texture submission is already active");
        Objects.requireNonNull(source, "source");
        submissionActive = true;
        return new Submission(source);
    }

    public final class Submission implements TextureSink, TextureRegistrar, AutoCloseable {
        private final ResourceId source;
        private final List<Staged> staged = new ArrayList<>();
        private final Map<SceneMesh.TextureReference, TextureResource> stagedReferences = new LinkedHashMap<>();
        private final Set<BorrowedVulkanTexture> stagedBorrowed =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<BorrowedVulkanTexture> rejectedBorrowed =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private boolean finished;
        private boolean invalid;

        private Submission(ResourceId source) {
            this.source = source;
        }

        @Override
        public int register(TextureResource resource) {
            return stage(null, resource);
        }

        @Override
        public void submit(SceneMesh.TextureReference reference, TextureResource resource) {
            if (finished) throw new IllegalStateException("texture submission is finished");
            Objects.requireNonNull(reference, "reference");
            Objects.requireNonNull(resource, "resource");
            if (references.containsKey(new Key(source, reference))
                    || stagedReferences.putIfAbsent(reference, resource) != null) {
                rejectBorrowed(resource);
                invalid = true;
                throw new IllegalArgumentException("duplicate texture contribution " + source + "/" + reference);
            }
            try {
                stage(reference, resource);
            } catch (Throwable failure) {
                stagedReferences.remove(reference);
                throw failure;
            }
        }

        private int stage(SceneMesh.TextureReference reference, TextureResource resource) {
            if (finished) throw new IllegalStateException("texture submission is finished");
            Objects.requireNonNull(resource, "resource");
            if (!(resource instanceof CpuTextureResource) && !(resource instanceof BorrowedVulkanTexture)) {
                invalid = true;
                throw new IllegalArgumentException("unsupported texture resource " + resource.getClass());
            }
            if (staged.size() >= capacity - nextSlot) {
                rejectBorrowed(resource);
                invalid = true;
                throw new IllegalStateException("provider texture table is full");
            }
            if (resource instanceof BorrowedVulkanTexture borrowed) {
                if (borrowedResources.contains(borrowed)) {
                    invalid = true;
                    throw new IllegalArgumentException("borrowed texture resource was already submitted");
                }
                if (!stagedBorrowed.add(borrowed)) {
                    invalid = true;
                    throw new IllegalArgumentException("borrowed texture resource was submitted more than once");
                }
            }
            int slot = nextSlot + staged.size();
            staged.add(new Staged(reference, resource, slot));
            return slot;
        }

        private void rejectBorrowed(TextureResource resource) {
            if (resource instanceof BorrowedVulkanTexture borrowed
                    && !borrowedResources.contains(borrowed) && !stagedBorrowed.contains(borrowed)) {
                rejectedBorrowed.add(borrowed);
            }
        }

        public void commit() {
            if (finished) throw new IllegalStateException("texture submission is finished");
            if (invalid) throw new IllegalStateException("texture submission was rejected");
            if (closed) throw new IllegalStateException("texture registry is closed");

            ArrayList<Entry> materialized = new ArrayList<>(staged.size());
            try {
                for (Staged contribution : staged) {
                    TextureResource resource = contribution.resource();
                    Entry entry = switch (resource) {
                        case CpuTextureResource cpu -> {
                            String suffix = contribution.reference() == null
                                    ? "texture-" + contribution.slot() : contribution.reference().toString();
                            UploadedTexture uploaded = uploader.upload(cpu, source + "/" + suffix);
                            yield new Entry(contribution.slot(), uploaded.imageView(), uploaded.imageLayout(),
                                    uploaded::destroy);
                        }
                        case BorrowedVulkanTexture borrowed -> new Entry(contribution.slot(), borrowed.imageView(),
                                borrowed.imageLayout(), borrowed.retired());
                        default -> throw new AssertionError(resource);
                    };
                    materialized.add(entry);
                }
                for (Entry entry : materialized) {
                    descriptors.write(entry.slot(), entry.imageView(), entry.imageLayout());
                }
            } catch (Throwable failure) {
                finish();
                try {
                    retireFailedSubmission(materialized);
                } catch (Throwable retirementFailure) {
                    failure.addSuppressed(retirementFailure);
                }
                throw failure;
            }

            for (int index = 0; index < staged.size(); index++) {
                Staged contribution = staged.get(index);
                Entry entry = materialized.get(index);
                entries.add(entry);
                if (contribution.reference() != null) references.put(new Key(source, contribution.reference()), entry);
                if (contribution.resource() instanceof BorrowedVulkanTexture borrowed) borrowedResources.add(borrowed);
            }
            nextSlot += staged.size();
            staged.clear();
            stagedReferences.clear();
            rejectedBorrowed.clear();
            finish();
        }

        private void retireFailedSubmission(List<Entry> materialized) {
            Set<TextureResource> materializedResources = Collections.newSetFromMap(new IdentityHashMap<>());
            for (int index = 0; index < materialized.size(); index++) {
                materializedResources.add(staged.get(index).resource());
            }
            ArrayList<Runnable> retirements = new ArrayList<>();
            for (int index = materialized.size() - 1; index >= 0; index--) {
                retirements.add(materialized.get(index).retire());
            }
            for (Staged contribution : staged) {
                if (!materializedResources.contains(contribution.resource())
                        && contribution.resource() instanceof BorrowedVulkanTexture borrowed) {
                    retirements.add(borrowed.retired());
                }
            }
            for (BorrowedVulkanTexture borrowed : rejectedBorrowed) retirements.add(borrowed.retired());
            staged.clear();
            stagedReferences.clear();
            rejectedBorrowed.clear();
            retireAll(retirements);
        }

        private void finish() {
            finished = true;
            submissionActive = false;
        }

        @Override
        public void close() {
            if (finished) return;
            ArrayList<Runnable> retirements = new ArrayList<>();
            for (Staged contribution : staged) {
                if (contribution.resource() instanceof BorrowedVulkanTexture borrowed) {
                    retirements.add(borrowed.retired());
                }
            }
            for (BorrowedVulkanTexture borrowed : rejectedBorrowed) retirements.add(borrowed.retired());
            staged.clear();
            stagedReferences.clear();
            rejectedBorrowed.clear();
            finish();
            retireAll(retirements);
        }
    }

    /** Resolve a submitted source-local reference while packing that source's geometry. */
    public int requireSlot(ResourceId source, SceneMesh.TextureReference reference) {
        Entry entry = references.get(new Key(source, reference));
        if (entry == null) throw new IllegalArgumentException("texture was not submitted: " + source + "/" + reference);
        return entry.slot();
    }

    public int size() {
        return entries.size();
    }

    /** Retire all resources after the runtime has drained GPU work referencing this table. */
    @Override
    public void close() {
        if (closed) return;
        if (submissionActive) throw new IllegalStateException("texture submission is active");
        closed = true;
        Entry[] retired = entries.toArray(Entry[]::new);
        entries.clear();
        references.clear();
        borrowedResources.clear();
        ArrayList<Runnable> retirements = new ArrayList<>(retired.length + 1);
        for (int index = retired.length - 1; index >= 0; index--) retirements.add(retired[index].retire());
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
