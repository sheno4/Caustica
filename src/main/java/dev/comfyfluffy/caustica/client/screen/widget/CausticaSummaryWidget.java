package dev.comfyfluffy.caustica.client.screen.widget;

import dev.comfyfluffy.caustica.api.provider.ProviderId;
import dev.comfyfluffy.caustica.client.screen.CausticaTheme;
import dev.comfyfluffy.caustica.client.settings.CompositionSummary;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * The frame diagram: stages down the left, the passes occupying each on the right, with the engine's own
 * trace and reconstruction marked where they sit between them.
 *
 * <p>Read-only, so it is a plain {@link AbstractWidget} rather than a button — it takes no input and holds no
 * focus. An empty stage is drawn rather than skipped: the gaps are what show where an extension could add
 * something.
 */
public final class CausticaSummaryWidget extends AbstractWidget {
    private static final int LANE_HEIGHT = 11;
    private static final int SECTION_GAP = 8;
    private static final int LABEL_WIDTH = 104;

    private final CompositionSummary summary;
    private final Font font;
    private final int accent;

    public CausticaSummaryWidget(CompositionSummary summary, Font font, int accent) {
        super(0, 0, 0, 0, Component.translatable("caustica.summary.title"));
        this.summary = summary;
        this.font = font;
        this.accent = accent;
        setHeight(measuredHeight());
    }

    private int measuredHeight() {
        return LANE_HEIGHT
                + summary.lanes().size() * LANE_HEIGHT
                + SECTION_GAP + LANE_HEIGHT
                + summary.providers().size() * LANE_HEIGHT
                + SECTION_GAP;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                            float partialTick) {
        int x = getX() + CausticaTheme.CONTENT_PAD;
        int valueX = x + LABEL_WIDTH;
        int right = getX() + getWidth() - CausticaTheme.CONTENT_PAD;
        int y = getY();

        graphics.text(font, getMessage(), x, y, CausticaTheme.TEXT_DISABLED, false);
        y += LANE_HEIGHT;

        for (CompositionSummary.Lane lane : summary.lanes()) {
            if (lane.engineOwned()) {
                // Engine anchors read as structure, not as something to act on: dimmed, italic-ish by
                // bracketing, and with a rule through the row to mark a fixed point in the frame.
                graphics.fill(x, y + font.lineHeight / 2, right, y + font.lineHeight / 2 + 1,
                        CausticaTheme.DIVIDER);
                Component bracketed = Component.literal("  ").append(lane.title()).append("  ");
                int width = font.width(bracketed);
                graphics.fill(x, y, x + width, y + font.lineHeight, CausticaTheme.CONTENT_BODY);
                graphics.text(font, bracketed, x, y, CausticaTheme.TEXT_DISABLED, false);
            } else {
                int marker = lane.isEmpty() ? CausticaTheme.TOGGLE_OFF : accent;
                graphics.fill(x, y + 3, x + 3, y + 6, marker);
                graphics.text(font, lane.title(), x + 8, y,
                        lane.isEmpty() ? CausticaTheme.TEXT_DISABLED : CausticaTheme.TEXT_SECONDARY, false);
                graphics.text(font, joinedPasses(lane.passes()), valueX, y,
                        lane.isEmpty() ? CausticaTheme.TEXT_DISABLED : CausticaTheme.TEXT_PRIMARY, false);
            }
            y += LANE_HEIGHT;
        }

        y += SECTION_GAP;
        graphics.text(font, Component.translatable("caustica.summary.providers"), x, y,
                CausticaTheme.TEXT_DISABLED, false);
        y += LANE_HEIGHT;
        for (CompositionSummary.ProviderRow row : summary.providers()) {
            graphics.text(font, row.title(), x + 8, y, CausticaTheme.TEXT_SECONDARY, false);
            graphics.text(font, joinedProviders(row.ids()), valueX, y, CausticaTheme.TEXT_PRIMARY, false);
            y += LANE_HEIGHT;
        }
    }

    /** Namespace dropped: every id on this page is Caustica's until a third extension exists to disambiguate. */
    private static Component joinedPasses(List<Identifier> ids) {
        if (ids.isEmpty()) {
            return Component.translatable("caustica.summary.none");
        }
        return Component.literal(String.join(", ", ids.stream().map(Identifier::getPath).toList()));
    }

    private static Component joinedProviders(List<ProviderId> ids) {
        if (ids.isEmpty()) {
            return Component.translatable("caustica.summary.none");
        }
        return Component.literal(String.join(", ", ids.stream().map(ProviderId::path).toList()));
    }

    @Override
    public void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, getMessage());
    }
}
