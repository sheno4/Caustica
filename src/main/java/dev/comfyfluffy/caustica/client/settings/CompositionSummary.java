package dev.comfyfluffy.caustica.client.settings;

import dev.comfyfluffy.caustica.api.CausticaRegistry;
import dev.comfyfluffy.caustica.api.pass.CausticaRenderPass;
import dev.comfyfluffy.caustica.api.pass.RenderStage;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * What actually runs in a frame, at the altitude a player can act on: which passes occupy each stage, where
 * the engine's own trace and reconstruction sit between them, and which providers feed the scene.
 *
 * <p>Derived here rather than in the widget so it can be asserted without a GUI. The engine anchors are part
 * of the picture, not decoration — a stage list without them says nothing about whether a pass runs before or
 * after the world is traced, which is the thing the ordering actually means.
 */
public record CompositionSummary(List<Lane> lanes, List<ProviderRow> providers) {

    /** One row of the frame diagram: either a stage a pass can register into, or an engine-owned anchor. */
    public record Lane(Component title, List<Identifier> passes, boolean engineOwned) {
        public boolean isEmpty() {
            return passes.isEmpty();
        }
    }

    public record ProviderRow(Component title, List<Identifier> ids) {
    }

    public static CompositionSummary of(CausticaRegistry registry) {
        List<CausticaRenderPass> passes = List.copyOf(registry.renderPasses().values());
        List<Lane> lanes = new ArrayList<>();
        for (RenderStage stage : RenderStage.values()) {
            lanes.add(new Lane(stageTitle(stage), passesIn(passes, stage), false));
            for (Component anchor : anchorsAfter(stage)) {
                lanes.add(new Lane(anchor, List.of(), true));
            }
        }
        List<ProviderRow> providers = List.of(
                new ProviderRow(Component.translatable("caustica.summary.providers.scene"),
                        List.copyOf(registry.sceneProviders().keySet())),
                new ProviderRow(Component.translatable("caustica.summary.providers.light"),
                        List.copyOf(registry.lightProviders().keySet())),
                new ProviderRow(Component.translatable("caustica.summary.providers.material"),
                        List.copyOf(registry.materialSources().keySet())));
        return new CompositionSummary(List.copyOf(lanes), providers);
    }

    private static List<Identifier> passesIn(List<CausticaRenderPass> passes, RenderStage stage) {
        return passes.stream().filter(pass -> pass.stage() == stage).map(CausticaRenderPass::id).toList();
    }

    /**
     * The engine-owned work bracketed between stages. These are fixed points in the frame, not stages
     * anything can register into, so they are listed where {@code RenderStage} documents them.
     */
    private static List<Component> anchorsAfter(RenderStage stage) {
        return switch (stage) {
            case BEFORE_TRACE -> List.of(Component.translatable("caustica.summary.anchor.world_trace"));
            case AFTER_TRACE -> List.of(Component.translatable("caustica.summary.anchor.reconstruction"));
            case PRESENT -> List.of(Component.translatable("caustica.summary.anchor.swapchain"));
            default -> List.of();
        };
    }

    private static Component stageTitle(RenderStage stage) {
        return Component.translatable("caustica.stage." + stage.name().toLowerCase(java.util.Locale.ROOT));
    }
}
