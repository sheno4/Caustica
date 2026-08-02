package dev.comfyfluffy.caustica.rt.pack;

import com.google.gson.JsonObject;
import dev.harrel.jsonschema.Validator;
import dev.harrel.jsonschema.ValidatorFactory;
import dev.harrel.jsonschema.providers.GsonNode;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;

final class RayPackManifestSchema {
    private static final String RESOURCE = "/caustica/raypacks/api/0.1/pack.schema.json";
    private static final SchemaState SCHEMA = load();

    private RayPackManifestSchema() {
    }

    static void validate(JsonObject manifest, String source) {
        Validator.Result result = SCHEMA.validator().validate(SCHEMA.uri(), manifest);
        if (result.isValid()) {
            return;
        }
        String errors = result.getErrors().stream()
                .sorted(Comparator.comparing(dev.harrel.jsonschema.Error::getInstanceLocation)
                        .thenComparing(dev.harrel.jsonschema.Error::getKeyword))
                .map(error -> location(error.getInstanceLocation()) + ": " + error.getError())
                .distinct()
                .limit(8)
                .reduce((left, right) -> left + "; " + right)
                .orElse("unknown schema violation");
        throw new IllegalArgumentException(source + ": invalid ray-pack manifest: " + errors);
    }

    private static String location(String pointer) {
        return pointer == null || pointer.isEmpty() ? "$" : "$" + pointer;
    }

    private static SchemaState load() {
        try (var input = RayPackManifestSchema.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("missing ray-pack manifest schema " + RESOURCE);
            }
            String schema = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            Validator validator = new ValidatorFactory()
                    .withJsonNodeFactory(new GsonNode.Factory())
                    .createValidator();
            return new SchemaState(validator, validator.registerSchema(schema));
        } catch (IOException e) {
            throw new IllegalStateException("failed to load ray-pack manifest schema " + RESOURCE, e);
        }
    }

    private record SchemaState(Validator validator, URI uri) {
    }
}
