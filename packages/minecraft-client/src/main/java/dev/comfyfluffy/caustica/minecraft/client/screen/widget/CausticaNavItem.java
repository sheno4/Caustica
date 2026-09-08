package dev.comfyfluffy.caustica.minecraft.client.screen.widget;

import dev.comfyfluffy.caustica.minecraft.client.screen.CausticaTheme;
import dev.comfyfluffy.caustica.minecraft.client.settings.SettingsSection;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;

/**
 * A sidebar entry. The selected one is marked by a bar in its section's accent — position and hue, never
 * brightness, since the UI is composited at a fixed nit level and cannot rely on being brighter.
 */
public final class CausticaNavItem extends AbstractButton {
    private final SettingsSection section;
    private final Font font;
    private final BooleanSupplier selected;
    private final Runnable onSelected;

    public CausticaNavItem(SettingsSection section, Font font, BooleanSupplier selected,
                           Runnable onSelected) {
        super(0, 0, 0, CausticaTheme.NAV_ITEM_HEIGHT, section.title());
        this.section = section;
        this.font = font;
        this.selected = selected;
        this.onSelected = onSelected;
    }

    @Override
    public void onPress(InputWithModifiers input) {
        onSelected.run();
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        boolean isSelected = selected.getAsBoolean();
        int right = getX() + getWidth();
        int textY = CausticaPaint.textBaseline(font, getY(), getHeight());

        if (isSelected) {
            graphics.fill(getX(), getY(), right, getY() + getHeight(), CausticaTheme.CONTENT_BODY);
            graphics.fill(getX(), getY(), getX() + CausticaTheme.ACCENT_BAR_WIDTH, getY() + getHeight(),
                    section.accent());
        } else if (isHovered) {
            graphics.fill(getX(), getY(), right, getY() + getHeight(), CausticaTheme.ROW_HOVER);
        }

        int labelX = getX() + CausticaTheme.ACCENT_BAR_WIDTH + 6;
        CausticaPaint.textClipped(graphics, font, section.title(), labelX, textY, right - labelX - 4,
                CausticaTheme.textColour(true, isSelected || isHovered));

        if (isFocused()) {
            CausticaPaint.focusRing(graphics, getX() + 1, getY() + 1, getWidth() - 2, getHeight() - 2,
                    section.accent());
        }
        handleCursor(graphics);
    }

    @Override
    public void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, section.title());
        if (selected.getAsBoolean()) {
            output.add(NarratedElementType.USAGE, Component.translatable("narration.selected"));
        }
    }
}
