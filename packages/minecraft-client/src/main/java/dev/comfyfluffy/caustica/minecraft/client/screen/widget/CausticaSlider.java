package dev.comfyfluffy.caustica.minecraft.client.screen.widget;

import dev.comfyfluffy.caustica.minecraft.client.screen.CausticaTheme;
import dev.comfyfluffy.caustica.minecraft.client.settings.SettingControl;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * A numeric preference drawn as a label, track and value. Vanilla supplies focus, keyboard editing and
 * drag delivery; pointer input maps to the track rather than the full row.
 */
public final class CausticaSlider extends AbstractSliderButton {
    private final SettingControl.RangeControl control;
    private final Font font;
    private final int accent;

    public CausticaSlider(SettingControl.RangeControl control, Font font, int accent) {
        super(0, 0, 0, CausticaTheme.ROW_HEIGHT, Component.empty(), 0.0);
        this.control = control;
        this.font = font;
        this.accent = accent;
        refreshValue();
        if (control.tooltip() != null) {
            setTooltip(net.minecraft.client.gui.components.Tooltip.create(control.tooltip()));
        }
    }

    @Override
    public boolean isActive() {
        return super.isActive() && control.enabled();
    }

    @Override
    protected void updateMessage() {
        // The value is painted in its own column, so the base class's message is unused.
    }

    @Override
    protected void applyValue() {
        control.set(control.fromSlider(value));
        refreshValue();
    }

    /** The base widget's normalized position mirrors the preference, including quantized writes. */
    private void refreshValue() {
        value = control.toSlider(control.get());
    }

    /**
     * Stepped controls advance by one declared step; continuous controls use vanilla keyboard increments.
     */
    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!isActive()) return false;
        refreshValue();
        if (canChangeValue && control.step() > 0.0 && (event.isLeft() || event.isRight())) {
            double span = control.sliderMaximum() - control.sliderMinimum();
            double delta = control.step() / span * (event.isLeft() ? -1.0 : 1.0);
            setValue(value + delta);
            return true;
        }
        return super.keyPressed(event);
    }

    /** Left edge of the track. The row is much wider, and only this part is the control. */
    private int trackLeft() {
        return getX() + getWidth() - CausticaTheme.VALUE_WIDTH - CausticaTheme.SLIDER_WIDTH
                - CausticaTheme.GROUP_GAP;
    }

    /**
     * The base class maps the pointer across the whole widget, which here is the entire row: clicking the
     * label would set a value, and the knob would never sit under the cursor. Both the hit test and the
     * mapping use the track instead.
     */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.x() < trackLeft()) {
            return false;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        setValueFromMouse(event.x());
    }

    @Override
    protected void onDrag(MouseButtonEvent event, double dragX, double dragY) {
        setValueFromMouse(event.x());
    }

    /** Pointer mapping uses the painted knob's travel; stepped preferences snap to their declared grid. */
    private void setValueFromMouse(double mouseX) {
        if (!isActive()) return;
        refreshValue();
        double travel = CausticaTheme.SLIDER_WIDTH - CausticaTheme.KNOB_WIDTH;
        setValue((mouseX - trackLeft() - CausticaTheme.KNOB_WIDTH / 2.0) / travel);
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        refreshValue();
        boolean enabled = control.enabled();
        int rowRight = getX() + getWidth();
        int trackLeft = trackLeft();
        int textY = CausticaPaint.textBaseline(font, getY(), getHeight());

        if (isHovered && enabled) {
            graphics.fill(getX(), getY(), rowRight, getY() + getHeight(), CausticaTheme.ROW_HOVER);
        }

        int labelColour = CausticaTheme.textColour(enabled, isHovered || isFocused());
        CausticaPaint.textClipped(graphics, font, control.label(), getX() + CausticaTheme.CONTENT_PAD, textY,
                trackLeft - getX() - CausticaTheme.CONTENT_PAD * 2, labelColour);
        int labelEnd = getX() + CausticaTheme.CONTENT_PAD + Math.min(font.width(control.label()),
                trackLeft - getX() - CausticaTheme.CONTENT_PAD * 2);
        CausticaPaint.dotLeader(graphics, labelEnd + 4, trackLeft - 4, textY + font.lineHeight / 2);

        int trackY = getY() + (getHeight() - CausticaTheme.TRACK_HEIGHT) / 2;
        graphics.fill(trackLeft, trackY, trackLeft + CausticaTheme.SLIDER_WIDTH,
                trackY + CausticaTheme.TRACK_HEIGHT, CausticaTheme.TRACK);
        int filled = (int) Math.round(value * (CausticaTheme.SLIDER_WIDTH - CausticaTheme.KNOB_WIDTH));
        // The base class tracks keyboard editing separately from focus and pointer dragging.
        int fillColour = enabled
                ? (canChangeValue ? CausticaTheme.active(accent) : accent)
                : CausticaTheme.dimmed(accent);
        if (filled > 0) {
            graphics.fill(trackLeft, trackY, trackLeft + filled, trackY + CausticaTheme.TRACK_HEIGHT, fillColour);
        }

        int knobX = trackLeft + filled;
        int knobY = getY() + (getHeight() - CausticaTheme.KNOB_HEIGHT) / 2;
        int knobColour = enabled
                ? (isHovered || isFocused() ? CausticaTheme.KNOB_HOVER : CausticaTheme.KNOB)
                : CausticaTheme.dimmed(CausticaTheme.KNOB);
        graphics.fill(knobX, knobY, knobX + CausticaTheme.KNOB_WIDTH, knobY + CausticaTheme.KNOB_HEIGHT, knobColour);

        CausticaPaint.textRight(graphics, font, control.format(control.get()), rowRight, textY,
                enabled ? CausticaTheme.TEXT_VALUE : CausticaTheme.TEXT_DISABLED);

        if (isFocused()) {
            CausticaPaint.focusRing(graphics, trackLeft, knobY, CausticaTheme.SLIDER_WIDTH,
                    CausticaTheme.KNOB_HEIGHT, accent);
        }
        handleCursor(graphics);
    }

    @Override
    public void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, control.label());
        output.add(NarratedElementType.USAGE, control.format(control.get()));
    }
}
