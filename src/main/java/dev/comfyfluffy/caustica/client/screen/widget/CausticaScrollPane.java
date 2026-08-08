package dev.comfyfluffy.caustica.client.screen.widget;

import dev.comfyfluffy.caustica.client.screen.CausticaTheme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractContainerWidget;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The content pane: a vertical stack of rows that scrolls and clips.
 *
 * <p>Extends {@link AbstractContainerWidget} for the scroll machinery — wheel, scrollbar drag, scroll
 * clamping and focus routing — rather than {@code AbstractSelectionList}, whose entry-selection model and
 * vanilla separators fight a bespoke layout, and which would turn every collapse into a list rebuild.
 *
 * <p>A collapsed row is hidden <em>and</em> excluded from {@link #children()}, so it takes no focus and no
 * input rather than merely painting nothing.
 */
public final class CausticaScrollPane extends AbstractContainerWidget {
    /** A row and the gap that precedes it, which is how groups are separated without spacer widgets. */
    public record Entry(AbstractWidget widget, int gapBefore) {
        public Entry(AbstractWidget widget) {
            this(widget, 0);
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private int contentHeight;

    public CausticaScrollPane() {
        super(0, 0, 0, 0, Component.empty());
    }

    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    public void setEntries(List<Entry> updated) {
        entries.clear();
        entries.addAll(updated);
        setScrollAmount(0.0);
        reflow();
    }

    /**
     * Positions every visible row top to bottom, offset by the scroll. Called after any change that can
     * alter the stack: a collapse, a section switch, or a resize.
     */
    public void reflow() {
        int y = getY() - (int) scrollAmount();
        int rowWidth = getWidth() - CausticaTheme.SCROLLBAR_WIDTH;
        int total = 0;
        for (Entry entry : entries) {
            AbstractWidget widget = entry.widget();
            if (!widget.visible) {
                continue;
            }
            y += entry.gapBefore();
            total += entry.gapBefore();
            widget.setX(getX());
            widget.setY(y);
            widget.setWidth(rowWidth);
            y += widget.getHeight();
            total += widget.getHeight();
        }
        contentHeight = total;
        refreshScrollAmount();
    }

    @Override
    protected int contentHeight() {
        return contentHeight;
    }

    @Override
    public List<? extends GuiEventListener> children() {
        return entries.stream().map(Entry::widget).filter(widget -> widget.visible).toList();
    }

    /**
     * Pointer events are rejected outside the pane before they reach a child. {@code isHovered} is computed
     * against the scissor, but hit testing is not, so a row scrolled past the edge would otherwise still
     * take a click it does not appear to be under.
     */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return isMouseOver(event.x(), event.y()) && super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return isMouseOver(mouseX, mouseY) && super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                            float partialTick) {
        int bottom = getY() + getHeight();
        graphics.enableScissor(getX(), getY(), getX() + getWidth(), bottom);
        for (Entry entry : entries) {
            AbstractWidget widget = entry.widget();
            // Skipping off-screen rows keeps a long section's cost proportional to what is visible.
            if (widget.visible && widget.getY() + widget.getHeight() >= getY() && widget.getY() <= bottom) {
                widget.extractRenderState(graphics, mouseX, mouseY, partialTick);
            }
        }
        graphics.disableScissor();
        extractScrollbar(graphics, mouseX, mouseY);
    }

    /** A flat bar, drawn rather than blitted so it matches the rest of the screen. */
    @Override
    protected void extractScrollbar(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int maximum = maxScrollAmount();
        if (maximum <= 0) {
            return;
        }
        int x = getX() + getWidth() - CausticaTheme.SCROLLBAR_WIDTH;
        graphics.fill(x, getY(), x + CausticaTheme.SCROLLBAR_WIDTH, getY() + getHeight(),
                CausticaTheme.SCROLLBAR_TRACK);
        int scrollerHeight = Math.max(16, getHeight() * getHeight() / contentHeight);
        int travel = getHeight() - scrollerHeight;
        int scrollerY = getY() + (int) (scrollAmount() / maximum * travel);
        graphics.fill(x, scrollerY, x + CausticaTheme.SCROLLBAR_WIDTH, scrollerY + scrollerHeight,
                CausticaTheme.EDGE_HIGHLIGHT);
    }

    @Override
    public void updateWidgetNarration(NarrationElementOutput output) {
        // Nothing of its own to say: the rows inside narrate themselves.
    }
}
