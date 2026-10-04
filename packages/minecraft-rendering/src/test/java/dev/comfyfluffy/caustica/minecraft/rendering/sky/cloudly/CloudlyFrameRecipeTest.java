package dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CloudlyFrameRecipeTest {
    @TempDir Path directory;

    @Test void sourceAliasesFeedLaterPatchesAndAuthoredLightRemainsFrozen() throws Exception {
        var recipe = fixture(true);
        Files.write(directory.resolve("parameters.bin"), new byte[32]);
        var first = recipe.encode(Map.of("factor", new double[]{0.45}, "frame", new double[]{4294967295L}), (layout, bytes) -> { });
        var second = recipe.encode(Map.of("factor", new double[]{0.65}, "frame", new double[]{57}), (layout, bytes) -> { });
        ByteBuffer a = first.parameters("source"), b = second.parameters("source");
        assertEquals(0.45f, a.getFloat(16));
        assertEquals(0.45f * 2, a.getFloat(20));
        assertEquals(0.65f * 2, b.getFloat(20));
        assertEquals(100000, a.getFloat(28));
        assertEquals(-1, a.getInt(0));
        assertEquals(57, b.getInt(0));
        assertTrue(a.isReadOnly());
        assertEquals("host", first.runtimeIds().get("source"));
        recipe.requireExecutionReady();
    }

    @Test void missingExecutedInputsAndOutOfRangeIntegersFailBeforeRecording() throws Exception {
        var recipe = fixture(true);
        assertThrows(IllegalArgumentException.class, () -> recipe.encode(Map.of("factor", new double[]{1}), (layout, bytes) -> { }));
        assertThrows(IllegalArgumentException.class, () -> recipe.encode(Map.of("factor", new double[]{1}, "frame", new double[]{4294967296L}), (layout, bytes) -> { }));
    }

    @Test void incompleteSourcePlanCanBeInspectedButCannotBeDeclaredReady() throws Exception {
        var recipe = fixture(false);
        assertFalse(recipe.executionReady());
        assertThrows(IllegalStateException.class, recipe::requireExecutionReady);
        assertEquals(32, recipe.encode(Map.of("factor", new double[]{1}, "frame", new double[]{0}), (layout, bytes) -> { }).parameters("source").remaining());
    }

    @Test void aStageWithoutCloudUniformsDoesNotDemandCloudStatistics() throws Exception {
        var recipe = fixture(true);
        var encoded = recipe.encode(Set.of("other"), Map.of("frame", new double[]{12}), (layout, bytes) -> { });
        assertEquals(12, encoded.parameters("other").getInt(0));
        assertEquals(Set.of("other"), encoded.parameters().keySet());
    }

    private CloudlyFrameRecipe fixture(boolean ready) throws Exception {
        byte[] code = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x07230203).putInt(0x00010600).putInt(0).putInt(1).putInt(0).array();
        Files.write(directory.resolve("host.spv"), code);
        Files.writeString(directory.resolve("library.json"), """
                {"schemaVersion":1,"programs":[{"id":"host","entryPoint":"main","spirv":"host.spv",
                "sha256":"%s","sourceSha256":"%s","parametersByteSize":32,"bindingByteSize":0,
                "pushByteSize":8,"pushFields":[{"name":"parametersAddress","offset":0,"size":8}],
                "commonFields":[{"name":"frameNumber","type":"uint","offset":0,"size":4}],
                "uniformBlocks":[{"name":"Sky","offset":16,"fields":[
                {"name":"factor","type":"float","offset":0,"size":4},
                {"name":"dependent","type":"float","offset":4,"size":4},
                {"name":"authoredLight","type":"float","offset":12,"size":4}]}]}]}
                """.formatted(hash(code), "1".repeat(64)));
        byte[] parameters = new byte[32];
        ByteBuffer.wrap(parameters).order(ByteOrder.LITTLE_ENDIAN).putFloat(28, 100000);
        Files.write(directory.resolve("parameters.bin"), parameters);
        Files.writeString(directory.resolve("recipe.json"), """
                {"schemaVersion":1,"executionReady":%s,"sourceInputs":{"Sky.factor":8},"hostSelections":{},
                "derivedInputs":[{"name":"unconsumed","expression":{"input":"anotherStageStatistics"}}],
                "uniformPatches":[
                {"uniform":"Sky","field":"factor","type":"float","offset":0,
                "expression":{"input":"factor"},"outputAliases":["Sky.factor"]},
                {"uniform":"Sky","field":"dependent","type":"float","offset":4,
                "expression":{"op":"multiply","args":[{"input":"Sky.factor"},2]},"outputAliases":[]}],
                "inactiveUniformMembers":[],"programs":[{"programId":"source","parametersByteSize":32,
                "template":{"path":"parameters.bin","sha256":"%s"},"uniformBlocks":[{"name":"Sky","offset":16,"size":16}],
                "commonFields":[{"name":"frameNumber","type":"uint","offset":0,"size":4,"expression":{"input":"frame"}}]}]}
                """.formatted(ready, hash(parameters)));
        var library = com.google.gson.JsonParser.parseString(Files.readString(directory.resolve("library.json"))).getAsJsonObject();
        var otherProgram = library.getAsJsonArray("programs").get(0).getAsJsonObject().deepCopy();
        otherProgram.addProperty("id", "hostWithoutSky");
        otherProgram.add("uniformBlocks", new com.google.gson.JsonArray());
        library.getAsJsonArray("programs").add(otherProgram);
        Files.writeString(directory.resolve("library.json"), library.toString());
        var plan = com.google.gson.JsonParser.parseString(Files.readString(directory.resolve("recipe.json"))).getAsJsonObject();
        var otherTemplate = plan.getAsJsonArray("programs").get(0).getAsJsonObject().deepCopy();
        otherTemplate.addProperty("programId", "other");
        otherTemplate.add("uniformBlocks", new com.google.gson.JsonArray());
        plan.getAsJsonArray("programs").add(otherTemplate);
        Files.writeString(directory.resolve("recipe.json"), plan.toString());
        return CloudlyFrameRecipe.load(directory.resolve("recipe.json"), CloudlyShaderLibrary.load(directory.resolve("library.json")),
                Map.of("source", "host", "other", "hostWithoutSky"));
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
