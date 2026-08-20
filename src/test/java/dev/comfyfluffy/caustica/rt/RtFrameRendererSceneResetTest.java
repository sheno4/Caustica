package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.api.CausticaRegistry;
import dev.comfyfluffy.caustica.rt.pipeline.RtDlssRr;
import dev.comfyfluffy.caustica.rt.provider.ProviderManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtFrameRendererSceneResetTest {
    @Test
    void sceneResetInvalidatesEveryRendererOwnedHistory() throws ReflectiveOperationException {
        CausticaRegistry.RuntimeContributions contributions = new CausticaRegistry.RuntimeContributions(
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        ProviderManager providers = new ProviderManager(contributions);
        RtFramePresenter presenter = new RtFramePresenter();
        RtFrameRenderer renderer = new RtFrameRenderer(
                new RtProgramManager(), providers, presenter, contributions);

        set(renderer, "mvHasPrev", true);
        set(renderer, "proceduralTimeValid", true);
        set(renderer, "currentTlasHandle", 123L);
        set(presenter, "renderedFrame", new RtFramePresenter.RenderedFrame(
                null, null, null, 1, 1, new org.joml.Matrix4f(), new org.joml.Matrix4f(), false));
        Object frameGeneration = get(presenter, "frameGeneration");
        set(frameGeneration, "reset", false);
        set(renderer.exposure(), "resetRequested", false);
        set(RtDlssRr.INSTANCE, "resetHistory", false);

        renderer.resetSceneHistory();

        assertFalse((boolean) get(renderer, "mvHasPrev"));
        assertFalse((boolean) get(renderer, "proceduralTimeValid"));
        assertTrue((boolean) get(renderer.exposure(), "resetRequested"));
        assertTrue((boolean) get(RtDlssRr.INSTANCE, "resetHistory"));
        assertNull(get(presenter, "renderedFrame"));
        assertTrue((boolean) get(frameGeneration, "reset"));
        org.junit.jupiter.api.Assertions.assertEquals(0L, get(renderer, "currentTlasHandle"));
    }

    private static Object get(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
