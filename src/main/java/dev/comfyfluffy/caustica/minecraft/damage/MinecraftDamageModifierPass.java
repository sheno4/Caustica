package dev.comfyfluffy.caustica.minecraft.damage;

import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.pass.CausticaRenderPass;
import dev.comfyfluffy.caustica.api.pass.PassFrame;
import dev.comfyfluffy.caustica.api.pass.PassSetup;
import dev.comfyfluffy.caustica.api.pass.RenderStage;
import dev.comfyfluffy.caustica.minecraft.entity.RtEntityTextures;
import dev.comfyfluffy.caustica.minecraft.terrain.RtTerrain;
import dev.comfyfluffy.caustica.rt.accel.GpuBuffer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Captures Minecraft block damage and publishes its projected surface-modifier inputs. */
public final class MinecraftDamageModifierPass implements CausticaRenderPass {
    public static final MinecraftDamageModifierPass INSTANCE = new MinecraftDamageModifierPass();
    public static final ResourceId ID = ResourceId.of("caustica", "minecraft_damage_modifier");
    public static final ResourceId MODIFIER_ID = ResourceId.of("caustica", "minecraft_damage");

    static final int CAPACITY = 8;
    static final int HEADER_BYTES = 16;
    static final int ENTRY_BYTES = 16;
    static final int BUFFER_BYTES = HEADER_BYTES + CAPACITY * ENTRY_BYTES;

    private volatile List<Entry> captured = List.of();
    private GpuBuffer buffer;

    private MinecraftDamageModifierPass() {
    }

    @Override
    public ResourceId id() {
        return ID;
    }

    @Override
    public RenderStage stage() {
        return RenderStage.BEFORE_TRACE;
    }

    @Override
    public void create(PassSetup setup) {
        buffer = setup.context().createBuffer(BUFFER_BYTES,
                VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                false, ID + " entries");
        setup.publishWorldResource("minecraftDamageModifiers", buffer);
    }

    /** Snapshot the host state before bindless texture uploads for this frame are flushed. */
    public void capture(ClientLevel level) {
        if (level == null) {
            captured = List.of();
            return;
        }
        ArrayList<Entry> entries = new ArrayList<>();
        for (var damage : level.destructionProgress().long2ObjectEntrySet()) {
            var progresses = damage.getValue();
            if (progresses == null || progresses.isEmpty()) {
                continue;
            }
            int stage = Mth.clamp(progresses.last().getProgress(), 0, 9);
            BlockPos position = BlockPos.of(damage.getLongKey());
            int textureIndex = RtEntityTextures.INSTANCE.slotFor(ModelBakery.DESTROY_TYPES.get(stage));
            entries.add(new Entry(position.getX(), position.getY(), position.getZ(), textureIndex));
            if (entries.size() == CAPACITY) {
                break;
            }
        }
        captured = snapshot(entries);
    }

    @Override
    public void record(PassFrame frame) {
        RtTerrain terrain = RtTerrain.currentOrNull();
        List<Entry> entries = terrain != null ? captured : List.of();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer data = stack.calloc(BUFFER_BYTES).order(ByteOrder.nativeOrder());
            writeEntries(data, entries, terrain != null ? terrain.blockX : 0,
                    terrain != null ? terrain.blockY : 0, terrain != null ? terrain.blockZ : 0);
            VK10.vkCmdUpdateBuffer(frame.commandBuffer(), buffer.handle, 0L, data);
        }
    }

    @Override
    public void destroy() {
        if (buffer != null) {
            buffer.destroy();
            buffer = null;
        }
        captured = List.of();
    }

    static List<Entry> snapshot(List<Entry> entries) {
        return List.copyOf(entries);
    }

    static void writeEntries(ByteBuffer data, List<Entry> entries, int originX, int originY, int originZ) {
        data.putInt(0, entries.size());
        for (int index = 0; index < entries.size(); index++) {
            Entry entry = entries.get(index);
            int offset = HEADER_BYTES + index * ENTRY_BYTES;
            data.putInt(offset, entry.worldX() - originX);
            data.putInt(offset + 4, entry.worldY() - originY);
            data.putInt(offset + 8, entry.worldZ() - originZ);
            data.putInt(offset + 12, entry.baseColorTextureIndex());
        }
    }

    public record Entry(int worldX, int worldY, int worldZ, int baseColorTextureIndex) {
    }
}
