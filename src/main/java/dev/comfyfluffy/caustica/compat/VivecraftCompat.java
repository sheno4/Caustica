package dev.comfyfluffy.caustica.compat;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.comfyfluffy.caustica.CausticaMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.util.LightCoordsUtil;
import org.joml.Matrix4f;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Optional access to the Vivecraft eye pass and its deferred world overlays. */
public final class VivecraftCompat {
    public enum View {
        DESKTOP, LEFT, RIGHT, AUXILIARY;

        public boolean isEye() {
            return this == LEFT || this == RIGHT;
        }
    }

    private VivecraftCompat() {}

    private static final class Access {
        static final Field RUNNING = resolveRunning();
        static final Hooks HOOKS = RUNNING == null ? null : resolveHooks();
    }

    public static boolean isVrActive() {
        try {
            return Access.RUNNING != null && Access.RUNNING.getBoolean(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot read Vivecraft VR state", e);
        }
    }

    public static View view() {
        if (!isVrActive()) return View.DESKTOP;
        Hooks hooks = Access.HOOKS;
        if (hooks == null) return View.AUXILIARY;
        try {
            if ((boolean) hooks.isVanilla.invoke(null)) return View.AUXILIARY;
            return switch (((Enum<?>) hooks.pass.get(hooks.holder.invoke(null))).name()) {
                case "LEFT" -> View.LEFT;
                case "RIGHT" -> View.RIGHT;
                default -> View.AUXILIARY;
            };
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot read the Vivecraft render pass", e);
        }
    }

    public static void deferEyeOverlays(LevelRenderState levelState) {
        Hooks hooks = Access.HOOKS;
        try {
            Object state = hooks.levelVrState.invoke(levelState);
            hooks.uiAfterWorld.setBoolean(state, true);
            hooks.handsSecond.setBoolean(hooks.armsState.get(state), true);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot defer Vivecraft eye overlays", e);
        }
    }

    /** The eye retains Vivecraft's raster depth for native overlay occlusion. */
    public static void restoreEyeEffects(SubmitNodeStorage storage, LevelRenderState levelState,
                                         FeatureRenderDispatcher dispatcher) {
        Hooks hooks = Access.HOOKS;
        var modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix().identity();
        try {
            Object state = hooks.levelVrState.invoke(levelState);
            // These screen surfaces carry UI colors and should be independent of world lightmaps.
            for (Field screen : hooks.screens) {
                hooks.lightCoords.setInt(screen.get(state), LightCoordsUtil.FULL_BRIGHT);
            }
            hooks.applyModelView.invoke(null, hooks.statePass.get(state), modelView);
            hooks.renderEffects.invoke(null, storage, levelState, new PoseStack(), false);
            dispatcher.renderAllFeatures(storage);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot restore Vivecraft eye effects", e);
        } finally {
            modelView.popMatrix();
        }
    }

    private static Field resolveRunning() {
        if (!FabricLoader.getInstance().isModLoaded("vivecraft")) return null;
        try {
            return load("client_vr.VRState").getField("VR_RUNNING");
        } catch (ReflectiveOperationException | LinkageError e) {
            CausticaMod.LOGGER.warn("Vivecraft VR state is unavailable", e);
            return null;
        }
    }

    private static Hooks resolveHooks() {
        try {
            Class<?> holder = load("client_vr.ClientDataHolderVR");
            Class<?> pass = load("api.client.data.RenderPass");
            Class<?> state = load("client_vr.render.renderstates.VRRenderState");
            Field[] screens = { state.getField("guiState"), state.getField("radialState"), state.getField("keyboardState") };
            return new Hooks(holder.getMethod("getInstance"), holder.getField("currentPass"),
                    load("client_xr.render_pass.RenderPassType").getMethod("isVanilla"),
                    load("client.extensions.LevelRenderStateExtension").getMethod("vivecraft$getVRRenderState"),
                    state.getField("currentPass"), state.getField("uiAfterWorld"), state.getField("armsState"),
                    load("client_vr.render.renderstates.ArmsRenderState").getField("handsSecond"), screens,
                    screens[0].getType().getField("lightCoords"),
                    load("client_vr.render.helpers.RenderHelper").getMethod("applyVRModelView", pass, Matrix4f.class),
                    load("client_vr.render.helpers.VREffectsHelper").getMethod("renderVrFast",
                            SubmitNodeCollector.class, LevelRenderState.class, PoseStack.class, boolean.class));
        } catch (ReflectiveOperationException | LinkageError e) {
            CausticaMod.LOGGER.warn("Vivecraft eye integration is unavailable; keeping native VR rendering", e);
            return null;
        }
    }

    private static Class<?> load(String name) throws ClassNotFoundException {
        return Class.forName("org.vivecraft." + name, false, VivecraftCompat.class.getClassLoader());
    }

    private record Hooks(Method holder, Field pass, Method isVanilla, Method levelVrState, Field statePass,
                         Field uiAfterWorld, Field armsState, Field handsSecond, Field[] screens,
                         Field lightCoords, Method applyModelView, Method renderEffects) {}
}
