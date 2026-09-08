package dev.comfyfluffy.caustica.minecraft.client.entity;

import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Supplies a resolved block state to the render-state mixin for capture through the world model set.
 * The display model set can use submission paths the RT collector does not implement.
 */
public interface ContainedBlockSource {
    void caustica$setContainedBlock(@Nullable BlockState state);
}
