package dev.comfyfluffy.caustica.rt.pack;

public final class RayPackContract {
    public static final int MANIFEST_FORMAT = 1;
    public static final ApiVersion API_VERSION = new ApiVersion(0, 1);

    private RayPackContract() {
    }

    public static boolean supports(ApiVersion requested) {
        if (API_VERSION.major() == 0) {
            return requested.equals(API_VERSION);
        }
        return requested.major() == API_VERSION.major() && requested.minor() <= API_VERSION.minor();
    }

    public record ApiVersion(int major, int minor) {
        public ApiVersion {
            if (major < 0 || minor < 0) {
                throw new IllegalArgumentException("ray-pack API version components must be non-negative");
            }
        }
    }
}
