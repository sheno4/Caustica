package dev.comfyfluffy.caustica.settings;


import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Declares one feature's settings. Terminated by {@link #register()}. */
public final class SettingsBuilder {
    private final SettingsRegistry registry;
    private final ResourceId id;
    private final List<Option<?>> options = new ArrayList<>();
    private final List<String> optionGroups = new ArrayList<>();
    private DisplayText title;
    private DisplayText description = DisplayText.EMPTY;
    private FeatureCategory category = FeatureCategory.GENERAL;

    SettingsBuilder(SettingsRegistry registry, ResourceId id) {
        this.registry = registry;
        this.id = id;
    }

    public SettingsBuilder title(DisplayText title) {
        this.title = Objects.requireNonNull(title, "title");
        return this;
    }

    public SettingsBuilder description(DisplayText description) {
        this.description = Objects.requireNonNull(description, "description");
        return this;
    }

    public SettingsBuilder category(FeatureCategory category) {
        this.category = Objects.requireNonNull(category, "category");
        return this;
    }

    public SettingsBuilder option(Option<?> option) {
        Objects.requireNonNull(option, "option");
        if (options.stream().anyMatch(existing -> existing.id().equals(option.id()))) {
            throw new IllegalStateException(id + " declares duplicate option " + option.id());
        }
        options.add(option);
        return this;
    }

    /** Declares each option in list order. */
    public SettingsBuilder options(List<Option<?>> declared) {
        declared.forEach(this::option);
        return this;
    }

    /**
     * Declares a collapsible group in display order. Add options with {@link Option#inGroup}.
     */
    public SettingsBuilder group(String groupId) {
        Option.requireGroupId(groupId);
        if (optionGroups.contains(groupId)) {
            throw new IllegalStateException(id + " declares duplicate option group " + groupId);
        }
        optionGroups.add(groupId);
        return this;
    }

    public FeatureSettings register() {
        FeatureSettings settings = new FeatureSettings(id,
                title != null ? title : DisplayText.literal(id.toString()),
                description, category, options, optionGroups);
        registry.register(settings);
        return settings;
    }
}
