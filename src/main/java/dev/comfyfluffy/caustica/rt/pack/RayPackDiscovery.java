package dev.comfyfluffy.caustica.rt.pack;

import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Locates ray-pack manifests: the bundled default on the classpath, and installed packs under a
 * directory of the form {@code <root>/<pack-dir>/pack.json} (docs/RAY_PACK_ARCHITECTURE.md section
 * 16.4). This is discovery only — schema and API-compatibility validation happen in
 * {@link RayPackManifest#parse} and {@link RayPackEpochManager#activate}.
 */
public final class RayPackDiscovery {
    public static final String BUNDLED_DEFAULT_MANIFEST = "/caustica/raypacks/default/pack.json";
    private static final String MANIFEST_FILE_NAME = "pack.json";

    private RayPackDiscovery() {
    }

    public static RayPackManifest discoverBundled() {
        return parse(readClasspathResource(BUNDLED_DEFAULT_MANIFEST), BUNDLED_DEFAULT_MANIFEST);
    }

    public static String bundledManifestJson() {
        return readClasspathResource(BUNDLED_DEFAULT_MANIFEST);
    }

    /**
     * Scans the immediate subdirectories of {@code root}, each expected to contain a {@code pack.json}
     * at its top. Subdirectories are visited in name order so results are deterministic across runs and
     * platforms; the first manifest seen for a given {@link RayPackId} is kept, and every later
     * manifest sharing that id is reported as a conflict instead of silently overwriting or replacing
     * it (section 16.4: "duplicate identifiers are resolved deterministically and reported").
     *
     * <p>A subdirectory whose manifest fails to parse or validate is reported as a conflict-shaped
     * failure too (source path + message) rather than aborting the whole scan, so one bad pack doesn't
     * hide every other installed pack from the selector.
     */
    public static Result scanDirectory(Path root) throws IOException {
        Objects.requireNonNull(root, "root");
        if (!Files.isDirectory(root)) {
            return new Result(List.of(), List.of());
        }
        List<Path> packDirectories;
        try (Stream<Path> entries = Files.list(root)) {
            packDirectories = entries.filter(Files::isDirectory).sorted().toList();
        }

        List<RayPackManifest> manifests = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        Map<RayPackId, Path> claimedBy = new HashMap<>();
        for (Path packDirectory : packDirectories) {
            Path manifestPath = packDirectory.resolve(MANIFEST_FILE_NAME);
            if (!Files.isRegularFile(manifestPath)) {
                continue;
            }
            String source = manifestPath.toString();
            RayPackManifest manifest;
            try {
                manifest = parse(Files.readString(manifestPath, StandardCharsets.UTF_8), source);
            } catch (RuntimeException e) {
                conflicts.add(source + ": " + e.getMessage());
                continue;
            }
            Path existing = claimedBy.putIfAbsent(manifest.id(), packDirectory);
            if (existing != null) {
                conflicts.add(source + ": duplicate ray-pack id " + manifest.id()
                        + " (already provided by " + existing + ")");
                continue;
            }
            manifests.add(manifest);
        }
        return new Result(List.copyOf(manifests), List.copyOf(conflicts));
    }

    private static RayPackManifest parse(String json, String source) {
        return RayPackManifest.parse(JsonParser.parseString(json).getAsJsonObject(), source);
    }

    private static String readClasspathResource(String resource) {
        try (InputStream input = RayPackDiscovery.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("missing ray-pack manifest resource " + resource);
            }
            try (InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                return readFully(reader);
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to read ray-pack manifest resource " + resource, e);
        }
    }

    private static String readFully(InputStreamReader reader) throws IOException {
        StringBuilder builder = new StringBuilder();
        char[] buffer = new char[4096];
        for (int read = reader.read(buffer); read >= 0; read = reader.read(buffer)) {
            builder.append(buffer, 0, read);
        }
        return builder.toString();
    }

    /** {@code duplicateConflicts} also carries per-directory parse/validation failures; see scanDirectory. */
    public record Result(List<RayPackManifest> manifests, List<String> duplicateConflicts) {
        public Result {
            manifests = List.copyOf(manifests);
            duplicateConflicts = List.copyOf(duplicateConflicts);
        }
    }
}
