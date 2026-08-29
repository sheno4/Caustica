package dev.comfyfluffy.caustica.minecraft;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.equipment.ArmorMaterials;
import net.minecraft.world.item.equipment.ArmorType;

/** Minecraft items that exercise renderer extension inputs. */
public final class CausticaItems {
    public static final Identifier SPOTLIGHT_HELMET_ID =
            Identifier.fromNamespaceAndPath("caustica", "spotlight_helmet");
    public static final ResourceKey<Item> SPOTLIGHT_HELMET_KEY =
            ResourceKey.create(Registries.ITEM, SPOTLIGHT_HELMET_ID);

    public static final Item SPOTLIGHT_HELMET = new Item(new Item.Properties()
            .setId(SPOTLIGHT_HELMET_KEY)
            .stacksTo(1)
            .humanoidArmor(ArmorMaterials.COPPER, ArmorType.HELMET));

    private CausticaItems() {
    }

    public static void register(ItemRegistrar registrar) {
        registrar.register(SPOTLIGHT_HELMET_KEY, SPOTLIGHT_HELMET);
    }

    @FunctionalInterface
    public interface ItemRegistrar {
        void register(ResourceKey<Item> key, Item item);
    }
}
