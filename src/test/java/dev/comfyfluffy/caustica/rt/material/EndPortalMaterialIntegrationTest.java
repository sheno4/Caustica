package dev.comfyfluffy.caustica.rt.material;

import com.google.gson.JsonParser;
import dev.comfyfluffy.caustica.TestRegistries;
import dev.comfyfluffy.caustica.api.Feature;
import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.provider.MaterialDefinition;
import dev.comfyfluffy.caustica.api.provider.MaterialRule;
import dev.comfyfluffy.caustica.api.provider.MaterialSink;
import dev.comfyfluffy.caustica.api.provider.MaterialSource;
import dev.comfyfluffy.caustica.engine.material.MaterialCatalog;
import dev.comfyfluffy.caustica.engine.material.MaterialTextureAsset;
import dev.comfyfluffy.caustica.engine.material.MaterialTextureKind;
import dev.comfyfluffy.caustica.engine.material.MaterialUv;
import dev.comfyfluffy.caustica.engine.material.OpenPbrMaterialDefaults;
import dev.comfyfluffy.caustica.engine.material.OpenPbrColorBinding;
import dev.comfyfluffy.caustica.minecraft.MinecraftProvidersExtension;
import dev.comfyfluffy.caustica.minecraft.material.MinecraftMaterialCatalogBuilder;
import dev.comfyfluffy.caustica.minecraft.material.MinecraftMaterialLookup;
import dev.comfyfluffy.caustica.minecraft.provider.MinecraftMaterialSource;
import dev.comfyfluffy.caustica.rt.gen.MaterialBindingData;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EndPortalMaterialIntegrationTest {
    private static final String PORTAL_RULE_PATH = "/assets/caustica/materials/end_portal.json";
    private static final Identifier PORTAL_RULE_ID = Identifier.parse("caustica:materials/end_portal.json");
    private static final Identifier PORTAL_TEXTURE =
            Identifier.parse("minecraft:textures/entity/end_portal/end_portal.png");

    @Test
    void logicalPortalResourceFlowsThroughSourceCatalogOverrideAndBinding() throws Exception {
        var registry = TestRegistries.withBuiltins();
        assertInstanceOf(MinecraftMaterialSource.class,
                registry.materialSources().get(MinecraftMaterialSource.ID));

        int portalSurfaceIndex = registry.surfaceIndex(MinecraftProvidersExtension.END_PORTAL_SURFACE);
        assertTrue(portalSurfaceIndex >= 0);
        Feature.SurfaceImplementation portalSurface = registry.surfaces().get(portalSurfaceIndex);
        assertEquals("caustica_portal_surface", portalSurface.module());
        assertEquals("PortalSurface", portalSurface.type());

        CollectingMaterialSink sink = new CollectingMaterialSink();
        MaterialSource resourcePackSource = submitted -> submitted.submit(loadPortalRule());
        resourcePackSource.submitMaterials(sink);
        assertEquals(1, sink.rules.size());

        Identifier discoveredTexture = materialTextureLocation(sink.rules.getFirst().match().material());
        assertEquals(PORTAL_TEXTURE, discoveredTexture);
        ResourceId logicalPortal = MinecraftMaterialLookup.logicalTexture(discoveredTexture);
        assertEquals(ResourceId.parse("minecraft:entity/end_portal/end_portal"), logicalPortal);
        MaterialTextureAsset portalAsset = new MaterialTextureAsset(logicalPortal,
                MaterialTextureKind.STANDALONE, 1, 1,
                () -> {
                    throw new AssertionError("catalog construction must not open the image");
                }, MaterialUv.IDENTITY, false, false, false,
                OpenPbrColorBinding.BASE_COLOR, OpenPbrColorBinding.BASE_COLOR,
                OpenPbrMaterialDefaults.DEFAULT_SPECULAR_IOR);
        MaterialCatalog catalog = new MaterialCatalog(List.of(), List.of(portalAsset), 16, 15000.0f);
        assertSame(portalAsset, catalog.standalone().getFirst());
        assertEquals(OpenPbrColorBinding.BASE_COLOR, portalAsset.subsurfaceColorBinding());
        assertEquals(OpenPbrColorBinding.BASE_COLOR, portalAsset.emissionColorBinding());

        RtMaterialOverrides overrides = RtMaterialOverrides.from(sink.rules, registry::surfaceIndex);
        RtMaterialOverrides.Rule compiledRule = overrides.rules().getFirst();
        assertTrue(compiledRule.matches(catalog.standalone().getFirst().material(), null));

        RtMaterialDesc runtimeTexture = new RtMaterialDesc(RtMaterialRegistry.TRANSPORT_SURFACE,
                RtMaterialDesc.Source.DERIVED_TEXTURE, 0,
                OpenPbrMaterialDefaults.RUNTIME_TEXTURE_SPECULAR_ROUGHNESS, 0.0f,
                OpenPbrMaterialDefaults.DEFAULT_SPECULAR_IOR, 0.0f,
                RtMaterialDesc.EmissionSource.NONE, 0.0f, RtMaterialDesc.EmissionSummary.NONE,
                RtMaterialRegistry.BUILTIN_SURFACE_IMPLEMENTATION);
        RtMaterialDesc portalMaterial = compiledRule.apply(runtimeTexture);
        assertEquals(portalSurfaceIndex, portalMaterial.surfaceImplementation());

        MaterialBindingData binding = createBinding(23, portalMaterial,
                new float[]{1.0f, 1.0f, 1.0f, 0.0f},
                RtMaterialRegistry.SHARED_ATLAS_BASE_COLOR_TEXTURE_INDEX, 0.1f);
        assertEquals(23, binding.surface());
        assertEquals(portalSurfaceIndex, RtMaterialRegistry.bindingSurfaceImpl(binding.packed0()));
        assertEquals(RtMaterialRegistry.SHARED_ATLAS_BASE_COLOR_TEXTURE_INDEX,
                RtMaterialRegistry.bindingBaseColorTextureIndex(binding.packed0()));
        assertEquals(0, RtMaterialRegistry.bindingCoverage(binding.packed0()));
        assertEquals(0, RtMaterialRegistry.bindingFlags(binding.packed0()));
    }

    private static MaterialRule loadPortalRule() {
        var stream = EndPortalMaterialIntegrationTest.class.getResourceAsStream(PORTAL_RULE_PATH);
        assertNotNull(stream, "missing bundled portal material rule");
        try (stream; var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return MinecraftMaterialSource.parse(
                    JsonParser.parseReader(reader).getAsJsonObject(), PORTAL_RULE_ID);
        } catch (Exception exception) {
            throw new AssertionError("failed to load bundled portal material rule", exception);
        }
    }

    private static MaterialBindingData createBinding(int surfaceId, RtMaterialDesc material,
                                                      float[] average, int baseColorTextureIndex,
                                                      float coverageCutoff) throws Exception {
        Method method = RtMaterialRegistry.class.getDeclaredMethod("binding", int.class,
                RtMaterialDesc.class, float[].class, int.class, float.class);
        assertTrue(method.trySetAccessible(), "binding factory must be accessible to the integration test");
        return (MaterialBindingData) method.invoke(null, surfaceId, material, average,
                baseColorTextureIndex, coverageCutoff);
    }

    private static Identifier materialTextureLocation(ResourceId material) throws Exception {
        Method method = MinecraftMaterialCatalogBuilder.class.getDeclaredMethod(
                "textureLocation", ResourceId.class);
        assertTrue(method.trySetAccessible(), "catalog resource lookup must be accessible to the integration test");
        return (Identifier) method.invoke(null, material);
    }

    private static final class CollectingMaterialSink implements MaterialSink {
        private final List<MaterialDefinition> definitions = new ArrayList<>();
        private final List<MaterialRule> rules = new ArrayList<>();

        @Override
        public void define(MaterialDefinition definition) {
            definitions.add(definition);
        }

        @Override
        public void submit(MaterialRule rule) {
            rules.add(rule);
        }
    }
}
