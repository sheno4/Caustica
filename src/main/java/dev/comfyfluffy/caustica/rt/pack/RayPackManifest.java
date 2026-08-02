package dev.comfyfluffy.caustica.rt.pack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Objects;
import java.util.regex.Pattern;

public record RayPackManifest(
        int format,
        RayPackId id,
        String version,
        RayPackContract.ApiVersion api,
        SlangImplementation slang,
        RayPackId lookPackage) {
    private static final Pattern VERSION = Pattern.compile(
            "[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?");
    private static final Pattern SLANG_MODULE = Pattern.compile("[A-Za-z_][A-Za-z0-9_.]*");
    private static final Pattern SLANG_TYPE = Pattern.compile("[A-Za-z_][A-Za-z0-9_:]*");
    private static final Pattern RELATIVE_PATH = Pattern.compile(
            "[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*");

    public RayPackManifest {
        if (format != RayPackContract.MANIFEST_FORMAT) {
            throw new IllegalArgumentException("unsupported ray-pack manifest format " + format);
        }
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(api, "api");
        Objects.requireNonNull(slang, "slang");
        Objects.requireNonNull(lookPackage, "lookPackage");
        if (!VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("ray-pack version must be semantic versioning");
        }
    }

    public static RayPackManifest parse(JsonObject root, String source) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(source, "source");
        RayPackManifestSchema.validate(root, source);

        int format = requiredInt(root, "format", source);
        RayPackId id = RayPackId.parse(requiredString(root, "id", source));
        String version = requiredString(root, "version", source);

        JsonObject apiJson = requiredObject(root, "api", source);
        RayPackContract.ApiVersion api = new RayPackContract.ApiVersion(
                requiredInt(apiJson, "major", source + ".api"),
                requiredInt(apiJson, "minor", source + ".api"));

        JsonObject slangJson = requiredObject(root, "slang", source);
        SlangImplementation slang = new SlangImplementation(
                requiredString(slangJson, "module", source + ".slang"),
                requiredString(slangJson, "type", source + ".slang"),
                requiredString(slangJson, "sourceRoot", source + ".slang"));

        return new RayPackManifest(format, id, version, api, slang,
                RayPackId.parse(requiredString(root, "lookPackage", source)));
    }

    private static JsonObject requiredObject(JsonObject root, String name, String source) {
        JsonElement value = root.get(name);
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException(source + ": missing object " + name);
        }
        return value.getAsJsonObject();
    }

    private static String requiredString(JsonObject root, String name, String source) {
        JsonElement value = root.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(source + ": missing string " + name);
        }
        return value.getAsString();
    }

    private static int requiredInt(JsonObject root, String name, String source) {
        JsonElement value = root.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(source + ": missing integer " + name);
        }
        try {
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(source + ": " + name + " must be an integer", e);
        }
    }

    public record SlangImplementation(String module, String type, String sourceRoot) {
        public SlangImplementation {
            Objects.requireNonNull(module, "module");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(sourceRoot, "sourceRoot");
            if (!SLANG_MODULE.matcher(module).matches()) {
                throw new IllegalArgumentException("invalid Slang module " + module);
            }
            String lowerModule = module.toLowerCase(java.util.Locale.ROOT);
            if (lowerModule.endsWith(".exe") || lowerModule.endsWith(".dll")
                    || lowerModule.endsWith(".so") || lowerModule.endsWith(".spv")) {
                throw new IllegalArgumentException("Slang module must name source, not a compiled artifact");
            }
            if (!SLANG_TYPE.matcher(type).matches()) {
                throw new IllegalArgumentException("invalid Slang type " + type);
            }
            if (!RELATIVE_PATH.matcher(sourceRoot).matches()
                    || sourceRoot.equals(".") || sourceRoot.contains("..")) {
                throw new IllegalArgumentException("Slang sourceRoot must be a normalized relative path");
            }
        }
    }
}
