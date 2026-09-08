package dev.comfyfluffy.caustica.slangtooling

import groovy.io.FileType
import org.gradle.api.GradleException

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.jar.JarFile

/** Materializes directory and JAR shader-module inputs as Slang include roots. */
final class SlangIncludeRoots {
    private SlangIncludeRoots() {
    }

    static List<File> directoryTree(File root) {
        if (!root.isDirectory()) return []
        List<File> directories = [root]
        root.eachFileRecurse(FileType.DIRECTORIES) { directories.add(it) }
        directories
    }

    static List<File> materialize(Collection<File> inputs, File extractionRoot) {
        if (extractionRoot.exists() && !extractionRoot.deleteDir()) {
            throw new GradleException("failed to clear extracted Slang includes under ${extractionRoot}")
        }

        List<File> ordered = inputs.collect { it.absoluteFile }.unique { it.path }.sort { it.path }
        List<File> roots = []
        ordered.eachWithIndex { input, index ->
            if (input.isDirectory()) {
                roots.add(input)
                return
            }
            if (!input.isFile() || !input.name.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                throw new GradleException("Slang include input must be a directory or JAR: ${input}")
            }

            File destination = new File(extractionRoot, String.format(Locale.ROOT, "%04d", index))
            extractJar(input, destination)
            roots.add(destination)
        }
        roots
    }

    private static void extractJar(File archive, File destination) {
        Path root = destination.toPath().toAbsolutePath().normalize()
        Files.createDirectories(root)
        new JarFile(archive).withCloseable { jar ->
            def entries = jar.entries().toList()
                    .findAll { !it.directory && it.name.toLowerCase(Locale.ROOT).endsWith(".slang") }
                    .sort { it.name }
            entries.each { entry ->
                Path output = root.resolve(entry.name.replace('\\', '/')).normalize()
                if (!output.startsWith(root)) {
                    throw new GradleException("Slang module JAR entry escapes its include root: ${entry.name}")
                }
                Files.createDirectories(output.parent)
                jar.getInputStream(entry).withCloseable { input ->
                    Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }
}
