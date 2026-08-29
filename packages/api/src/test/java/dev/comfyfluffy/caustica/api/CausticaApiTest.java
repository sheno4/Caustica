package dev.comfyfluffy.caustica.api;

import dev.comfyfluffy.caustica.api.session.RenderSessionChannel;
import dev.comfyfluffy.caustica.settings.OptionLookup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class CausticaApiTest {
    @Test
    void constructorCreatesIndependentImmutableApiValues() {
        RenderSessionChannel sessions = stub(RenderSessionChannel.class);
        OptionLookup options = feature -> { throw new AssertionError(feature); };
        CausticaApi first = new CausticaApi(sessions, options);
        CausticaApi second = new CausticaApi(sessions, options);

        assertSame(sessions, first.sessions());
        assertSame(sessions, second.sessions());
        assertSame(options, first.options());
        assertNotSame(first, second);
        assertThrows(NullPointerException.class, () -> new CausticaApi(null, options));
        assertThrows(NullPointerException.class, () -> new CausticaApi(sessions, null));
    }

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type) {
        return (T) java.lang.reflect.Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> null);
    }
}
