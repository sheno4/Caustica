package dev.comfyfluffy.caustica.rt.material;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtMaterialClassificationImportFirewallTest {
    private static final Path MATERIAL_SOURCES = Path.of("src", "main", "java", "dev", "comfyfluffy",
            "caustica", "rt", "material").toAbsolutePath().normalize();

    @Test
    void minecraftClassifiersDoNotLiveInRendererMaterialPackage() throws IOException {
        for (String removed : new String[]{"RtMaterials.java", "RtDielectrics.java", "RtEmissionSemantics.java",
                "RtLabPbr.java", "RtEmissionHeuristic.java"}) {
            assertFalse(Files.exists(MATERIAL_SOURCES.resolve(removed)),
                    () -> removed + " must remain in the Minecraft adapter");
        }

        for (String renderer : new String[]{"RtMaterialRegistry.java", "RtMaterialPageCompiler.java"}) {
            String text = Files.readString(MATERIAL_SOURCES.resolve(renderer));
            for (String forbidden : List.of("net.minecraft.", "net.fabricmc.", "com.mojang.",
                    "dev.comfyfluffy.caustica.minecraft.", "TextureAtlasSprite", "NativeImage",
                    "Identifier", "SpriteContentsAccessor", "TextureAtlasAccessor",
                    "labPbrSpecular", "labPbrNormal", "inferEmissionMask", "RtLabPbr",
                    "RtEmissionHeuristic", "RtLookPackage", "defaultEmissionLuminanceCdM2()",
                    "lavaId", "defaultUniformEmissionId", "defaultUniformEmissionAsset",
                    "MODEL_OPAQUE", "MODEL_DIELECTRIC", "VARIANT_GLASS", "compileParticleDesc",
                    "particleId")) {
                assertFalse(text.contains(forbidden),
                        () -> renderer + " crossed its host type firewall with " + forbidden);
            }
            assertFalse(text.contains("RtMaterials") || text.contains("RtDielectrics")
                            || text.contains("RtEmissionSemantics"),
                    () -> renderer + " references a removed Minecraft classifier");
        }
    }

    @Test
    void canonicalPageCompilerHasSourceNeutralOwnershipAndVocabulary() throws IOException {
        Path compiler = MATERIAL_SOURCES.resolve("RtMaterialPageCompiler.java");
        assertTrue(Files.isRegularFile(compiler));
        assertFalse(Files.exists(MATERIAL_SOURCES.resolve("RtBlockMaterials.java")));

        String source = Files.readString(compiler);
        Pattern producerVocabulary = Pattern.compile(
                "\\b(minecraft|vanilla|block|terrain|entity|lava|glowstone|section)\\b",
                Pattern.CASE_INSENSITIVE);
        assertFalse(producerVocabulary.matcher(source).find(),
                "canonical material page compiler contains host-producer vocabulary");
    }
}
