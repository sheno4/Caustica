package dev.comfyfluffy.caustica.minecraft.client.settings;

import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Objects;

/**
 * One page of the settings screen: an entry in the sidebar and the groups it shows.
 *
 * <p>This is the extensibility seam. A third-party extension gets a page by registering a {@code Feature}
 * with grouped options — it contributes data, not widgets, so nothing about the screen's construction is
 * public API yet. That is deliberate: exporting a widget-contribution interface before the widget set has
 * shipped once would freeze the wrong shape.
 *
 * @param accent the section's colour, used for its sidebar bar, slider fills and focus rings — never for
 *               body text, and never as brightness, since the UI is composited at a fixed nit level in HDR
 */
public record SettingsSection(String id, Component title, int accent, List<SettingGroup> groups) {
    public SettingsSection {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        groups = List.copyOf(groups);
    }

    public List<SettingControl> allControls() {
        return groups.stream().flatMap(group -> group.allControls().stream()).toList();
    }

    public boolean isModified() {
        return allControls().stream().filter(SettingControl::enabled).anyMatch(SettingControl::isModified);
    }

    public void reset() {
        allControls().stream().filter(SettingControl::enabled).forEach(SettingControl::reset);
    }
}
