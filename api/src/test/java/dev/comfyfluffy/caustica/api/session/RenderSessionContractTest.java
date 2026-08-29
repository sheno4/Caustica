package dev.comfyfluffy.caustica.api.session;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

final class RenderSessionContractTest {
    @Test
    void registrationExposesOnlyANonBlockingCloseRequest() throws NoSuchMethodException {
        assertSame(void.class, RenderSessionRegistration.class.getMethod("close").getReturnType());
        assertFalse(Arrays.stream(RenderSessionRegistration.class.getMethods())
                .map(Method::getName)
                .anyMatch(name -> name.startsWith("await") || name.startsWith("join")));
    }
}
