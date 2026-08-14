# Caustica extension API

Status: current API at `CausticaApi.VERSION`. This document uses exact current Java and Slang names.

## Registration vocabulary

- An **extension** implements `CausticaExtension` and registers one or more features.
- A **feature** is the unit shown to users and owns providers, shader implementations, passes and options.
- A **provider** contributes additive scene, light or material data.
- A **slot** selects one implementation. The only current slot is `Slots.SKY`.
- A **surface** is a registered implementation selected per material, not a slot candidate.
- A **surface modifier** is one member of an ordered generated dispatch applied to receiver geometry.

All public names use the host-neutral `ResourceId`. UI text uses `DisplayText.literal(...)` or
`DisplayText.translatable(...)`; it never exposes a host text type. Provider identity belongs to the
`FeatureBuilder.sceneProvider`, `lightProvider` or `materialSource` registration, not to the provider
implementation.

## Loader registration

On Fabric, expose the extension through the `caustica` entrypoint in `fabric.mod.json`:

```json
"entrypoints": {
  "caustica": ["example.caustica.CrystalExtension"]
}
```

On NeoForge, expose the same implementation through Java's `ServiceLoader` by listing its binary class
name in `META-INF/services/dev.comfyfluffy.caustica.api.CausticaExtension`. The extension implementation
and all provider code remain loader-neutral.

## A compile-valid Java feature

The following feature registers a named procedural material, retained triangle geometry and a spot light.
Its Slang surface is shown in the next section.

```java
package example.caustica;

import dev.comfyfluffy.caustica.api.CausticaExtension;
import dev.comfyfluffy.caustica.api.CausticaRegistry;
import dev.comfyfluffy.caustica.api.DisplayText;
import dev.comfyfluffy.caustica.api.FeatureCategory;
import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.ShaderSource;
import dev.comfyfluffy.caustica.api.provider.GeometryTransform;
import dev.comfyfluffy.caustica.api.provider.LightProvider;
import dev.comfyfluffy.caustica.api.provider.MaterialDefinition;
import dev.comfyfluffy.caustica.api.provider.MaterialHandle;
import dev.comfyfluffy.caustica.api.provider.MaterialSource;
import dev.comfyfluffy.caustica.api.provider.MaterialTopology;
import dev.comfyfluffy.caustica.api.provider.SceneProvider;
import dev.comfyfluffy.caustica.api.provider.TriangleMesh;
import dev.comfyfluffy.caustica.engine.light.LightDescriptor;

import java.util.List;

public final class ExampleExtension implements CausticaExtension {
    private static final ResourceId FEATURE = ResourceId.of("example", "crystal_feature");
    private static final ResourceId SCENE = ResourceId.of("example", "crystal_scene");
    private static final ResourceId LIGHTS = ResourceId.of("example", "crystal_lights");
    private static final ResourceId MATERIALS = ResourceId.of("example", "crystal_materials");
    private static final ResourceId MATERIAL = ResourceId.of("example", "crystal");
    private static final ResourceId SURFACE = ResourceId.of("example", "crystal_surface");

    @Override
    public void register(CausticaRegistry registry) {
        registry.feature(FEATURE)
                .title(DisplayText.literal("Crystal proof"))
                .description(DisplayText.translatable("feature.example.crystal.description"))
                .category(FeatureCategory.GEOMETRY)
                .shaderSource(ShaderSource.classpath("/example/caustica/shaders", "surface"))
                .surface(SURFACE, "example_crystal_surface", "CrystalSurface")
                .sceneProvider(SCENE, new CrystalScene())
                .lightProvider(LIGHTS, new CrystalLight())
                .materialSource(MATERIALS, (MaterialSource) sink ->
                        sink.define(new MaterialDefinition(
                                new MaterialHandle(MATERIAL),
                                0.35f, 0.65f, 1.0f,
                                0.12f, 0.0f, 1.52f, 0.8f,
                                MaterialTopology.SURFACE, SURFACE)))
                .register();
    }

    private static final class CrystalScene implements SceneProvider {
        private static final long MESH = 1L;
        private static final long INSTANCE = 1L;

        @Override
        public void submitGeometry(dev.comfyfluffy.caustica.api.provider.SceneGeometrySink sink) {
            TriangleMesh mesh = new TriangleMesh(
                    new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0},
                    new float[]{0, 0, 1, 0, 0, 1},
                    new int[]{0, 1, 2},
                    List.of(new TriangleMesh.MaterialRange(
                            0, 1, new MaterialHandle(MATERIAL))));
            sink.retainMesh(MESH, mesh);
            sink.instance(INSTANCE, MESH, GeometryTransform.translation(0.0, 80.0, 0.0));
        }
    }

    private static final class CrystalLight implements LightProvider {
        @Override
        public void submitLights(dev.comfyfluffy.caustica.api.provider.LightSink sink) {
            sink.submit(new LightDescriptor.Spot(
                    1L, 0.0, 82.0, 0.0,
                    0.0, -1.0, 0.0,
                    24.0, Math.toRadians(20.0),
                    500.0, 550.0, 650.0));
        }
    }
}
```

