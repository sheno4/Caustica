package dev.comfyfluffy.caustica.settings;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

final class OptionTest {
    private enum Quality { LOW, HIGH }

    @Test
    void boundedNumbersNormalizeToTheirDeclaredType() {
        Option<Integer> count = Option.integer("count", 1, 8, 4);
        assertEquals(8, count.normalize(99L));
        assertEquals(3, count.normalize(2.8));
        assertEquals(1, count.normalize("-5"));
        assertEquals(0.5f, Option.range("weight", 0, 1, 1).normalize("0.5"));
        assertThrows(IllegalArgumentException.class, () -> count.normalize("NaN"));
        assertThrows(IllegalArgumentException.class, () -> Option.range("weight", 0, 1, Float.NaN));
    }

    @Test
    void choicesValidateAndEnumsRoundTripAsNames() {
        assertEquals(2, Option.intChoice("quality", 1, List.of(1, 2)).normalize(2L));
        Option<String> choice = Option.stringChoice("mode", "raw", List.of("raw", "nrd"));
        assertEquals("nrd", choice.normalize("nrd"));
        assertThrows(IllegalArgumentException.class, () -> choice.normalize("missing"));
        Option<Quality> quality = Option.enumOf("quality", Quality.LOW, List.of(Quality.values()));
        assertEquals(Quality.HIGH, quality.normalize("high"));
        assertEquals(Optional.of("HIGH"), quality.encode(Quality.HIGH));
    }

    @Test
    void optionalStringsUseAnExplicitAbsentValue() {
        Option<Optional<String>> path = Option.optionalString("path");
        assertEquals(Optional.empty(), path.defaultValue());
        assertEquals(Optional.of("scene.gltf"), path.normalize("scene.gltf"));
        assertEquals(Optional.empty(), path.normalize(" "));
        assertEquals(Optional.empty(), path.encode(Optional.empty()));
        assertEquals(Optional.of("scene.gltf"), path.encode(Optional.of("scene.gltf")));
    }

    @Test
    void storageIdentitySurvivesDisplayMetadataChanges() {
        Option<Integer> count = Option.integer("count", 0, 16, 4)
                .storage("sampling.count", "caustica.count").inGroup("sampling").step(1).sliderRange(1, 8);
        assertEquals("sampling.count", count.tomlPath());
        assertEquals("caustica.count", count.systemPropertyKey());
        assertEquals(1, count.sliderMinimum());
        assertEquals(8, count.sliderMaximum());
    }

    @Test
    void invalidBooleanTextIsRejectedAndColorsUseRgbBounds() {
        assertThrows(IllegalArgumentException.class, () -> Option.bool("flag", true).normalize("nope"));
        assertEquals(0xffffff, Option.color("color", 0).normalize(0x1000000L));
    }
}
