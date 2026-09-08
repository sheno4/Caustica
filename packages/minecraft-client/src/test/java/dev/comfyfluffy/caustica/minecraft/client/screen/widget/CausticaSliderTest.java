package dev.comfyfluffy.caustica.minecraft.client.screen.widget;

import dev.comfyfluffy.caustica.minecraft.client.settings.SettingControl;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;

final class CausticaSliderTest {
    @Test
    void availabilityCanChangeAfterConstructionAndDuringDragging() {
        var control = new Range();
        control.enabled = false;
        var slider = new CausticaSlider(control, null, 0);
        slider.setWidth(300);
        assertFalse(slider.isActive());
        control.enabled = true;
        assertTrue(slider.isActive());
        var pointer = new MouseButtonEvent(300, 5, new MouseButtonInfo(0, 0));
        slider.onClick(pointer, false);
        assertEquals(10, control.value);
        control.enabled = false;
        slider.onDrag(new MouseButtonEvent(0, 5, new MouseButtonInfo(0, 0)), -300, 0);
        assertEquals(10, control.value);
    }

    @Test
    void steppedKeysRespectEditingStateAndAvailability() {
        var control = new Range();
        var slider = new CausticaSlider(control, null, 0);
        var right = new KeyEvent(GLFW_KEY_RIGHT, 0, 0);
        var select = new KeyEvent(GLFW_KEY_ENTER, 0, 0);

        assertFalse(slider.keyPressed(right));
        assertEquals(5, control.value);
        assertTrue(slider.keyPressed(select));
        assertTrue(slider.keyPressed(right));
        assertEquals(6, control.value);
        assertTrue(slider.keyPressed(select));
        assertFalse(slider.keyPressed(right));
        assertEquals(6, control.value);
        assertTrue(slider.keyPressed(select));
        control.enabled = false;
        assertFalse(slider.keyPressed(right));
        assertEquals(6, control.value);
        control.enabled = true;
        slider.visible = false;
        assertFalse(slider.keyPressed(right));
        assertEquals(6, control.value);
    }

    private static final class Range implements SettingControl.RangeControl {
        double value = 5;
        boolean enabled = true;
        @Override public String id() { return "test"; }
        @Override public Component label() { return Component.empty(); }
        @Override public Component tooltip() { return Component.empty(); }
        @Override public boolean enabled() { return enabled; }
        @Override public double get() { return value; }
        @Override public void set(double value) { this.value = value; }
        @Override public double defaultValue() { return 5; }
        @Override public double sliderMinimum() { return 0; }
        @Override public double sliderMaximum() { return 10; }
        @Override public double step() { return 1; }
        @Override public Component format(double value) { return Component.literal(Double.toString(value)); }
    }
}
