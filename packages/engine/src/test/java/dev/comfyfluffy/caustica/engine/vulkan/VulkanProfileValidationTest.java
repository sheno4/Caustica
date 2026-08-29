package dev.comfyfluffy.caustica.engine.vulkan;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class VulkanProfileValidationTest {
    @Test
    void acceptsTheCompleteRequiredProfile() {
        VulkanRequiredProfile required = VulkanRequiredProfile.CAUSTICA_1_4;
        VulkanProfileSupport support = new VulkanProfileSupport(
                required.apiVersion(), required.apiVersion(), required.apiVersion(),
                required.deviceExtensions(), required.features());
        VulkanProfileValidation validation = VulkanProfileValidation.validate(required, support);
        assertTrue(validation.supported());
        assertEquals("Vulkan profile supported", validation.diagnostic());
    }

    @Test
    void reportsEveryMissingRequirementInOneStableDiagnostic() {
        VulkanRequiredProfile required = VulkanRequiredProfile.CAUSTICA_1_4;
        HashSet<String> extensions = new HashSet<>(required.deviceExtensions());
        extensions.remove("VK_EXT_descriptor_heap");
        EnumSet<VulkanFeature> features = EnumSet.copyOf(required.features());
        features.remove(VulkanFeature.SHADER_OBJECT);
        int vulkan13 = VulkanRequiredProfile.makeApiVersion(0, 1, 3, 280);
        VulkanProfileValidation validation = VulkanProfileValidation.validate(required,
                new VulkanProfileSupport(vulkan13, vulkan13, vulkan13, extensions, features));
        assertFalse(validation.supported());
        assertEquals(5, validation.issues().size());
        assertTrue(validation.diagnostic().contains("LOADER_API_VERSION: requires 1.4.0 or newer, found 1.3.280"));
        assertTrue(validation.diagnostic().contains("DEVICE_EXTENSION: requires VK_EXT_descriptor_heap, found unavailable"));
        assertTrue(validation.diagnostic().contains("DEVICE_FEATURE: requires shaderObject, found false"));
    }

    @Test
    void formatsVariantAwareApiVersions() {
        assertEquals("1.4.7", VulkanRequiredProfile.formatApiVersion(
                VulkanRequiredProfile.makeApiVersion(0, 1, 4, 7)));
        assertEquals("2:1.4.7", VulkanRequiredProfile.formatApiVersion(
                VulkanRequiredProfile.makeApiVersion(2, 1, 4, 7)));
    }

    @Test
    void doesNotTreatAnotherApiVariantAsACompatibleNewerVersion() {
        VulkanRequiredProfile required = VulkanRequiredProfile.CAUSTICA_1_4;
        int otherVariant = VulkanRequiredProfile.makeApiVersion(1, 1, 4, 0);
        VulkanProfileValidation validation = VulkanProfileValidation.validate(required,
                new VulkanProfileSupport(otherVariant, otherVariant, otherVariant,
                        required.deviceExtensions(), required.features()));
        assertEquals(3, validation.issues().size());
    }
}
