package dev.comfyfluffy.caustica.minecraft.client;

import dev.comfyfluffy.caustica.renderer.presentation.bloom.BloomPass;
import dev.comfyfluffy.caustica.renderer.runtime.RendererOptions;
import dev.comfyfluffy.caustica.settings.Option;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ConfigOptionVocabularyTest {
    @Test
    void rendererAndExtensionDeclarationsExposeTheSameSliderVocabulary() {
        Option<Integer> runtimeInt = RendererOptions.Rt.Composite.MAX_BOUNCES;
        Option<Float> runtimeFloat = RendererOptions.Rt.Tonemap.GAMMA;
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
