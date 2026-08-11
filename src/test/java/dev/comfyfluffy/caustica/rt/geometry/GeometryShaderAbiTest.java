package dev.comfyfluffy.caustica.rt.geometry;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GeometryShaderAbiTest {
    private static final List<String> REMOVED_IDENTIFIERS = List.of(
            "primAddr", "idxAddr", "uvAddr", "dispAddr", "rigidDisp", "triBase",
            "TERRAIN_PRIM_IN_LIGHT_BUFFER", "terrainBinding", "terrainMat", "entityO2w",
            "entityLod", "entityTexel", "blockSlot", "blockLod", "blockTexel",
            "entityAlbedoTex", "blockAlbedoAtlas", "albedoTextures", "albedoSlot",
            "bindingAlbedoSlot", "albedoUv");

    @Test
    void geometryShadersUseExplicitProducerNeutralAddressing() throws IOException {
        Path world = Path.of("src", "main", "resources", "caustica", "shaders", "world")
                .toAbsolutePath().normalize();
        List<Path> sources = List.of(world.resolve("world_common.slang"),
                world.resolve("any_hit_common.slang"), world.resolve("closest_hit.slang"),
                world.resolve("bindings.slang"));
        String content = read(sources);

        for (String identifier : REMOVED_IDENTIFIERS) {
            assertFalse(content.contains(identifier), "removed geometry identifier remains: " + identifier);
        }
        assertFalse(content.matches("(?is).*\\b(terrain|entity)\\b.*"),
                "geometry hit shaders must not name source producers");
        assertFalse(content.matches("(?s).*indexAddress\\s*[!=]=\\s*0.*"),
                "geometry addressing must not be inferred from the index pointer");
        assertTrue(content.contains("GEOMETRY_INDEXED_TEXTURE_COORDINATES = 1u"));
        assertTrue(content.contains("(g.flags & GEOMETRY_INDEXED_TEXTURE_COORDINATES) != 0u"));
        assertTrue(content.contains("public uint64_t primitiveAddress"));
        assertTrue(content.contains("public uint64_t textureCoordinateAddress"));
        assertTrue(content.contains("public uint triangleBase[3]"));
        assertTrue(content.contains("[[vk::binding(0, 1)]] public Sampler2D baseColorTextures[]"));
        assertTrue(content.contains("bindingBaseColorTextureIndex"));
        assertTrue(content.contains("public float4 baseColorUv"));
        assertFalse(content.contains("[[vk::binding(2, 0)]]"), "retired set-0 binding 2 must remain a hole");
    }

    @Test
    void everyGeometryTableWriterDeclaresItsTextureCoordinateMode() throws IOException {
        Path java = Path.of("src", "main", "java", "dev", "comfyfluffy", "caustica")
                .toAbsolutePath().normalize();
        String indexedWriters = read(List.of(java.resolve("rt/geometry/RtSceneGeometryManager.java"),
                java.resolve("minecraft/entity/RtEntities.java")));
        String directWriter = Files.readString(java.resolve("rt/geometry/RtRetainedGeometryScene.java"));

        assertTrue(indexedWriters.contains("RtGeometryAbi.FLAG_INDEXED_TEXTURE_COORDINATES"));
        assertTrue(directWriter.contains("RtGeometryAbi.FLAG_TRIANGLE_CORNER_TEXTURE_COORDINATES"));
    }

    @Test
    void primitiveEmissionLaneContainsOnlyEmission() throws IOException {
        Path root = Path.of("src", "main").toAbsolutePath().normalize();
        String mesher = Files.readString(root.resolve(
                "java/dev/comfyfluffy/caustica/minecraft/terrain/RtTerrainMesher.java"));
        String collector = Files.readString(root.resolve(
                "java/dev/comfyfluffy/caustica/minecraft/terrain/RtLightCollector.java"));
        String closestHit = Files.readString(root.resolve(
                "resources/caustica/shaders/world/closest_hit.slang"));

        assertTrue(mesher.contains("prim.add(q.emission);"));
        assertTrue(collector.contains("float stateEmission = p[pb + 3];"));
        assertTrue(closestHit.contains("float stateEmission = pr.normal.w;"));
        assertFalse(mesher.contains("q.emission + 2"));
        assertFalse(collector.contains("ew >= 1.5"));
        assertFalse(closestHit.contains("ew >= 1.5"));
    }

    private static String read(List<Path> sources) throws IOException {
        StringBuilder content = new StringBuilder();
        for (Path source : sources) {
            assertTrue(Files.isRegularFile(source), "missing geometry shader source: " + source);
            content.append(Files.readString(source)).append('\n');
        }
        return content.toString();
    }
}
