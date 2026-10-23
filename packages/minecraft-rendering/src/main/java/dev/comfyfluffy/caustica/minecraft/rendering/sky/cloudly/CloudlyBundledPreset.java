package dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/** Bundled source paths remain readable for asynchronous uploads throughout the process lifetime. */
public final class CloudlyBundledPreset {
    private static FileSystem archiveFileSystem;
    private static final Map<CloudlyOptions.Scene, CloudlySourcePack> SOURCES = new EnumMap<>(CloudlyOptions.Scene.class);

    private CloudlyBundledPreset() { }

    /** Reads the immutable preset directly from the class directory or the owning mod archive. */
    public static synchronized CloudlySourcePack load(CloudlyOptions.Scene scene) throws IOException {
        CloudlySourcePack source = SOURCES.get(scene);
        if (source != null) return source;
        String manifest = switch (scene) {
            case HANGAR -> "/caustica/cloudly/manifest.json";
            case MISSION3 -> "/caustica/cloudly/scenes/mission3/manifest.json";
        };
        URL resource = CloudlyBundledPreset.class.getResource(manifest);
        if (resource == null) throw new FileNotFoundException("The mod does not contain its bundled Cloudly preset: " + manifest);
        try {
            Path path = switch (resource.getProtocol()) {
                case "file" -> Path.of(resource.toURI());
                case "jar" -> archivePath(resource);
                default -> throw new IOException("Unsupported bundled Cloudly resource protocol: " + resource.getProtocol());
            };
            source = CloudlySourcePack.load(path);
            SOURCES.put(scene, source);
            return source;
        } catch (URISyntaxException failure) {
            throw new IOException("Invalid bundled Cloudly resource URI", failure);
        }
    }

    private static Path archivePath(URL resource) throws IOException, URISyntaxException {
        var connection = (JarURLConnection)resource.openConnection();
        if (!connection.getJarFileURL().getProtocol().equals("file")) {
            throw new IOException("The bundled Cloudly preset must belong to a local mod archive");
        }
        if (archiveFileSystem == null) {
            URI archive = URI.create("jar:" + connection.getJarFileURL().toURI());
            try {
                archiveFileSystem = FileSystems.getFileSystem(archive);
            } catch (FileSystemNotFoundException missing) {
                try {
                    archiveFileSystem = FileSystems.newFileSystem(archive, Map.of());
                } catch (FileSystemAlreadyExistsException raced) {
                    archiveFileSystem = FileSystems.getFileSystem(archive);
                }
            }
        }
        return archiveFileSystem.getPath("/" + connection.getEntryName());
    }
}
