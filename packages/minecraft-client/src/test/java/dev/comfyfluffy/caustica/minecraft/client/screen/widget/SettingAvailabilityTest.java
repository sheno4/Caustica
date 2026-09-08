package dev.comfyfluffy.caustica.minecraft.client.screen.widget;

import dev.comfyfluffy.caustica.minecraft.client.settings.SettingControl;
import dev.comfyfluffy.caustica.minecraft.client.settings.SettingGroup;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class SettingAvailabilityTest {
    @Test
    void toggleAndGroupHeaderFollowCurrentAvailability() {
        var control = new Bool();
        var changes = new AtomicInteger();
        var toggle = new CausticaToggle(control, null, 0, changes::incrementAndGet);
        var header = new CausticaGroupHeader(
                new SettingGroup("test", Component.empty(), control, List.of()),
                null, 0, changes::incrementAndGet);
        assertFalse(toggle.isActive());
        assertFalse(header.isActive());
        toggle.onPress(null);
        header.onPress(null);
        assertEquals(0, changes.get());
        control.enabled = true;
        assertTrue(toggle.isActive());
        assertTrue(header.isActive());
        toggle.onPress(null);
        assertTrue(control.value);
        header.onPress(null);
        assertFalse(control.value);
        assertEquals(2, changes.get());
        control.enabled = false;
        header.onPress(null);
        assertEquals(2, changes.get());
    }

    @Test
    void popupCannotSelectWhenItsControlBecomesUnavailable() {
        var enabled = new java.util.concurrent.atomic.AtomicBoolean(false);
        var selections = new AtomicInteger();
        var control = new SettingControl.ChoiceControl<Integer>() {
            @Override public String id() { return "test"; }
            @Override public Component label() { return Component.empty(); }
            @Override public Component tooltip() { return Component.empty(); }
            @Override public boolean enabled() { return enabled.get(); }
            @Override public List<Integer> choices() { return List.of(0, 1); }
            @Override public Integer get() { return 0; }
            @Override public void set(Integer value) { selections.incrementAndGet(); }
            @Override public Integer defaultValue() { return 0; }
            @Override public Component labelOf(Integer value) { return Component.empty(); }
        };
        var dropdown = new CausticaDropdown<>(control, null, 0, ignored -> {});
        dropdown.setWidth(200);
        assertFalse(dropdown.isActive());
        enabled.set(true);
        assertTrue(dropdown.isActive());
        assertTrue(dropdown.clickPopup(100, dropdown.getHeight() + 2));
        assertEquals(1, selections.get());
        enabled.set(false);
        assertFalse(dropdown.clickPopup(100, dropdown.getHeight() + 2));
        assertEquals(1, selections.get());
    }

    private static final class Bool implements SettingControl.BoolControl {
        boolean value;
        boolean enabled;
        @Override public String id() { return "test"; }
        @Override public Component label() { return Component.empty(); }
        @Override public Component tooltip() { return Component.empty(); }
        @Override public boolean enabled() { return enabled; }
        @Override public boolean get() { return value; }
        @Override public void set(boolean value) { this.value = value; }
        @Override public boolean defaultValue() { return false; }
    }
}
