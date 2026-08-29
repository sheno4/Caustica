package dev.comfyfluffy.caustica;

import dev.comfyfluffy.caustica.builtin.BloomPass;
import dev.comfyfluffy.caustica.config.CausticaConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ConfigOptionVocabularyTest {
    @Test
    void runtimeAndExtensionStoresExposeTheSameSliderVocabulary() {
        CausticaConfig.IntSetting runtimeInt = CausticaConfig.Rt.Composite.MAX_BOUNCES;
        CausticaConfig.FloatSetting runtimeFloat = CausticaConfig.Rt.Tonemap.GAMMA;
        var extensionOption = BloomPass.THRESHOLD_SCENE_LINEAR;

        assertEquals(2, runtimeInt.sliderMinimum());
        assertEquals(8, runtimeInt.sliderMaximum());
        assertEquals(0.5f, runtimeFloat.sliderMinimum());
        assertEquals(1.5f, runtimeFloat.sliderMaximum());
        assertEquals(0.0, extensionOption.sliderMinimum());
        assertEquals(16.0, extensionOption.sliderMaximum());
        assertEquals(65504.0, extensionOption.maximum(), "the slider span is not the storage clamp");
    }
}
