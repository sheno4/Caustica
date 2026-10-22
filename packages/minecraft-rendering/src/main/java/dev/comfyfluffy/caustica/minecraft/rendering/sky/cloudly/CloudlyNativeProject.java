package dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Private source revisions and authored constants consumed by the original GPU renderer. */
public record CloudlyNativeProject(CloudlyShaderLibrary library, CloudlyOriginalBakeResources density,
                                   CloudlyOriginalBakeResources atmosphere, CloudlyFrameTextures.Source textures,
                                   CloudlyFrameSchedule schedule, Map<String, CloudlyFrameRecipe> recipes,
                                   Map<String, double[]> settings, Path sphereSamples) {
    public CloudlyNativeProject { recipes = Map.copyOf(recipes); settings = copy(settings); }
    @Override public Map<String, double[]> settings() { return copy(settings); }

    public static CloudlyNativeProject load(Path manifest) throws IOException {
        Path actual = manifest.toRealPath();
        JsonObject root = JsonParser.parseString(Files.readString(actual)).getAsJsonObject();
        if (root.get("schemaVersion").getAsInt() != 1) throw new IOException("Unsupported native Cloudly project");
        var library = CloudlyShaderLibrary.load(path(actual, root, "library"));
        var density = CloudlyOriginalBakeResources.load(path(actual, root, "densityBake"), library);
        var atmosphere = CloudlyOriginalBakeResources.load(path(actual, root, "atmosphereBake"), library);
        var textures = CloudlyFrameTextures.load(path(actual, root, "textures"));
        var schedule = CloudlyFrameSchedule.load(path(actual, root, "frameSchedule"), library);
        Map<String, CloudlyFrameRecipe> recipes = new LinkedHashMap<>();
        for (JsonElement item : root.getAsJsonArray("parameterRecipes")) {
            Path file = actual.getParent().resolve(item.getAsString()).toRealPath();
            JsonObject recipe = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            Map<String, String> ids = new LinkedHashMap<>();
            for (JsonElement value : recipe.getAsJsonArray("programs")) {
                JsonObject program = value.getAsJsonObject();
                String id = program.get("programId").getAsString();
                ids.put(id, program.has("runtimeProgramId") ? program.get("runtimeProgramId").getAsString()
                        : List.of("62", "97", "406", "3930", "3928").contains(id) ? id + "-host-camera" : id);
            }
            var decoded = CloudlyFrameRecipe.load(file, library, ids);
            for (String id : ids.keySet()) if (recipes.put(id, decoded) != null) throw new IOException("Repeated native frame recipe " + id);
        }
        Map<String, double[]> settings = new LinkedHashMap<>();
        for (var entry : root.getAsJsonObject("settings").entrySet()) {
            settings.put(entry.getKey(), CloudlyUniformExpressions.evaluate(entry.getValue(), Map.of()));
        }
        return new CloudlyNativeProject(library, density, atmosphere, textures, schedule, recipes, settings,
                path(actual, root, "sphereSamples"));
    }

    public double scalar(String name) {
        double[] value = settings.get(name);
        if (value == null || value.length != 1 || !Double.isFinite(value[0])) throw new IllegalArgumentException("Missing native source scalar " + name);
        return value[0];
    }

    private static Path path(Path manifest, JsonObject root, String name) throws IOException {
        return manifest.getParent().resolve(root.get(name).getAsString()).toRealPath();
    }
    private static Map<String, double[]> copy(Map<String, double[]> input) {
        var copy = new LinkedHashMap<String, double[]>();
        input.forEach((key, value) -> copy.put(key, value.clone()));
        return Map.copyOf(copy);
    }
}
