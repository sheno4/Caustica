package dev.comfyfluffy.caustica.example.gltfviewer;

import dev.comfyfluffy.caustica.example.gltfcontent.GltfAssetAdapter;
import dev.comfyfluffy.caustica.example.gltfcontent.GltfLoader;
import dev.comfyfluffy.caustica.example.gltfcontent.GltfScene;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Resource-epoch snapshot for the viewer model. Resource packs can replace
 * {@code assets/caustica_gltf_viewer/gltf/viewer/model.glb} with any supported glTF binary.
 */
final class GltfViewerAssetRepository {
    static final Identifier LOCATION = Identifier.fromNamespaceAndPath(
            GltfViewerMod.MOD_ID, "gltf/viewer/model.glb");
    private final Supplier<GltfScene> loader;
    private GltfScene current;

    GltfViewerAssetRepository() {
        this(() -> load(Minecraft.getInstance().getResourceManager()));
    }

    GltfViewerAssetRepository(Supplier<GltfScene> loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    GltfScene current() {
        if (current == null) {
            throw new IllegalStateException("glTF viewer assets are not loaded for this resource epoch");
        }
        return current;
    }

    void reload() {
        current = Objects.requireNonNull(loader.get(), "loader returned a null glTF viewer scene");
    }

    void clear() {
        current = null;
    }

    static GltfScene load(ResourceManager resources) {
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
            return GltfAssetAdapter.adapt(asset);
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
