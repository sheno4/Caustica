package dev.comfyfluffy.caustica.client.settings;

import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Objects;

/**
 * A collapsible run of rows under one title.
 *
 * <p>{@code header} is the bool whose value decides whether {@code rows} are shown — bloom's on/off switch,
 * where turning it off makes its parameters meaningless rather than merely uninteresting. A group with no
 * header collapses only when the player clicks its caret, which is the right shape wherever there is no
 * state in which the rows do not apply.
 */
public record SettingGroup(String id, Component title, SettingControl.BoolControl header,
                           List<SettingControl> rows) {
    public SettingGroup {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        rows = List.copyOf(rows);
    }

    /** Whether the rows should be visible, given the header's value and the player's caret state. */
    public boolean rowsVisible(boolean expandedByCaret) {
        return header != null ? header.get() : expandedByCaret;
    }

    /** Every control in the group, header included — what "reset this section" iterates. */
    public List<SettingControl> allControls() {
        if (header == null) {
            return rows;
        }
        List<SettingControl> all = new java.util.ArrayList<>(rows.size() + 1);
        all.add(header);
        all.addAll(rows);
        return List.copyOf(all);
    }
}
