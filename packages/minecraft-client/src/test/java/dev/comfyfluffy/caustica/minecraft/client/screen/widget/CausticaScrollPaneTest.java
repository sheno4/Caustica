package dev.comfyfluffy.caustica.minecraft.client.screen.widget;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;

final class CausticaScrollPaneTest {
    @Test
    void replacingRowsReleasesTheirFocusAndDrag() {
        var row = new KeyTarget();
        var pane = focusedPane(row);

        pane.setEntries(List.of(new CausticaScrollPane.Entry(new KeyTarget())));

        assertNull(pane.getFocused());
        assertFalse(row.isFocused());
        assertFalse(pane.isDragging());
        assertFalse(pane.keyPressed(new KeyEvent(GLFW_KEY_ENTER, 0, 0)));
        assertEquals(0, row.keyPresses);
    }

    @Test
    void hidingTheFocusedRowReleasesItsFocusAndDrag() {
        var row = new KeyTarget();
        var pane = focusedPane(row);

        row.visible = false;
        pane.reflow();

        assertTrue(pane.children().isEmpty());
        assertNull(pane.getFocused());
        assertFalse(row.isFocused());
        assertFalse(pane.isDragging());
        assertFalse(pane.keyPressed(new KeyEvent(GLFW_KEY_ENTER, 0, 0)));
        assertEquals(0, row.keyPresses);
    }

    @Test
    void reflowPreservesInputForARowThatRemainsVisible() {
        var row = new KeyTarget();
        var pane = focusedPane(row);

        pane.setWidth(400);
        pane.reflow();

        assertSame(row, pane.getFocused());
        assertTrue(pane.isDragging());
        assertTrue(pane.keyPressed(new KeyEvent(GLFW_KEY_ENTER, 0, 0)));
        assertEquals(1, row.keyPresses);
    }

    private static CausticaScrollPane focusedPane(KeyTarget row) {
        var pane = new CausticaScrollPane();
        pane.setWidth(300);
        pane.setHeight(100);
        pane.setEntries(List.of(new CausticaScrollPane.Entry(row)));
        pane.setFocused(row);
        pane.setDragging(true);
        return pane;
    }

    private static final class KeyTarget extends AbstractWidget {
        private int keyPresses;

        private KeyTarget() {
            super(0, 0, 0, 20, Component.empty());
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            keyPresses++;
            return true;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                                float partialTick) {
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
        }
    }
}
