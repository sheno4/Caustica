# Caustica extension API

Status: current API at `CausticaApi.VERSION`. This document uses exact current Java and Slang names.

## Standalone example extension

`extensions/gltf-viewer` builds as the independent `caustica-gltf-viewer` mod jar. Its Fabric
`caustica` entrypoint and NeoForge `CausticaExtension` service declaration exercise loader discovery;
Caustica does not register the example directly. It compiles only against the `caustica-api` project;
the full Caustica mod is a runtime dependency and embeds those API classes and Slang modules. The
extension jar must not bundle its own copy of the API.

For a published build, use the API only at compile time and require the full mod through Fabric or
NeoForge metadata at runtime:

```groovy
dependencies {
    compileOnly "dev.comfyfluffy.caustica:caustica-api:0.3.0"
}
```

The API artifact deliberately contains only `dev.comfyfluffy.caustica.api` classes, its eight public
Slang authoring modules, and their LWJGL/JOML compile contracts. It contains no Minecraft, loader,
renderer, Slang-runtime, or native implementation.

The `caustica_gltf_viewer:gltf_viewer_anchor` block is only a world-transform anchor. It renders the
immutable scene loaded from `assets/caustica_gltf_viewer/gltf/viewer/model.glb`; resource packs can
replace that exact path with any supported GLB. A pack reload rebuilds the shared scene, and each anchor
adds only its block position to the authored node hierarchy. The viewer never centers, fits, rescales,
or bakes transforms.

The `caustica_gltf_viewer:procedural_surface_anchor` block publishes an API-authored cube at the block
transform. Its material selects the extension-owned `caustica_gltf_viewer:portal_surface` Slang
`ISurfaceModel`, proving that shader source, surface registration, material definition and retained
geometry all cross the standalone jar boundary.

The current subset accepts glTF 2.0 triangle primitives, indexed or implicit indices, node hierarchy,
`POSITION`, `NORMAL`, `TANGENT`, `COLOR_0`, and `TEXCOORD_0`. It supports PNG/JPEG images, standard
base-color, metallic-roughness, normal, and emissive textures, alpha modes and cutoff, plus uniform
`KHR_materials_ior`, `KHR_materials_transmission`, and `KHR_materials_emissive_strength`. Required
extensions outside that set, compressed or sparse geometry, morph targets, transmission textures,
texture coordinates above zero, and non-repeat wrap modes are rejected explicitly. Texture transforms
are unsupported; a required `KHR_texture_transform` extension is rejected, while optional declarations
retain their legal core glTF fallback.

glTF emissive strength is dimensionless while Caustica material emission is photometric. The example
viewer explicitly chooses 4000 cd/m² for unit emissive strength, multiplies it by
`KHR_materials_emissive_strength`, and caps it at 65504 cd/m². The emissive texture and factor remain
multiplicative color terms; a zero emissive factor therefore stays non-emissive.

## Registration vocabulary

- An **extension** implements `CausticaExtension` and registers one or more features.
- A **feature** is the unit shown to users and owns providers, shader implementations, passes and options.
- A **provider** contributes additive scene, light or material data.
- A **slot** selects one implementation. The only current slot is `Slots.SKY`.
- A **surface** is a registered implementation selected per material, not a slot candidate.
- A **surface modifier** is one member of an ordered generated dispatch applied to receiver geometry.
- A **runtime activation** decides whether a feature's activation factories run always or only when the feature
  owns a selected slot. It is separate from shader-program composition. A feature with render passes or
  scene, light or material providers must bind at least one slot to use `SELECTED_SLOT`; registration rejects
  an unbound feature whose runtime contributions could never activate. Use `ALWAYS` for an unbound runtime
  feature. Composition-only surfaces, surface modifiers and options may remain unbound.

All public names use the host-neutral `ResourceId`. UI text uses `DisplayText.literal(...)` or
`DisplayText.translatable(...)`; it never exposes a host text type. Provider identity belongs to the
`FeatureBuilder.sceneProvider`, `lightProvider` or `materialSource` registration, not to the provider
implementation.

