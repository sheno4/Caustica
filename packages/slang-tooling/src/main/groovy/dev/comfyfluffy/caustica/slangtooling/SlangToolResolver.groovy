package dev.comfyfluffy.caustica.slangtooling

final class SlangToolResolver {
    private SlangToolResolver() {
    }

    static String slang(String name) {
        resolve("SLANG_SDK", "bin", name)
    }

    static String vulkan(String name) {
        resolve("VULKAN_SDK", windows() ? "Bin" : "bin", name)
    }

    private static String resolve(String environmentVariable, String directory, String name) {
        String executable = windows() ? "${name}.exe" : name
        String sdk = System.getenv(environmentVariable)
        if (sdk != null && !sdk.isBlank()) {
            File candidate = new File(new File(sdk, directory), executable)
            if (candidate.isFile()) return candidate.absolutePath
        }
        executable
    }

    private static boolean windows() {
        System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")
    }
}