The explicit nested classes show the exact callback signatures; provider interfaces also supply
lifecycle defaults.

## A complete surface implementation

Every registered surface implements all four methods. Neutral medium methods are still required because
the generated dispatch has one uniform interface for every material.

```slang
module example_crystal_surface;

import caustica_surface;

public struct CrystalSurface : ISurfaceModel {
    public void evaluateSurface(SurfaceInput input, inout MaterialInput material) {
        float pulse = 0.5 + 0.5 * sin(input.time * 2.0);
        material.emissionColor = float3(0.25, 0.55, 1.0);
        material.emissionLuminance = 20.0 * pulse;
    }

    public float3 evaluateResponse(float3 radiance, SurfaceClosure surface) {
        return radiance;
    }

    public MediumProperties evaluateMedium(MediumInput input) {
        MediumProperties medium;
        medium.extinction = float3(0.0);
        medium.guideTransmittance = float3(1.0);
        medium.transmissionRayBias = 0.0;
        medium.flags = 0u;
        return medium;
    }

    public float3 evaluateMediumLighting(MediumLightingInput input) {
        return float3(1.0);
    }
}
```

`evaluateSurface` edits a fully initialized `MaterialInput`; leave fields you do not own unchanged.
`evaluateResponse` is a final response hook, not a replacement BSDF. The engine owns closure construction,
BSDF evaluation/sampling/pdf agreement, direct-light estimators, guide generation and transport.

`evaluateMedium` is called through the material's registered surface implementation for structural
`MEDIUM_BOUNDARY` crossings. It returns extinction, reconstruction guide transmittance, ray bias and
`MEDIUM_TERMINATE_ON_MISS` / `MEDIUM_BOUNDARY_LIGHTING` flags. `evaluateMediumLighting` is a
multiplicative response for a tracked boundary direct-light segment. Return one for no effect. Medium
behavior uses the same registered surface selected by the material.

## Scene providers

`SceneProvider` has these callbacks:

```java
default void update() {}
default void prepareFrame() {}
default void submitGeometry(SceneGeometrySink sink) {}
default void invalidate() {}
default void onResourceReload() {}
default void stop() {}
default void shutdown() {}
```

`submitGeometry` is a complete desired retained snapshot for that provider. Keys are stable only within
the provider. Call `retainMesh(meshKey, mesh)` before any `instance(instanceKey, meshKey, transform)` that
references it. Material ranges are contiguous triangle spans and cover the whole mesh. Positions are
mesh-local floats; translation remains double precision in `GeometryTransform` until renderer rebasing.

Omitting a prior mesh or instance retires it after its last graphics use. The engine clones submitted
arrays, uploads geometry, builds BLAS, writes canonical geometry records and inserts instances into the
TLAS. Minecraft's rounded block clouds use exactly this contract.

Minecraft terrain and animated entities use an internal optimized `RtSceneSource` implemented by their
registered scene provider. That seam is not public extension API; it exists to retain asynchronous chunk
meshing, entity refit and bindless host texture behavior while still merging into the same geometry table
and TLAS.

