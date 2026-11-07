package dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/**
 * Bundled source paths remain readable for asynchronous uploads throughout the process lifetime.
 *
 * <p>Each scene owns its archive file system. Preparation for one scene is cancellable by
 * interrupting its upload worker; with a single shared file system that interrupt closed the
 * underlying jar channel and made every later scene fail with {@link java.nio.channels.ClosedChannelException}.
 */
public final class CloudlyBundledPreset {
    private static final Map<CloudlyOptions.Scene, CloudlySourcePack> SOURCES = new EnumMap<>(CloudlyOptions.Scene.class);
    private static final Map<CloudlyOptions.Scene, FileSystem> ARCHIVES = new EnumMap<>(CloudlyOptions.Scene.class);

    private CloudlyBundledPreset() { }

    /** Reads the immutable preset directly from the class directory or the owning mod archive. */
    public static synchronized CloudlySourcePack load(CloudlyOptions.Scene scene) throws IOException {
        CloudlySourcePack source = SOURCES.get(scene);
        if (source != null) {
            try {
                if (Files.isRegularFile(source.manifest())) return source;
            } catch (RuntimeException ignored) {
                // A canceled upload may have closed this scene's archive channel.
            }
            SOURCES.remove(scene);
            closeArchive(scene);
        }
        String manifest = switch (scene) {
            case HANGAR -> "/caustica/cloudly/manifest.json";
            case MISSION3 -> "/caustica/cloudly/scenes/mission3/manifest.json";
            case MISSION11 -> "/caustica/cloudly/scenes/mission11/manifest.json";
        };
        URL resource = CloudlyBundledPreset.class.getResource(manifest);
        if (resource == null) throw new FileNotFoundException("The mod does not contain its bundled Cloudly preset: " + manifest);
        try {
            Path path = switch (resource.getProtocol()) {
                case "file" -> Path.of(resource.toURI());
                case "jar" -> archivePath(scene, resource);
                default -> throw new IOException("Unsupported bundled Cloudly resource protocol: " + resource.getProtocol());
            };
            try {
                source = CloudlySourcePack.load(path);
                SOURCES.put(scene, source);
                return source;
            } catch (IOException | RuntimeException failure) {
                closeArchive(scene);
                throw failure;
            }
        } catch (URISyntaxException failure) {
            throw new IOException("Invalid bundled Cloudly resource URI", failure);
        }
    }

    private static Path archivePath(CloudlyOptions.Scene scene, URL resource) throws IOException, URISyntaxException {
        var connection = (JarURLConnection)resource.openConnection();
        if (!connection.getJarFileURL().getProtocol().equals("file")) {
            throw new IOException("The bundled Cloudly preset must belong to a local mod archive");
        }
        closeArchive(scene);
        Path archive = Path.of(connection.getJarFileURL().toURI());
        FileSystem fileSystem = FileSystems.newFileSystem(archive, Map.of());
        ARCHIVES.put(scene, fileSystem);
        return fileSystem.getPath("/" + connection.getEntryName());
    }

    private static void closeArchive(CloudlyOptions.Scene scene) {
        FileSystem fileSystem = ARCHIVES.remove(scene);
        if (fileSystem == null) return;
        try {
            fileSystem.close();
        } catch (IOException ignored) {
            // The interrupted upload worker may already have closed its channel.
        }
    }
}
