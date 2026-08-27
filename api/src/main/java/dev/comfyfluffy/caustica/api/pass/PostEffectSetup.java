package dev.comfyfluffy.caustica.api.pass;

/**
 * Fixed formats needed to create a post-effect pipeline for one render session.
 *
 * @param common common pass services
 * @param sceneColorFormat scene colour/output VkFormat
 * @param exposureFormat exposure-image VkFormat
 */
public record PostEffectSetup(PassSetup common, int sceneColorFormat, int exposureFormat) {
    public PostEffectSetup {
        if (common == null) throw new NullPointerException("common");
    }
}
