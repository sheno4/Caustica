package dev.comfyfluffy.caustica.api;

import dev.comfyfluffy.caustica.api.session.RenderSessionChannel;
import dev.comfyfluffy.caustica.api.host.CausticaBootstrap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class CausticaApiTest {
    @Test
    void exposesTheHostInstalledSessionFactoryChannelAndRefusesASecondInstall() {
        RenderSessionChannel sessions = stub(RenderSessionChannel.class);
        CausticaBootstrap.install(sessions);

        CausticaApi api = CausticaApi.getInstance();

        assertSame(sessions, api.sessions());
        assertThrows(IllegalStateException.class, () -> CausticaBootstrap.install(sessions));
    }

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type) {
        return (T) java.lang.reflect.Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> null);
    }
}
