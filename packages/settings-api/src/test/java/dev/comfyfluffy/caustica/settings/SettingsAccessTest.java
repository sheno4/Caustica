package dev.comfyfluffy.caustica.settings;

import dev.comfyfluffy.caustica.settings.testing.InMemorySettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class SettingsAccessTest {
    @Test
    void editsLeaveCapturedFrameValuesUnchanged() {
        InMemorySettings settings = new InMemorySettings();
        ResourceId feature = ResourceId.of("test", "render");
        Option<Integer> samples = Option.integer("samples", 1, 16, 2);
        var frame = settings.snapshot();
        settings.set(feature, samples, 32);
        assertEquals(16, settings.options(feature).get(samples));
        assertEquals(2, frame.options(feature).get(samples));
        assertEquals(1, settings.saves());
    }
}
