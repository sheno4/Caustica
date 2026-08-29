package dev.comfyfluffy.caustica.settings;


import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Display metadata, category, options, and option groups declared by one feature.
 *
 * <p>The id must match the feature's engine registration when the feature uses both APIs.
 */
public record FeatureSettings(ResourceId id, DisplayText title, DisplayText description,
                              FeatureCategory category, List<Option<?>> options,
                              List<String> optionGroups) {
    public FeatureSettings {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(category, "category");
        options = List.copyOf(options);
        optionGroups = List.copyOf(optionGroups);
        Set<String> headed = new HashSet<>();
        for (Option<?> option : options) {
            String group = option.group();
            if (group == null) {
                continue;
            }
            if (!optionGroups.contains(group)) {
                throw new IllegalArgumentException(
                        id + ": option " + option.id() + " is in undeclared group " + group);
            }
            if (option.isGroupHeader() && !headed.add(group)) {
                throw new IllegalArgumentException(id + ": group " + group + " has more than one header");
            }
        }
    }

    public Option<?> option(String optionId) {
        return options.stream().filter(option -> option.id().equals(optionId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(id + " declares no option " + optionId));
    }
}
