module caustica_minecraft_damage_bindings;

public struct MinecraftDamageModifierEntry {
    public int3 cell;
    public uint baseColorTextureIndex;
};

public struct MinecraftDamageModifierBuffer {
    public uint count;
    public uint3 reserved;
    public MinecraftDamageModifierEntry entries[8];
};

[[vk::binding(4, 2)]] public ConstantBuffer<MinecraftDamageModifierBuffer> minecraftDamageModifiers;
