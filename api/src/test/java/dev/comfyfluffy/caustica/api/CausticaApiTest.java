package dev.comfyfluffy.caustica.api;

import dev.comfyfluffy.caustica.api.session.RenderSessionChannel;
import dev.comfyfluffy.caustica.api.host.CausticaBootstrap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class CausticaApiTest {
    @Test
    void bootstrapCreatesIndependentImmutableApiValues() {
        RenderSessionChannel sessions = stub(RenderSessionChannel.class);
        CausticaApi first = CausticaBootstrap.create(sessions);
        CausticaApi second = CausticaBootstrap.create(sessions);

        assertSame(sessions, first.sessions());
        assertSame(sessions, second.sessions());
        assertNotSame(first, second);
        assertThrows(NullPointerException.class, () -> CausticaBootstrap.create(null));
    }

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type) {
        return (T) java.lang.reflect.Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> null);
    }
}
