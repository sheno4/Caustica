package dev.comfyfluffy.caustica.minecraft.client.screen.widget;

import dev.comfyfluffy.caustica.minecraft.client.screen.CausticaTheme;
import dev.comfyfluffy.caustica.minecraft.client.settings.SettingControl;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.PreeditEvent;

/** A labelled text row whose editor receives the scroll pane's focus and input events. */
public final class CausticaTextField extends AbstractWidget {
    private final SettingControl.TextControl control;
    private final Font font;
    private final int accent;
    private final EditBox editor;

    public CausticaTextField(SettingControl.TextControl control, Font font, int accent) {
        super(0, 0, 0, CausticaTheme.ROW_HEIGHT * 2, control.label());
        this.control = control;
        this.font = font;
        this.accent = accent;
        editor = new EditBox(font, 1, CausticaTheme.ROW_HEIGHT - 2, control.label());
        editor.setMaxLength(4096);
        editor.setValue(control.get());
        editor.setCursorPosition(0);
        editor.setResponder(control::set);
        editor.setTextColor(CausticaTheme.TEXT_VALUE);
        editor.setTextColorUneditable(CausticaTheme.TEXT_DISABLED);
        setTooltip(Tooltip.create(control.tooltip()));
        layoutEditor();
    }

    private void layoutEditor() {
        editor.setX(getX() + CausticaTheme.CONTENT_PAD);
        editor.setY(getY() + CausticaTheme.ROW_HEIGHT);
        editor.setWidth(Math.max(1, getWidth() - CausticaTheme.CONTENT_PAD * 2));
    }

    @Override
    public void setX(int x) {
        super.setX(x);
        layoutEditor();
    }

    @Override
    public void setY(int y) {
        super.setY(y);
        layoutEditor();
    }

    @Override
    public void setWidth(int width) {
        super.setWidth(width);
        layoutEditor();
    }

    @Override
    public boolean isActive() {
        return super.isActive() && control.enabled();
    }

    @Override
    public void setFocused(boolean focused) {
        super.setFocused(focused);
        editor.setFocused(focused);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return isActive() && editor.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        return isActive() && editor.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        return editor.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        return isActive() && editor.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return isActive() && editor.charTyped(event);
    }

    @Override
    public boolean preeditUpdated(PreeditEvent event) {
        return isActive() && editor.preeditUpdated(event);
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                            float partialTick) {
        boolean enabled = isActive();
        editor.active = enabled;
        editor.setEditable(enabled);
        if (!isFocused() && !editor.getValue().equals(control.get())) editor.setValue(control.get());
        if (isHovered && enabled) {
            graphics.fill(getX(), getY(), getRight(), getBottom(), CausticaTheme.ROW_HOVER);
        }
        int textY = CausticaPaint.textBaseline(font, getY(), CausticaTheme.ROW_HEIGHT);
        CausticaPaint.textClipped(graphics, font, control.label(), getX() + CausticaTheme.CONTENT_PAD,
                textY, getWidth() - CausticaTheme.CONTENT_PAD * 2,
                CausticaTheme.textColour(enabled, isHovered || isFocused()));
        editor.extractRenderState(graphics, mouseX, mouseY, partialTick);
        if (isFocused()) {
            CausticaPaint.focusRing(graphics, editor.getX(), editor.getY(), editor.getWidth(),
                    editor.getHeight(), accent);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, control.label());
        editor.updateWidgetNarration(output);
    }
}
