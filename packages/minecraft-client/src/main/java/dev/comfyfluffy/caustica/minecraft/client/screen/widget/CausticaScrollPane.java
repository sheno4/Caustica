package dev.comfyfluffy.caustica.minecraft.client.screen.widget;

import dev.comfyfluffy.caustica.minecraft.client.screen.CausticaTheme;
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
 * A scrolling, clipped stack of settings rows. Vanilla supplies scrolling and focus routing.
 *
 * <p>Collapsed rows are excluded from {@link #children()}. Reflow releases focus and drag state when their
 * target leaves the visible layout.
 */
public final class CausticaScrollPane extends AbstractContainerWidget {
    /** A row and the gap that precedes it, which is how groups are separated without spacer widgets. */
    public record Entry(AbstractWidget widget, int gapBefore) {
        public Entry(AbstractWidget widget) {
            this(widget, 0);
        }
    }

    /** A visible row and its Y in content space, before the scroll offset is applied. */
    private record Placed(AbstractWidget widget, int contentY) {
    }

    private final List<Entry> entries = new ArrayList<>();
    private final List<Placed> placed = new ArrayList<>();
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
     * Measures the stack: assigns each visible row its width and its Y in content space. Called after any
     * change that can alter the stack — a collapse, a section switch, or a resize — but <em>not</em> on
     * scroll, which only shifts where those rows land.
     */
    public void reflow() {
        placed.clear();
        int rowWidth = getWidth() - CausticaTheme.SCROLLBAR_WIDTH;
        int y = 0;
        for (Entry entry : entries) {
            AbstractWidget widget = entry.widget();
            if (!widget.visible) {
                continue;
            }
            y += entry.gapBefore();
            widget.setX(getX());
            widget.setWidth(rowWidth);
            placed.add(new Placed(widget, y));
            y += widget.getHeight();
        }
        contentHeight = y;
        GuiEventListener focused = getFocused();
        if (focused != null && placed.stream().noneMatch(row -> row.widget() == focused)) {
            setFocused(null);
            setDragging(false);
        }
        refreshScrollAmount();
        applyScroll();
    }

    /**
     * Applies the scroll offset to measured row positions without recalculating the stack layout.
     */
    private void applyScroll() {
        int top = getY() - (int) scrollAmount();
        for (Placed row : placed) {
            row.widget().setY(top + row.contentY());
        }
    }

    @Override
    protected int contentHeight() {
        return contentHeight;
    }

    @Override
    protected double scrollRate() {
        // AbstractContainerWidget's convenience constructor installs a zero wheel rate.
        return CausticaTheme.ROW_HEIGHT;
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
        if (!isMouseOver(mouseX, mouseY) || !super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) {
            return false;
        }
        applyScroll();
        return true;
    }

    /** Dragging the scrollbar moves the rows in the same step, not at the next unrelated re-measure. */
    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        boolean handled = super.mouseDragged(event, dragX, dragY);
        applyScroll();
        return handled;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                            float partialTick) {
        int bottom = getY() + getHeight();
        // Placement is re-applied here so a scroll from any source lands before the rows are drawn.
        applyScroll();
        graphics.enableScissor(getX(), getY(), getX() + getWidth(), bottom);
        for (Placed row : placed) {
            AbstractWidget widget = row.widget();
            // Skipping off-screen rows keeps a long section's cost proportional to what is visible.
            if (widget.getY() + widget.getHeight() >= getY() && widget.getY() <= bottom) {
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
