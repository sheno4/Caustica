package dev.comfyfluffy.caustica.minecraft.gltf;

import dev.comfyfluffy.caustica.gltf.GltfLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;

/**
 * Resource-epoch snapshot for the viewer model. Resource packs can replace
 * {@code assets/caustica/gltf/viewer/model.glb} with any supported glTF binary.
 */
final class GltfViewerAssetRepository {
    static final Identifier LOCATION = Identifier.fromNamespaceAndPath(
            "caustica", "gltf/viewer/model.glb");
    private static GltfViewerScene current;

    private GltfViewerAssetRepository() {
    }

    static GltfViewerScene current() {
        if (current == null) {
            reload();
        }
        return current;
    }

    static void reload() {
        current = load(Minecraft.getInstance().getResourceManager());
    }

    static void clear() {
        current = null;
    }

    static GltfViewerScene load(ResourceManager resources) {
        try {
            byte[] source = read(resources, LOCATION);
            String base = parent(LOCATION.getPath());
            GltfLoader.Asset asset = GltfLoader.load(source, uri -> {
                String relative = URI.create(uri).getPath();
                if (relative == null) {
                    throw new IOException("glTF resource URI has no path: " + uri);
                }
                Path resolved = Path.of(base).resolve(relative).normalize();
                Path root = Path.of(base).normalize();
                if (Path.of(relative).isAbsolute() || !resolved.startsWith(root)) {
                    throw new IOException("glTF resource escapes viewer asset directory: " + uri);
                }
                Identifier location = Identifier.fromNamespaceAndPath(
                        LOCATION.getNamespace(), resolved.toString().replace('\\', '/'));
                return read(resources, location);
            });
            return GltfViewerAsset.adapt(asset);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("failed to load glTF viewer asset " + LOCATION, exception);
        }
    }

    private static byte[] read(ResourceManager resources, Identifier location) throws IOException {
        try (InputStream input = resources.getResourceOrThrow(location).open()) {
            return input.readAllBytes();
        }
    }

    private static String parent(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash + 1);
    }
}
