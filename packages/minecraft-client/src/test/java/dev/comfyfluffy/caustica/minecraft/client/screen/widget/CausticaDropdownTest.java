package dev.comfyfluffy.caustica.minecraft.client.screen.widget;

import dev.comfyfluffy.caustica.minecraft.client.settings.SettingControl;
import net.minecraft.network.chat.Component;
import net.minecraft.client.input.KeyEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;

final class CausticaDropdownTest {
    @Test
    void keyboardChoiceIsTentativeUntilConfirmedAndWrapsAtTheEnds() {
        var control = new Choice();
        var dropdown = new CausticaDropdown<>(control, null, 0, CausticaDropdown::collapse);
        dropdown.expand(240);
        assertTrue(dropdown.keyPressedPopup(new KeyEvent(GLFW_KEY_UP, 0, 0)));
        assertTrue(dropdown.keyPressedPopup(new KeyEvent(GLFW_KEY_UP, 0, 0)));
        assertEquals(1000, control.get());
        assertTrue(dropdown.keyPressedPopup(new KeyEvent(GLFW_KEY_ENTER, 0, 0)));
        assertEquals(4000, control.get());
        assertFalse(dropdown.keyPressedPopup(new KeyEvent(GLFW_KEY_DOWN, 0, 0)));

        dropdown.expand(240);
        dropdown.keyPressedPopup(new KeyEvent(GLFW_KEY_DOWN, 0, 0));
        dropdown.keyPressedPopup(new KeyEvent(GLFW_KEY_SPACE, 0, 0));
        assertEquals(500, control.get());
    }

    @Test
    void closingDiscardsThePendingChoiceAndDisabledControlsCannotCommit() {
        var control = new Choice();
        var dropdown = new CausticaDropdown<>(control, null, 0, CausticaDropdown::collapse);
        dropdown.expand(240);
        dropdown.keyPressedPopup(new KeyEvent(GLFW_KEY_DOWN, 0, 0));
        dropdown.collapse();
        dropdown.expand(240);
        dropdown.keyPressedPopup(new KeyEvent(GLFW_KEY_ENTER, 0, 0));
        assertEquals(1000, control.get());
        dropdown.expand(240);
        dropdown.keyPressedPopup(new KeyEvent(GLFW_KEY_DOWN, 0, 0));
        control.enabled = false;
        assertFalse(dropdown.keyPressedPopup(new KeyEvent(GLFW_KEY_ENTER, 0, 0)));
        assertEquals(1000, control.get());
    }

    @Test
    void bottomRowOpensAboveAndEveryChoiceRemainsClickable() {
        var control = new Choice();
        var dropdown = new CausticaDropdown<>(control, null, 0, ignored -> {});
        dropdown.setWidth(300);
        dropdown.setY(200);
        dropdown.expand(240);

        assertFalse(dropdown.clickPopup(200, 225));
        for (int index = 0; index < control.choices().size(); index++) {
            assertTrue(dropdown.clickPopup(200, 145 + index * 12));
            assertEquals(control.choices().get(index), control.get());
        }
    }

    @Test
    void rowWithSpaceBelowKeepsItsPopupBelow() {
        var control = new Choice();
        var dropdown = new CausticaDropdown<>(control, null, 0, ignored -> {});
        dropdown.setWidth(300);
        dropdown.setY(40);
        dropdown.expand(240);

        assertFalse(dropdown.clickPopup(200, 30));
        assertTrue(dropdown.clickPopup(200, 115));
        assertEquals(4000, control.get());
    }

    private static final class Choice implements SettingControl.ChoiceControl<Integer> {
        private int value = 1000;
        private boolean enabled = true;
        @Override public String id() { return "test"; }
        @Override public Component label() { return Component.empty(); }
        @Override public Component tooltip() { return null; }
        @Override public boolean enabled() { return enabled; }
        @Override public List<Integer> choices() { return List.of(500, 1000, 1500, 2000, 4000); }
        @Override public Integer get() { return value; }
        @Override public void set(Integer value) { this.value = value; }
        @Override public Integer defaultValue() { return 1000; }
        @Override public Component labelOf(Integer value) { return Component.literal(value.toString()); }
    }
}
