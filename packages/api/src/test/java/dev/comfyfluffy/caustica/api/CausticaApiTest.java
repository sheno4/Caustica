package dev.comfyfluffy.caustica.api;

import dev.comfyfluffy.caustica.api.session.RenderSessionChannel;
import dev.comfyfluffy.caustica.settings.Option;
import dev.comfyfluffy.caustica.settings.ResourceId;
import dev.comfyfluffy.caustica.settings.testing.InMemorySettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class CausticaApiTest {
    @Test
    void constructorCreatesIndependentImmutableApiValues() {
        RenderSessionChannel sessions = stub(RenderSessionChannel.class);
        InMemorySettings options = new InMemorySettings();
        CausticaApi first = new CausticaApi(sessions, options);
        CausticaApi second = new CausticaApi(sessions, options);

        assertSame(sessions, first.sessions());
        assertSame(sessions, second.sessions());
        assertSame(options, first.options());
        assertNotSame(first, second);
        assertThrows(NullPointerException.class, () -> new CausticaApi(null, options));
        assertThrows(NullPointerException.class, () -> new CausticaApi(sessions, null));
    }

    @Test
    void extensionsEditTheSharedSettingsWhileExistingFrameSnapshotsStayFixed() {
        InMemorySettings settings = new InMemorySettings();
        CausticaApi api = new CausticaApi(stub(RenderSessionChannel.class), settings);
        ResourceId feature = ResourceId.of("test", "render");
        Option<Integer> samples = Option.integer("samples", 1, 16, 2);
        var frame = api.options().snapshot();
        api.options().set(feature, samples, 32);
        assertEquals(16, settings.options(feature).get(samples));
        assertEquals(2, frame.options(feature).get(samples));
        assertEquals(1, settings.saves());
    }

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type) {
        return (T) java.lang.reflect.Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> null);
    }
}
