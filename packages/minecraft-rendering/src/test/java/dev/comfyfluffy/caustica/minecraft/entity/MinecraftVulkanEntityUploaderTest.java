package dev.comfyfluffy.caustica.minecraft.entity;

import dev.comfyfluffy.caustica.minecraft.gen.MinecraftPrimitiveData;
import dev.comfyfluffy.caustica.settings.ResourceId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

final class MinecraftVulkanEntityUploaderTest {
    @Test void groupsOnlyAdjacentMatchingProgramAndCoverage() {
        var material = material("stone", MinecraftEntityMesh.Program.MATERIAL);
        var portal = material("portal", MinecraftEntityMesh.Program.PORTAL);
        MinecraftEntityMesh mesh = mesh(List.of(triangle(material, MinecraftEntityMesh.Coverage.OPAQUE),
                triangle(material, MinecraftEntityMesh.Coverage.OPAQUE),
                triangle(material, MinecraftEntityMesh.Coverage.CUTOUT),
                triangle(portal, MinecraftEntityMesh.Coverage.CUTOUT)));

        assertEquals(List.of(new MinecraftVulkanEntityUploader.GeometryRange(0, 2, null),
                new MinecraftVulkanEntityUploader.GeometryRange(2, 3, null),
                new MinecraftVulkanEntityUploader.GeometryRange(3, 4, null)),
                MinecraftVulkanEntityUploader.geometryRanges(mesh));
    }

    @Test void primitivePackingUsesMaterialDescriptorTintAndEmission() {
        var triangle = triangle(material("stone", MinecraftEntityMesh.Program.MATERIAL),
                MinecraftEntityMesh.Coverage.CUTOUT);
        var uv = new MinecraftPrimitiveData.Float2[]{new MinecraftPrimitiveData.Float2(0, 0),
                new MinecraftPrimitiveData.Float2(1, 0), new MinecraftPrimitiveData.Float2(0, 1)};
        var color = new MinecraftPrimitiveData.Float4(.2f, .3f, .4f, 1);

        MinecraftPrimitiveData record = MinecraftVulkanEntityUploader.primitiveRecord(
                triangle, uv, color, 17, 23, MinecraftVulkanEntityUploader.TangentBasis.ZERO);

        assertEquals(17, record.materialIndex());
        assertEquals(23, record.baseTexture().value());
        assertEquals(1, record.textureFlags());
        assertEquals(2.5f, record.primitiveEmission());
        assertEquals(.2f, record.vertexColors()[2].x());
        ByteBuffer bytes = ByteBuffer.allocate(MinecraftPrimitiveData.BYTE_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN);
        record.write(bytes);
        assertEquals(17, bytes.getInt(92));
        assertEquals(23, bytes.getInt(96));
        assertEquals(1, bytes.getInt(100));
        assertEquals(2.5f, bytes.getFloat(104));
    }

    @Test void textureSetDestroysDescriptorThenAllLeasesAndAggregatesFailures() {
        ArrayList<String> closed = new ArrayList<>();
        var range = new dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorRange<
                dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorIndex.Resource>() {
            @Override public dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorIndex.Resource firstIndex() {
                return new dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorIndex.Resource(4);
            }
            @Override public int descriptorCount() { return 1; }
            @Override public void destroy() { closed.add("descriptor"); throw new IllegalStateException("descriptor"); }
        };
        EntityTextureResolver.BorrowedTexture first = lease(closed, "first", true);
        EntityTextureResolver.BorrowedTexture second = lease(closed, "second", false);
        var set = new MinecraftVulkanEntityUploader.TextureSet(range, Map.of(), List.of(first, second));

        IllegalStateException failure = assertThrows(IllegalStateException.class, set::close);
        assertEquals(List.of("descriptor", "first", "second"), closed);
        assertEquals(1, failure.getSuppressed().length);
    }

    private static EntityTextureResolver.BorrowedTexture lease(
            List<String> closed, String name, boolean fail) {
        return new EntityTextureResolver.BorrowedTexture() {
            @Override public long vkImage() { return 1; }
            @Override public int format() { return 37; }
            @Override public int baseMipLevel() { return 0; }
            @Override public int mipLevels() { return 1; }
            @Override public int imageLayout() { return 0; }
            @Override public void close() {
                closed.add(name);
                if (fail) throw new IllegalArgumentException(name);
            }
        };
    }

