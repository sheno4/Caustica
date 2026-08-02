package dev.comfyfluffy.caustica.slang;

import java.util.Locale;

enum SlangPlatform {
    WINDOWS_X64("windows-x64", "causticaslang.dll", "slang.dll", "slang-compiler.dll"),
    LINUX_X64("linux-x64", "libcausticaslang.so", "libslang.so", "libslang-compiler.so");

    private final String resourceName;
    private final String shimName;
    private final String coreName;
    private final String compilerName;

    SlangPlatform(String resourceName, String shimName, String coreName, String compilerName) {
        this.resourceName = resourceName;
        this.shimName = shimName;
        this.coreName = coreName;
        this.compilerName = compilerName;
    }

    static SlangPlatform current() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean x64 = arch.equals("x86_64") || arch.equals("amd64");
        if (os.contains("win") && x64) {
            return WINDOWS_X64;
        }
        if (os.contains("linux") && x64) {
            return LINUX_X64;
        }
        throw new IllegalStateException("Bundled Slang is unavailable for " + os + "/" + arch);
    }

    String resourceName() {
        return resourceName;
    }

    String shimName() {
        return shimName;
    }

    String coreName() {
        return coreName;
    }

    String compilerName() {
        return compilerName;
    }
}

