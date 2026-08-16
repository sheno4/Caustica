package dev.comfyfluffy.caustica.minecraft.damage;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

}
