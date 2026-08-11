package dev.comfyfluffy.caustica.minecraft.damage;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftDamageModifierPassTest {
    @Test
    void packsCountRebasedCellsAndTextureIndicesIntoTheShaderLayout() {
        ByteBuffer data = ByteBuffer.allocateDirect(MinecraftDamageModifierPass.BUFFER_BYTES)
                .order(ByteOrder.nativeOrder());
        MinecraftDamageModifierPass.writeEntries(data, List.of(
                new MinecraftDamageModifierPass.Entry(101, 22, -7, 31),
                new MinecraftDamageModifierPass.Entry(95, 18, 4, 47)), 100, 20, -5);

        assertEquals(2, data.getInt(0));
        assertEquals(1, data.getInt(MinecraftDamageModifierPass.HEADER_BYTES));
        assertEquals(2, data.getInt(MinecraftDamageModifierPass.HEADER_BYTES + 4));
        assertEquals(-2, data.getInt(MinecraftDamageModifierPass.HEADER_BYTES + 8));
        assertEquals(31, data.getInt(MinecraftDamageModifierPass.HEADER_BYTES + 12));
        int second = MinecraftDamageModifierPass.HEADER_BYTES + MinecraftDamageModifierPass.ENTRY_BYTES;
        assertEquals(-5, data.getInt(second));
        assertEquals(-2, data.getInt(second + 4));
        assertEquals(9, data.getInt(second + 8));
        assertEquals(47, data.getInt(second + 12));
        assertEquals(0, data.getInt(second + MinecraftDamageModifierPass.ENTRY_BYTES));
    }

    @Test
    void captureSnapshotDoesNotAliasTheMutableInput() {
        ArrayList<MinecraftDamageModifierPass.Entry> entries = new ArrayList<>();
        entries.add(new MinecraftDamageModifierPass.Entry(1, 2, 3, 4));
        List<MinecraftDamageModifierPass.Entry> snapshot = MinecraftDamageModifierPass.snapshot(entries);
        entries.clear();

        assertEquals(1, snapshot.size());
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
    }

    @Test
    void modifierUsesOnlyPublicTextureResourcesAndAttenuatesEmission() throws Exception {
        String shader = Files.readString(Path.of("src", "main", "resources", "caustica", "shaders",
                "minecraft", "modifier", "caustica_minecraft_damage_modifier.slang"));
        assertTrue(shader.contains("import caustica_surface_modifier;"));
        assertTrue(shader.contains("import caustica_texture_resources;"));
        assertTrue(shader.contains("material.baseColor = clamp(material.baseColor * factor"));
        assertTrue(shader.contains("material.emissionColor = clamp(material.emissionColor * factor"));
        assertFalse(shader.contains("import bindings;"));
        assertFalse(shader.contains("import world_"));
    }

    @Test
    void rendererCoreContainsNoMinecraftDamagePolicy() throws Exception {
        Path root = Path.of("src", "main").toAbsolutePath().normalize();
        String frame = Files.readString(root.resolve(
                "java/dev/comfyfluffy/caustica/engine/frame/FrameSnapshot.java"));
        String composite = Files.readString(root.resolve(
                "java/dev/comfyfluffy/caustica/rt/RtComposite.java"));
        String common = Files.readString(root.resolve(
                "resources/caustica/shaders/world/world_common.slang"));
        String closestHit = Files.readString(root.resolve(
                "resources/caustica/shaders/world/closest_hit.slang"));
        String content = frame + composite + common + closestHit;
        for (String removed : List.of("DamageOverlay", "BreakEntry", "breakCount",
                "breakingEntries", "applyBreaking", "damageOverlays")) {
            assertFalse(content.contains(removed), "renderer core retains damage policy: " + removed);
        }
    }
}