## Light providers

`LightProvider.submitLights(LightSink)` submits the provider's complete current-frame light snapshot.
Omitted keys disappear immediately, and duplicate keys within one provider reject that provider snapshot.

Available descriptors and units:

| Descriptor | Position/direction | Radiometry |
|---|---|---|
| `Rectangle` | center, two half-edge vectors and one-sided normal | ACEScg radiance, cd/m² |
| `Point` | center and finite range in metres | ACEScg intensity, candela |
| `Spot` | center, normalized direction, finite range and outer half-angle | ACEScg intensity, candela |
| `Distant` | normalized direction toward source and angular radius | ACEScg normal illuminance, lux |

Finite descriptors enter the finite light BVHs. Distant descriptors share the unified light selection
distribution but do not pretend to have a finite position. Minecraft's sun and moon are `Distant`; the
spotlight helmet is `Spot`. These finite and distant records are the public light structures.

## Material sources

`MaterialSource.submitMaterials(MaterialSink)` runs at the resource-epoch boundary. A source may:

- `define(MaterialDefinition)` for a stable named material used by provider geometry;
- `submit(MaterialRule)` for an ordered override of a host-catalog material and optional geometry key.

Definitions are textureless named values. Their base color is scene-linear ACEScg. A null `surface`
selects the built-in surface; otherwise it names a registered `ISurfaceModel` implementation.

`MaterialTopology` is explicit:

- `SURFACE` selects ordinary surface/thin-sheet closure transport;
- `MEDIUM_BOUNDARY` creates an interface tracked by the engine medium stack.

Topology is independent of `transmissionWeight`. A `SURFACE` with nonzero transmission remains a thin
sheet. A `MEDIUM_BOUNDARY` does not become a surface when transmission is zero. Coverage is likewise not
part of `MaterialDefinition`; geometry/binding derivation selects opaque, cutout or stochastic coverage.

Rules use source-neutral identifiers:

```java
sink.submit(new MaterialRule(
        ResourceId.of("example", "polished_override"),
        new MaterialRule.Match(
                ResourceId.of("host", "materials/polished"),
                null),
        new MaterialRule.Parameters(
                0.08f,       // specularRoughness
                null,        // inherit metalness
                1.52f,       // specularIor
                null,        // inherit transmissionWeight
                null,        // inherit emission luminance
                SURFACE)));  // registered surface, or null
```

The first matching rule owns the material. The host adapter decides how its resource system maps to
`ResourceId` and is responsible for ordering specificity and resource priority before submission.
Minecraft translates resource-pack JSON and logical texture/block IDs here; those types never cross into
the engine material API.

Integer material bindings, surface indices, bindless texture indices and compiled pages are private to a
resource epoch. Geometry retains `MaterialHandle`, never an integer ID.

## OpenPBR Surface 1.1.1 subset

The public `MaterialInput` field names use OpenPBR meanings. The current writable subset is:

- `geometryNormal` and Caustica's reconstruction-only `previousGeometryNormal`;
- `baseColor`, `baseMetalness`;
- perceptual `specularRoughness`, `specularIor`;
- `transmissionWeight`, `transmissionColor`;
- `subsurfaceWeight`, `subsurfaceColor`, `subsurfaceScatterAnisotropy`;
- `emissionColor`, `emissionLuminance` in cd/m².

Defaults for fields not authored by the source are `transmission_color=(1,1,1)`,
`subsurface_weight=0`, `subsurface_color=(0.8,0.8,0.8)`,
`subsurface_scatter_anisotropy=0`, and zero emission luminance. The engine initializes emission color from
the canonical source base color before a procedural surface may replace it.

Supported closure behavior includes rough dielectric/metal reflection, a rough straight-through thin
dielectric BTDF for thin-sheet `transmission_weight`, and a thin dense-scattering subsurface sheet.
Subsurface anisotropy divides its energy between diffuse reflection and diffuse transmission. All active
lobes participate in the engine's shared evaluate/sample/pdf mixture.

The following OpenPBR parameters are fixed at specification defaults rather than exposed:

