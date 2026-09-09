package dev.comfyfluffy.caustica.slang;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

record SlangRuntimeManifest(int format, int abi, String slangVersion, String platform, String shim,
                            String bundleSha256, List<FileEntry> files) {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final int MAX_FILES = 256;
    private static final long MAX_FILE_BYTES = 256L * 1024L * 1024L;

    SlangRuntimeManifest {
        files = List.copyOf(files);
    }

    static SlangRuntimeManifest read(Reader reader) throws IOException {
        JsonObject root;
        try {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Invalid Slang runtime manifest", e);
        }
        int format = integer(root, "format");
        int abi = integer(root, "abi");
        String slangVersion = string(root, "slangVersion");
        String platform = string(root, "platform");
        String shim = checkedPath(string(root, "shim"));
        String bundleSha256 = checkedSha256(string(root, "bundleSha256"));
        JsonElement filesElement = root.get("files");
        if (filesElement == null || !filesElement.isJsonArray()) {
            throw new IOException("Slang runtime manifest is missing files");
        }
        JsonArray array = filesElement.getAsJsonArray();
        if (array.isEmpty() || array.size() > MAX_FILES) {
            throw new IOException("Slang runtime manifest file count is outside supported limits");
        }
        Set<String> paths = new HashSet<>();
        List<FileEntry> files = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                throw new IOException("Slang runtime file entry must be an object");
            }
            JsonObject object = element.getAsJsonObject();
            String path = checkedPath(string(object, "path"));
            long size = longValue(object, "size");
            if (size < 0 || size > MAX_FILE_BYTES) {
                throw new IOException("Slang runtime file size is outside supported limits: " + path);
            }
            if (!paths.add(path)) {
                throw new IOException("Duplicate Slang runtime file: " + path);
            }
            files.add(new FileEntry(path, size, checkedSha256(string(object, "sha256"))));
        }
        if (format != 1 || abi != SlangLibrary.ABI_VERSION) {
            throw new IOException("Unsupported Slang runtime manifest format/ABI " + format + "/" + abi);
        }
        if (files.stream().noneMatch(file -> file.path().equals(shim))) {
            throw new IOException("Slang runtime shim is not present in file list: " + shim);
        }
        return new SlangRuntimeManifest(format, abi, slangVersion, platform, shim, bundleSha256, files);
    }

    private static int integer(JsonObject object, String name) throws IOException {
        long value = longValue(object, name);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IOException("Slang runtime manifest integer is out of range: " + name);
        }
        return (int) value;
    }

    private static long longValue(JsonObject object, String name) throws IOException {
        try {
            JsonElement value = object.get(name);
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                throw new IOException("Slang runtime manifest is missing number " + name);
            }
            return value.getAsBigDecimal().longValueExact();
        } catch (NumberFormatException | ArithmeticException | UnsupportedOperationException e) {
            throw new IOException("Invalid Slang runtime manifest number " + name, e);
        }
    }

    private static String string(JsonObject object, String name) throws IOException {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IOException("Slang runtime manifest is missing string " + name);
        }
        return value.getAsString();
    }

    private static String checkedPath(String path) throws IOException {
        if (path.isBlank() || path.startsWith("/") || path.startsWith("\\") || path.contains("\\")
                || path.contains(":") || path.equals("..") || path.startsWith("../") || path.contains("/../")) {
            throw new IOException("Unsafe Slang runtime path: " + path);
        }
        return path;
    }

    private static String checkedSha256(String value) throws IOException {
        if (!SHA256.matcher(value).matches()) {
            throw new IOException("Invalid Slang runtime SHA-256: " + value);
        }
        return value;
    }

    record FileEntry(String path, long size, String sha256) {
    }
}