    @Test void tangentBasisUsesIndexedPositionAndUvGradients() {
        var basis = MinecraftVulkanEntityUploader.tangentBasis(
                new float[]{0, 0, 0, 2, 0, 0, 0, 3, 0}, new int[]{0, 1, 2},
                new float[]{0, 0, 1, 0, 0, 1}, 0);
        assertEquals(1, basis.tangent().x());
        assertEquals(1, basis.bitangent().y());
    }

    @Test void cutoutRangesSplitWhenOpacityHintsDifferButStochasticIgnoresHints() {
        var a = material("a", MinecraftEntityMesh.Program.MATERIAL);
        var b = material("b", MinecraftEntityMesh.Program.MATERIAL);
        var hintA = new dev.comfyfluffy.caustica.api.geometry.MeshBuild.OpacityMicromapHint(0, 1, 2);
        var hintB = new dev.comfyfluffy.caustica.api.geometry.MeshBuild.OpacityMicromapHint(.1f, .9f, 2);
        MinecraftEntityMesh mesh = mesh(List.of(triangle(a, MinecraftEntityMesh.Coverage.CUTOUT),
                triangle(b, MinecraftEntityMesh.Coverage.CUTOUT)));
        var ranges = MinecraftVulkanEntityUploader.geometryRanges(mesh,
                material -> material.material().path().equals("a") ? hintA : hintB);
        assertEquals(2, ranges.size());
        assertEquals(hintA, ranges.getFirst().opacityMicromap());

        MinecraftEntityMesh stochastic = mesh(List.of(
                triangle(a, MinecraftEntityMesh.Coverage.STOCHASTIC),
                triangle(b, MinecraftEntityMesh.Coverage.STOCHASTIC)));
        assertEquals(1, MinecraftVulkanEntityUploader.geometryRanges(stochastic,
                material -> material.material().path().equals("a") ? hintA : hintB).size());

    }

    @Test void materialKeyPreservesOpticalProfileAndMediumBoundary() {
        var material = new MinecraftEntityMesh.Material(ResourceId.of("test", "glass"), null,
                MinecraftEntityMesh.Program.MATERIAL,
                MinecraftEntityMesh.MaterialProfile.SMOOTH_DIELECTRIC, true);
        var key = MinecraftVulkanEntityUploader.materialKey(material);
        assertEquals(dev.comfyfluffy.caustica.minecraft.material.MinecraftMaterialProfile.SMOOTH_DIELECTRIC,
                key.profile());
        assertEquals(dev.comfyfluffy.caustica.minecraft.material.MinecraftMaterialTopology.MEDIUM_BOUNDARY,
                key.topology());
    }

    @Test void cleanupContinuesAndAggregatesFailures() {
        ArrayList<Integer> closed = new ArrayList<>();
        Throwable failure = MinecraftVulkanEntityUploader.closeAll(
                () -> { closed.add(1); throw new IllegalStateException("first"); },
                () -> closed.add(2),
                () -> { closed.add(3); throw new IllegalArgumentException("third"); });

        assertEquals(List.of(1, 2, 3), closed);
        assertInstanceOf(IllegalStateException.class, failure);
        assertEquals(1, failure.getSuppressed().length);
        assertInstanceOf(IllegalArgumentException.class, failure.getSuppressed()[0]);
    }

    private static MinecraftEntityMesh mesh(List<MinecraftEntityMesh.Triangle> triangles) {
        int count = triangles.size();
        float[] positions = new float[count * 9];
        float[] uvs = new float[count * 6];
        int[] indices = new int[count * 3];
        for (int i = 0; i < indices.length; i++) indices[i] = i;
        return new MinecraftEntityMesh(positions, indices, uvs, triangles, 1);
    }

    private static MinecraftEntityMesh.Material material(String name, MinecraftEntityMesh.Program program) {
        return new MinecraftEntityMesh.Material(ResourceId.of("test", name), null, program);
    }

    private static MinecraftEntityMesh.Triangle triangle(MinecraftEntityMesh.Material material,
                                                          MinecraftEntityMesh.Coverage coverage) {
        return new MinecraftEntityMesh.Triangle(material, coverage, 0, 1, 0, 2.5f, .2f, .3f, .4f);
    }
}