Runtime contributions that need feature-local shared state use the contextual registration methods:
`renderPassContextual`, `sceneProviderContextual`, `lightProviderContextual`, and
`materialSourceContextual`. Every contextual factory belonging to one active feature receives the same
`FeatureRuntimeContext`; a later runtime activation receives a new context and new values. Contexts are
never shared between features. Define a typed key and create the value from whichever factory sees it
first:

```java
private static final FeatureRuntimeContext.Key<AssetRepository> ASSETS =
        new FeatureRuntimeContext.Key<>(ResourceId.of("example", "assets"), AssetRepository.class);

builder.sceneProviderContextual(SCENE,
        context -> new CrystalScene(context.getOrCreate(ASSETS, AssetRepository::new)));
builder.materialSourceContextual(MATERIALS,
        context -> new CrystalMaterials(context.getOrCreate(ASSETS, AssetRepository::new)));
```

The ordinary no-argument registration methods remain the simplest choice when contributions do not
share activation state.

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

`CausticaExtension` registers renderer contributions, not Minecraft content. A mod that adds blocks or
items also needs its loader's normal mod initializer, as the standalone example does. Keeping those two
entrypoints separate lets the feature/provider implementation remain loader-neutral.

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
import dev.comfyfluffy.caustica.api.RuntimeActivation;
import dev.comfyfluffy.caustica.api.ShaderSource;
import dev.comfyfluffy.caustica.api.provider.GeometryTransform;
import dev.comfyfluffy.caustica.api.provider.LightProvider;
import dev.comfyfluffy.caustica.api.provider.LightDescriptor;
import dev.comfyfluffy.caustica.api.provider.MaterialDefinition;
import dev.comfyfluffy.caustica.api.provider.MaterialHandle;
import dev.comfyfluffy.caustica.api.provider.MaterialSource;
import dev.comfyfluffy.caustica.api.provider.MaterialTopology;
import dev.comfyfluffy.caustica.api.provider.SceneFrameContext;
import dev.comfyfluffy.caustica.api.provider.SceneGeometrySink;
import dev.comfyfluffy.caustica.api.provider.SceneMesh;
import dev.comfyfluffy.caustica.api.provider.SceneProvider;

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
                .runtimeActivation(RuntimeActivation.ALWAYS)
                .shaderSource(ShaderSource.classpath(ExampleExtension.class,
                        "/example/caustica/shaders", "surface"))
                .surface(SURFACE, "example_crystal_surface", "CrystalSurface")
                .sceneProvider(SCENE, CrystalScene::new)
                .lightProvider(LIGHTS, CrystalLight::new)
                .materialSource(MATERIALS, () -> (MaterialSource) sink ->
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

        private boolean submitted;

        @Override
        public void submitGeometry(SceneFrameContext frame) {
            if (submitted) return;
            SceneMesh mesh = new SceneMesh(
                    new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0},
                    new int[]{0, 1, 2},
                    SceneMesh.UvLayout.PER_VERTEX,
                    new float[]{0, 0, 1, 0, 0, 1},
                    List.of(SceneMesh.TriangleSurface.surface(new MaterialHandle(MATERIAL))));
            frame.geometry().submit(1L, List.of(
                    new SceneGeometrySink.Put(MESH, mesh),
                    new SceneGeometrySink.Place(
                            INSTANCE, MESH, GeometryTransform.translation(0.0, 80.0, 0.0))));
            submitted = true;
        }

        @Override
        public void onWorldChanged() {
            submitted = false;
        }

        @Override
        public void onResourcePackClosing() {
            submitted = false;
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

`SceneProvider`, `MaterialSource`, and `LightProvider` all inherit `ProviderLifecycle`:

```java
default void onWorldChanged() {}
default void onResourcePackClosing() {}
default void onResourcePackApplied() {}
default void stop() {}
default void shutdown() {}
```

For world and resource-pack callbacks, the engine visits material sources first, then scene providers,
then light providers; failure of one provider does not suppress callbacks to the others. In particular,
every successful material-source `onResourcePackApplied()` completes before scene callbacks and before
the next `submitMaterials` collection. Resource-owning material sources can therefore reload or clear
feature-context state without a global singleton or lazy cross-provider fallback.

`SceneProvider` additionally has these callbacks:

```java
default void onMaterialEpoch(MaterialSnapshot materials) {}
default void onMaterialEpochClosing() {}
default void update(SceneGeometryUpdateContext update) {}
default void prepareFrame() {}
default void submitTextures(TextureSink sink) {}
default void submitGeometry(SceneFrameContext frame) {}
```

Geometry group, resident-mesh and placement keys are stable only within the provider. Submit one atomic group
only when its retained state changes. `Put` retains or replaces a `SceneMesh`; `Place` adds or replaces a
world-space placement of one retained mesh; `Transform` changes that placement; `Remove` removes it; and `Drop`
retires mesh data after its placements have been removed. Omitting a previously submitted group means no
change. Repeating an unchanged `Put` pays a real re-upload and acceleration rebuild because the renderer does
not diff mesh bytes. `SceneMesh` carries one material surface per triangle. Positions are mesh-local floats;
translation remains double precision in `GeometryTransform` until renderer rebasing. Optional vertex normals
contain one mesh-local XYZ vector per indexed vertex. Optional vertex colors contain one linear BT.709 RGBA
multiplier per indexed vertex. Empty attribute arrays select the normal and RGB tint stored in each triangle's
`TriangleSurface` instead; the fallback color has alpha one.

A mesh also survives its provider only as long as the provider stays live: the engine releases every mesh
still retained by a provider that stopped (failure or session end) without releasing them itself. The
engine clones submitted arrays, uploads geometry, builds BLAS, writes canonical geometry records and
inserts placements into the TLAS. Minecraft's rounded block clouds use exactly this contract: they retain
their four cloud meshes once and publish only changed cell placements.

Minecraft terrain and animated entities use this same scene-provider contract. Their asynchronous CPU work
publishes keyed atomic geometry groups and observes the ordinary publication callback before updating its
resident state. They contribute textures and receive material semantics through the same supported callbacks
available to any scene provider; there is no privileged Minecraft scene-source interface.

Geometry submitted from `update(SceneGeometryUpdateContext)` is treated as retained static content and may be
compacted or receive renderer-selected opacity acceleration. Geometry submitted from `submitGeometry` is treated
as frame-dynamic content. Providers describe content and lifetime; acceleration policy remains renderer-owned.

`submitTextures(TextureSink)` publishes each source-local texture reference at most once per resource epoch,
before geometry that references it. `CpuTextureResource` copies immutable RGBA8 pixels. A
`BorrowedVulkanTexture` is a supported zero-copy option for providers that already own a Vulkan image; keep its
view and declared layout alive until the renderer invokes its retirement callback. Texture descriptor indices
are renderer-private and cannot be observed by the provider.

`onMaterialEpoch(MaterialSnapshot)` publishes an immutable, thread-safe semantic snapshot. Workers may retain
that snapshot until their current jobs finish, but must stop dispatching against it when
`onMaterialEpochClosing()` runs. `MaterialSnapshot.analyze(...)` returns epoch-cached material analysis without
allocating or exposing compiled binding and texture indices.

## Light providers

`LightProvider.submitLights(LightSink)` submits the provider's complete current-frame light snapshot.
Omitted keys disappear immediately, and duplicate keys within one provider reject that provider snapshot.

`LightProvider.retainedLights()` returns the provider's complete immutable `RetainedLightCollection`. Each
group has a source-local key and revision. Change the collection generation whenever group membership or a
group revision changes; unchanged generations are reused in O(1) without traversing or revalidating their
groups. Keep a group revision unchanged while its light list is unchanged and change it when replacing that
list. Omitted group keys are removed in the next generation. The renderer qualifies keys by provider identity,
so independent providers may use the same local key.

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
- `submit(MaterialRule)` for an ordered override of a texture material and optional geometry key;
- `submitAsset(MaterialTextureAsset)` for a neutral texture asset compiled in the same epoch.

`MaterialDefinition` is the complete named declaration. Its uniform colors are scene-linear ACEScg and
its optional `MaterialTextureAsset` supplies semantic per-texel streams under the same material handle.
The renderer multiplies sampled base color, roughness, metalness, subsurface weight, emissive RGB and
emission weight by the corresponding uniform factors. A null `surface` selects the built-in surface;
otherwise it names a registered `ISurfaceModel` implementation. All declaration, texture-source, image,
texel, color-binding and UV types are in `dev.comfyfluffy.caustica.api.provider`.

`MaterialTopology` is explicit:

- `SURFACE` selects ordinary surface/thin-sheet closure transport;
- `MEDIUM_BOUNDARY` creates an interface tracked by the engine medium stack.

Topology is independent of `transmissionWeight`. A `SURFACE` with nonzero transmission remains a thin
sheet. A `MEDIUM_BOUNDARY` does not become a surface when transmission is zero. `alphaCutoff` belongs to
the definition, while geometry/binding derivation still selects opaque, cutout or stochastic coverage.

Semantic texture flags declare the authored streams: `surfaceParameters` enables roughness, metalness,
IOR and subsurface weight; `normalMap` enables tangent-space normals; and `emissionMask` enables both the
scalar emission weight and independent linear-BT.709 emissive RGB. The emissive texture is multiplied by
the definition's ACEScg `emissionColor` and `emissionLuminanceCdM2`. Set an attached asset's
`uniformEmissionLuminanceCdM2` to zero because the definition owns that uniform; the asset-level value is
only used when an asset is submitted without a matching definition.

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
                SURFACE,     // registered surface, or null
                MaterialTopology.SURFACE))); // explicit topology override, or null