- `base_weight=1`, `base_diffuse_roughness=0`;
- `specular_weight=1`, `specular_color=(1,1,1)`, specular anisotropy zero;
- `transmission_depth=0`, transmission scatter/anisotropy/dispersion zero;
- coat, fuzz and thin-film weights zero.

`specular_color` is intentionally unsupported: dielectric F0 derives from IOR, metal F0 from base color,
and conductor F82 edge tint is not implemented. `geometry_opacity` is structural coverage and
`geometry_thin_walled` is `MaterialTopology`; neither is a writable `MaterialInput`. There is no
`materialTags` field. Coat, fuzz, anisotropic specular, thin film, dispersion, displacement and full
volumetric subsurface are not silently approximated.

Minecraft owns its source policy: NativeImage/atlas and resource discovery, borrowed/owned image lifetime,
LabPBR decoding, emission inference, IOR lookup and lighting calibration. It supplies semantic
`OpenPbrTextureTexel` values and a fixed-resolution `EmissionFootprint`; `RtMaterialPageCompiler` alone
packs renderer pages and GPU channels.

## Surface modifiers

Register a modifier with:

```java
builder.surfaceModifier(
        ResourceId.of("example", "projected_mark"),
        "example_projected_mark",
        "ProjectedMark");
```

The Slang type implements:

```slang
public struct ProjectedMark : ISurfaceModifier {
    public void applySurfaceModifier(
            SurfaceModifierInput input,
            inout MaterialInput material) {
        material.specularRoughness = max(material.specularRoughness, 0.8);
    }
}
```

The composition generator calls registered modifiers sequentially in registration order, after the
selected surface evaluates, and only for geometry marked as a modifier receiver. A modifier needing GPU
state publishes a reflected world resource from a render pass and anchors its bindings module with
`FeatureBuilder.passResourceModule(...)`. Minecraft block damage is the proof implementation: its pass
captures damage entries and textures, while its modifier projects the current crack stage onto receiver
terrain. No block semantics exist in the renderer.

## Render passes and shader resources

`CausticaRenderPass` owns its Vulkan resources. `create(PassSetup)` allocates and publishes persistent
state, `resize` handles extent changes, and `record(PassFrame)` records work at its `RenderStage`.
`PassFrame.sceneColor()` and `sceneColorTarget()` form an ordered scene-referred post chain. World shaders
consume pass resources published into reflected descriptor set 2; the compiler validates name, binding and
resource kind. Set 0 remains engine-owned and set 1 is the bindless texture array.

Use `passResourceModule(moduleName)` for a module containing global shader resources. The generated
non-generic anchor imports it so Slang composition types remain valid.

## Lifecycle and failure isolation

Provider callbacks are transactional. Staging completes and validates before a geometry, light or
material snapshot becomes visible. An exception disables only that provider and invokes `stop`.

- `onResourceReload` invalidates host/resource-derived state.
- `stop` stops production before outstanding GPU work is drained.
- `shutdown` releases provider-owned state after GPU idle.
- Scene providers additionally receive `update`, `prepareFrame` and `invalidate`.

Material bindings and named-material resolutions are epoch-local. Do not cache integer IDs across reloads.
Retain `ResourceId` / `MaterialHandle` and resolve within the frame or resource snapshot supplied by the
engine.

## Landed proof consumers

- rounded block clouds: `SceneProvider`, retained `TriangleMesh`, named material;
- spotlight helmet: `LightProvider` and `LightDescriptor.Spot`;
- sun and moon: ordinary `LightDescriptor.Distant` values;
- end portal: host material rule selecting a registered procedural surface;
- water: explicit `MEDIUM_BOUNDARY` plus `evaluateMedium` and `evaluateMediumLighting`;
- block damage: pass-published resources plus ordered `ISurfaceModifier` dispatch;
- lava and particles: ordinary source-owned material resolution and generic binding derivation.

Visual verification remains appropriate for procedural animation, medium response, projected modifiers,
light direction/cones and resource-reload material changes even when API, reflection and ABI tests pass.
