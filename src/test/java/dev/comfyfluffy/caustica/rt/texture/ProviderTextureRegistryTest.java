package dev.comfyfluffy.caustica.rt.texture;

import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.gpu.BorrowedVulkanTexture;
import dev.comfyfluffy.caustica.api.provider.CpuTextureResource;
import dev.comfyfluffy.caustica.api.provider.SceneMesh;
import dev.comfyfluffy.caustica.api.provider.TextureResource;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VK10;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProviderTextureRegistryTest {
    private static final ResourceId FIRST = ResourceId.of("test", "first");
    private static final ResourceId SECOND = ResourceId.of("test", "second");
    private static final SceneMesh.TextureReference TEXTURE =
            new SceneMesh.StandaloneTexture(ResourceId.of("test", "albedo"));
    private static final SceneMesh.TextureReference NORMAL =
            new SceneMesh.StandaloneTexture(ResourceId.of("test", "normal"));

    @Test
    void qualifiesReferencesByProviderAndKeepsSlotsPrivateAndStable() {
        AtomicInteger destroyed = new AtomicInteger();
        List<Write> writes = new ArrayList<>();
        ProviderTextureRegistry registry = new ProviderTextureRegistry(4,
                (texture, label) -> uploaded(101L, destroyed),
                (slot, view, layout) -> writes.add(new Write(slot, view, layout)));

        submit(registry, FIRST, TEXTURE, cpu());
        submit(registry, SECOND, TEXTURE,
                new BorrowedVulkanTexture(202L, VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        destroyed::incrementAndGet));

        assertEquals(1, registry.requireSlot(FIRST, TEXTURE));
        assertEquals(2, registry.requireSlot(SECOND, TEXTURE));
        assertEquals(List.of(
                new Write(0, 101L, VK10.VK_IMAGE_LAYOUT_GENERAL),
                new Write(1, 101L, VK10.VK_IMAGE_LAYOUT_GENERAL),
                new Write(2, 202L, VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)), writes);

        registry.close();
        registry.close();
        assertEquals(3, destroyed.get());
    }

    @Test
    void rejectsDuplicateAndOverflowingContributions() {
        ProviderTextureRegistry registry = new ProviderTextureRegistry(2,
                (texture, label) -> uploaded(101L, new AtomicInteger()), (slot, view, layout) -> { });
        submit(registry, FIRST, TEXTURE, cpu());
        assertThrows(IllegalArgumentException.class, () -> submit(registry, FIRST, TEXTURE, cpu()));
        assertThrows(IllegalStateException.class, () -> submit(registry, SECOND, TEXTURE, cpu()));
        registry.close();
    }

    @Test
    void retiresAnUploadWhenItsDescriptorCannotBePublished() {
        AtomicInteger destroyed = new AtomicInteger();
        ProviderTextureRegistry registry = new ProviderTextureRegistry(2,
                (texture, label) -> uploaded(101L, destroyed),
                (slot, view, layout) -> {
                    if (slot != 0) throw new IllegalStateException("descriptor failure");
                });

        assertThrows(IllegalStateException.class, () -> submit(registry, FIRST, TEXTURE, cpu()));
        assertEquals(1, destroyed.get());
        assertEquals(0, registry.size());
        registry.close();
        assertEquals(2, destroyed.get());
    }

    @Test
    void cpuTextureCopiesProviderMemory() {
        byte[] pixels = { 1, 2, 3, 4 };
        CpuTextureResource texture = new CpuTextureResource(1, 1, CpuTextureResource.Encoding.SRGB, pixels);
        pixels[0] = 9;
        assertArrayEquals(new byte[] { 1, 2, 3, 4 }, texture.rgba8());
        byte[] read = texture.rgba8();
        read[1] = 9;
        assertArrayEquals(new byte[] { 1, 2, 3, 4 }, texture.rgba8());
    }

    @Test
    void borrowedViewIsReleasedOnlyWhenTheDrainedEpochCloses() {
        AtomicBoolean gpuDrained = new AtomicBoolean();
        AtomicBoolean hostMayReleaseView = new AtomicBoolean();
        ProviderTextureRegistry registry = new ProviderTextureRegistry(2,
                (texture, label) -> uploaded(101L, new AtomicInteger()), (slot, view, layout) -> { });
        submit(registry, FIRST, TEXTURE,
                new BorrowedVulkanTexture(202L, VK10.VK_IMAGE_LAYOUT_GENERAL, () -> {
                    assertTrue(gpuDrained.get(), "borrowed view retired before descriptor work drained");
                    hostMayReleaseView.set(true);
                }));

        assertFalse(hostMayReleaseView.get());
        gpuDrained.set(true);
        registry.close();
        assertTrue(hostMayReleaseView.get());
    }

    @Test
    void overflowingBatchRetiresBorrowedViewsWithoutConsumingCapacity() {
        AtomicInteger retired = new AtomicInteger();
        ProviderTextureRegistry registry = new ProviderTextureRegistry(2,
                (texture, label) -> uploaded(101L, new AtomicInteger()), (slot, view, layout) -> { });

        assertThrows(IllegalStateException.class, () -> {
            try (ProviderTextureRegistry.Submission submission = registry.submission(FIRST)) {
                submission.submit(TEXTURE, borrowed(201L, retired));
                submission.submit(NORMAL, borrowed(202L, retired));
                submission.commit();
            }
        });

        assertEquals(2, retired.get());
        assertEquals(0, registry.size());
        submit(registry, SECOND, TEXTURE, borrowed(203L, retired));
        assertEquals(1, registry.requireSlot(SECOND, TEXTURE));
        registry.close();
        assertEquals(3, retired.get());
    }

    @Test
    void duplicateInBatchRollsBackEveryAcceptedBorrow() {
        AtomicInteger retired = new AtomicInteger();
        ProviderTextureRegistry registry = new ProviderTextureRegistry(3,
                (texture, label) -> uploaded(101L, new AtomicInteger()), (slot, view, layout) -> { });

        assertThrows(IllegalArgumentException.class, () -> {
            try (ProviderTextureRegistry.Submission submission = registry.submission(FIRST)) {
                submission.submit(TEXTURE, borrowed(201L, retired));
                submission.submit(TEXTURE, borrowed(202L, retired));
            }
        });

        assertEquals(2, retired.get());
        assertEquals(0, registry.size());
        submit(registry, SECOND, TEXTURE, borrowed(203L, retired));
        assertEquals(1, registry.requireSlot(SECOND, TEXTURE));
        registry.close();
    }

    private static CpuTextureResource cpu() {
        return new CpuTextureResource(1, 1, CpuTextureResource.Encoding.SRGB, new byte[] { 1, 2, 3, 4 });
    }

    private static void submit(ProviderTextureRegistry registry, ResourceId source,
                               SceneMesh.TextureReference reference, TextureResource resource) {
        try (ProviderTextureRegistry.Submission submission = registry.submission(source)) {
            submission.submit(reference, resource);
            submission.commit();
        }
    }

    private static ProviderTextureRegistry.UploadedTexture uploaded(long view, AtomicInteger destroyed) {
        return new ProviderTextureRegistry.UploadedTexture() {
            @Override public long imageView() { return view; }
            @Override public int imageLayout() { return VK10.VK_IMAGE_LAYOUT_GENERAL; }
            @Override public void destroy() { destroyed.incrementAndGet(); }
        };
    }

    private static BorrowedVulkanTexture borrowed(long view, AtomicInteger retired) {
        return new BorrowedVulkanTexture(view, VK10.VK_IMAGE_LAYOUT_GENERAL, retired::incrementAndGet);
    }

    private record Write(int slot, long view, int layout) {
    }
}