```

The first matching rule owns the material. The host adapter decides how its resource system maps to
`ResourceId` and is responsible for ordering specificity and resource priority before submission.
Minecraft translates resource-pack JSON and logical texture/block IDs here; those types never cross into
the engine material API.

Each standalone texture asset owns its source and optional uniform-emission calibration. Independent material sources do
not negotiate host-wide atlas or luminance policy. Integer material bindings, surface indices, bindless
texture indices and compiled pages are private to a resource epoch. Geometry retains `MaterialHandle`, never
an integer ID.

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
Passes at the same stage record in registration order.
`PassSetup.device()` returns the supported `GpuDevice` capability; passes create buffers and images through
it and destroy what they create after the last referencing frame completes. Frame-provided images are
renderer-owned and must not be retained across resize or destroyed. `PassFrame.gpuUse()` can retire resources
after the current frame completes, but must never be awaited while recording that same frame.
`PassSetup.shaderCompiler()` compiles and reflects pass-owned Slang modules through the host runtime without
exposing that runtime's implementation types.
`GpuDevice.rasterCapabilities()` exposes device-compatible wide-line limits and the preferred color sample
count for pass-local raster pipelines without exposing host device negotiation.
`PassFrame.sceneColor()` and `sceneColorTarget()` form an ordered scene-referred post chain. World shaders
consume pass resources published into reflected descriptor set 2; the compiler validates name, binding and
resource kind. Set 0 remains engine-owned and set 1 is the bindless texture array.

Compiler-generated Java shader records are private ABI serializers owned by their pass or renderer package;
their generated packages are not extension API. Extension passes should keep semantic inputs in their own
types and treat any generated serializer they own as an implementation detail.

Use `passResourceModule(moduleName)` for a module containing global shader resources. The generated
non-generic anchor imports it so Slang composition types remain valid.

Register a pass with its stable id, stage and factory, for example
`renderPass(MY_PASS, RenderStage.BEFORE_TRACE, MyPass::new)`. Its `onResourcePackClosing`,
`onResourcePackApplied` and `onWorldChanged` callbacks have the same epoch meaning as provider callbacks.

## Lifecycle and failure isolation

Features register factories, not live render passes or providers. Each factory creates one
runtime-activation-scoped instance after RT is requested; `destroy`/`shutdown` ends that instance before the
next activation can create a replacement. Use `runtimeActivation(RuntimeActivation.ALWAYS)` for host bridges
that must run for every session. `SELECTED_SLOT` is the default and constructs runtime contributions only
when the feature owns a currently selected slot. Registering render passes or providers with this mode therefore
requires at least one slot binding. Surface and surface-modifier owners still participate in program composition
regardless of this choice and need no slot binding unless they also declare runtime contributions. Selecting a
different slot owner replaces its child runtime activation only after its candidate program is ready, while the
parent render session and process-scoped programs remain available.

Material, texture, geometry and light contributions are isolated per provider. Staging completes and
validates before a contribution becomes visible; an exception disables only that provider, discards or
retires its staged resources and invokes `stop`.

- `onResourcePackClosing` drops references to the old pack before host images are destroyed;
  `onResourcePackApplied` observes the replacement pack after it becomes active.
- `onWorldChanged` invalidates scene state when a render session enters or leaves a world.
- `stop` stops production before outstanding GPU work is drained.
- `shutdown` releases provider-owned state after GPU idle.
- Scene providers additionally receive `update` and `prepareFrame`.

Material bindings and named-material resolutions are epoch-local. Do not cache integer IDs across reloads.
Retain `ResourceId` / `MaterialHandle` and resolve within the frame or resource snapshot supplied by the
engine.

## Boundary feedback

Shader resources must be anchored to a class owned by the contributing mod. A path alone works only
while every mod shares Caustica's class loader, so `ShaderSource.classpath(anchor, root, ...)` carries
resource ownership explicitly. Extension colors likewise cross through public `ColorSpaces` helpers
because material constants are ACEScg while common asset formats author in sRGB or linear BT.709.
Slang imports expose public declarations in one composition namespace, so registered binding, surface
and modifier type names must be globally unique across modules. The registry rejects collisions during
extension registration rather than letting an ambiguous symbol fail world-program compilation.

The public Java signatures are implementation-neutral: light and material value types live under
`api.provider`, option lookup exposes only API views, and pass shader compilation is supplied as a host
service. Gradle compiles those contracts as the independent `caustica-api` artifact, the root mod embeds
that artifact exactly once, and the standalone example has only `compileOnly` access to it. Source and
jar checks supplement the module boundary by rejecting implementation references and accidental API
shading in the extension.

Both Caustica and the example are currently client-only mods. Their blocks work in integrated
single-player, but a dedicated server cannot register or persist them until content registration is
split into a common-side artifact that does not depend on the renderer.

## Landed proof consumers

- rounded block clouds: `SceneProvider`, retained `SceneMesh`, named material;
- standalone glTF viewer: real loader discovery, resource-reloadable retained meshes, authored node
  instances, semantic textures, vertex normals and linear RGBA colors;
- standalone procedural block: extension-owned Slang source and per-material `ISurfaceModel` dispatch;
- spotlight helmet: `LightProvider` and `LightDescriptor.Spot`;
- sun and moon: ordinary `LightDescriptor.Distant` values;
- end portal: host material rule selecting a registered procedural surface;
- water: explicit `MEDIUM_BOUNDARY` plus `evaluateMedium` and `evaluateMediumLighting`;
- block damage: pass-published resources plus ordered `ISurfaceModifier` dispatch;
- lava and particles: ordinary source-owned material resolution and generic binding derivation.

Visual verification remains appropriate for procedural animation, medium response, projected modifiers,
light direction/cones and resource-reload material changes even when API, reflection and ABI tests pass.
