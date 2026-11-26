package dev.comfyfluffy.caustica.compat;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.comfyfluffy.caustica.CausticaMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Consumer;

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

    private static final class OverlayAccess {
        static final OverlayOptions OPTIONS = hasEyeIntegration() ? resolveOverlayOptions() : null;
    }

    private static final class HeadAccess {
        static HeadPose pose = hasEyeIntegration() ? resolveHeadPose() : null;
    }

    private static final class HandAccess {
        static HandHooks hooks = hasEyeIntegration() ? resolveHandHooks() : null;
        static HiddenHands hidden;
        static BlockState heldBlockState;
    }

    /** Block-state semantics for the held item's actual draw submission on the render thread. */
    public static BlockState heldBlockState() {
        return HandAccess.heldBlockState;
    }

    public static boolean hasEyeIntegration() {
        return Access.HOOKS != null;
    }

    public static Matrix4f headViewRotation() {
        HeadPose pose = HeadAccess.pose;
        if (pose == null) return null;
        try {
            return (Matrix4f) pose.modelView.invoke(null, pose.headPass);
        } catch (ReflectiveOperationException e) {
            HeadAccess.pose = null;
            CausticaMod.LOGGER.warn("Cannot read Vivecraft head pose; using the normal eye renderer", e);
            return null;
        }
    }

    public static Boolean highResolutionOverlay() {
        OverlayOptions options = OverlayAccess.OPTIONS;
        if (options == null) return null;
        try {
            Object settings = options.settings.get(Access.HOOKS.holder.invoke(null));
            return settings == null ? null : options.doubleResolution.getBoolean(settings);
        } catch (ReflectiveOperationException e) {
            CausticaMod.LOGGER.warn("Cannot read Vivecraft overlay quality", e);
            return null;
        }
    }

    public static void setHighResolutionOverlay(boolean highResolution) {
        OverlayOptions options = OverlayAccess.OPTIONS;
        if (options == null) return;
        try {
            Object settings = options.settings.get(Access.HOOKS.holder.invoke(null));
            if (settings != null && options.doubleResolution.getBoolean(settings) != highResolution) {
                // Vivecraft's option setter persists the value and schedules its GUI target resize.
                options.setOption.invoke(settings, options.doubleResolutionOption);
            }
        } catch (ReflectiveOperationException e) {
            CausticaMod.LOGGER.warn("Cannot set Vivecraft overlay quality", e);
        }
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

    public static Consumer<SubmitNodeCollector> captureEyeHands(LevelRenderState levelState) {
        HandHooks hands = HandAccess.hooks;
        Hooks hooks = Access.HOOKS;
        if (hands == null || hooks == null) return null;
        try {
            Object state = hooks.levelVrState.invoke(levelState);
            Object arms = hooks.armsState.get(state);
            if (!hands.renderHands.getBoolean(arms) || hands.mainMenuHand.getBoolean(arms)
                    || hands.offMenuHand.getBoolean(arms)) return null;
            BlockState mainBlock = heldBlock((ItemStack) hands.mainItem.get(arms));
            BlockState offBlock = heldBlock((ItemStack) hands.offItem.get(arms));
            Object teleport = hands.teleportState.get(state);
            return collector -> {
                try {
                    boolean energy = hands.teleportEnergy.getBoolean(teleport);
                    boolean arc = hands.teleportArc.getBoolean(teleport);
                    try {
                        // These draws belong to the native overlay pass, which also owns their blend state.
                        hands.teleportEnergy.setBoolean(teleport, false);
                        hands.teleportArc.setBoolean(teleport, false);
                        if (mainBlock == null && offBlock == null) {
                            hands.submit.invoke(null, collector, state, levelState.cameraRenderState,
                                    new PoseStack(), true, true, false, false, 0);
                        } else {
                            submitHand(hands, collector, state, levelState.cameraRenderState, true, mainBlock);
                            submitHand(hands, collector, state, levelState.cameraRenderState, false, offBlock);
                        }
                    } finally {
                        hands.teleportEnergy.setBoolean(teleport, energy);
                        hands.teleportArc.setBoolean(teleport, arc);
                    }
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("Cannot submit Vivecraft hand geometry", e);
                }
            };
        } catch (ReflectiveOperationException e) {
            HandAccess.hooks = null;
            CausticaMod.LOGGER.warn("Cannot read Vivecraft hand state. Using native hands.", e);
            return null;
        }
    }

    private static BlockState heldBlock(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem blockItem)) return null;
        BlockState state = blockItem.getBlock().defaultBlockState();
        var properties = stack.get(DataComponents.BLOCK_STATE);
        return properties == null ? state : properties.apply(state);
    }

    private static void submitHand(HandHooks hooks, SubmitNodeCollector collector, Object state,
                                   CameraRenderState camera, boolean mainHand, BlockState heldBlock)
            throws ReflectiveOperationException {
        BlockState prior = HandAccess.heldBlockState;
        HandAccess.heldBlockState = heldBlock;
        try {
            hooks.submit.invoke(null, collector, state, camera, new PoseStack(), mainHand, !mainHand,
                    false, false, 0);
        } finally {
            HandAccess.heldBlockState = prior;
        }
    }

    public static void hideCapturedHands(LevelRenderState levelState) {
        HandHooks hands = HandAccess.hooks;
        Hooks hooks = Access.HOOKS;
        if (hands == null || hooks == null) return;
        try {
            Object arms = hooks.armsState.get(hooks.levelVrState.invoke(levelState));
            if (hands.renderHands.getBoolean(arms)) {
                HandAccess.hidden = new HiddenHands(hands, arms, hands.skipMain.getBoolean(arms),
                        hands.skipOff.getBoolean(arms));
                // The offhand helper still submits teleport overlays with the model skipped.
                hands.skipMain.setBoolean(arms, true);
                hands.skipOff.setBoolean(arms, true);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot hide the captured Vivecraft hand draw", e);
        }
    }

    public static void restoreCapturedHands() {
        HiddenHands hidden = HandAccess.hidden;
        if (hidden == null) return;
        HandAccess.hidden = null;
        try {
            hidden.hooks.skipMain.setBoolean(hidden.arms, hidden.skipMain);
            hidden.hooks.skipOff.setBoolean(hidden.arms, hidden.skipOff);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot restore the Vivecraft hand state", e);
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

    private static OverlayOptions resolveOverlayOptions() {
        try {
            Class<?> holder = Access.HOOKS.holder.getReturnType();
            Field settings = holder.getField("vrSettings");
            Class<?> options = load("client_vr.settings.VRSettings$VrOptions");
            return new OverlayOptions(settings, settings.getType().getField("doubleGUIResolution"),
                    settings.getType().getMethod("setOptionValue", options),
                    options.getField("DOUBLE_GUI_RESOLUTION").get(null));
        } catch (ReflectiveOperationException | LinkageError e) {
            CausticaMod.LOGGER.warn("Vivecraft overlay quality option is unavailable", e);
            return null;
        }
    }

    private static HeadPose resolveHeadPose() {
        try {
            Class<?> pass = load("api.client.data.RenderPass");
            Method modelView = load("client_vr.render.helpers.RenderHelper").getMethod("getVRModelView", pass);
            // VRData.getEye(VANILLA) selects the raw HMD pose. CENTER may use mirror smoothing.
            return new HeadPose(modelView, pass.getField("VANILLA").get(null));
        } catch (ReflectiveOperationException | LinkageError e) {
            CausticaMod.LOGGER.warn("Vivecraft head pose is unavailable; cannot align foveated views", e);
            return null;
        }
    }

    private static HandHooks resolveHandHooks() {
        try {
            Class<?> state = load("client_vr.render.renderstates.VRRenderState");
            Class<?> arms = Access.HOOKS.armsState.getType();
            Method submit = load("client_vr.render.helpers.VRArmHelper").getMethod("renderVRHands",
                    SubmitNodeCollector.class, state, CameraRenderState.class, PoseStack.class,
                    boolean.class, boolean.class, boolean.class, boolean.class, int.class);
            Field teleportState = state.getField("teleportState");
            Class<?> teleport = teleportState.getType();
            return new HandHooks(submit, arms.getField("renderHands"), arms.getField("menuHandMain"),
                    arms.getField("menuHandOff"), arms.getField("skipMainHandItemRendering"),
                    arms.getField("skipOffHandItemRendering"), arms.getField("mainHandRenderItem"),
                    arms.getField("offHandRenderItem"), teleportState,
                    teleport.getField("tpEnergy"), teleport.getField("arcAiming"));
        } catch (ReflectiveOperationException | LinkageError e) {
            CausticaMod.LOGGER.warn("Vivecraft hand submission is unavailable. Using native hands.", e);
            return null;
        }
    }

    private static Class<?> load(String name) throws ClassNotFoundException {
        return Class.forName("org.vivecraft." + name, false, VivecraftCompat.class.getClassLoader());
    }

    private record Hooks(Method holder, Field pass, Method isVanilla, Method levelVrState, Field statePass,
                         Field uiAfterWorld, Field armsState, Field handsSecond, Field[] screens,
                         Field lightCoords, Method applyModelView, Method renderEffects) {}

    private record OverlayOptions(Field settings, Field doubleResolution, Method setOption,
                                  Object doubleResolutionOption) {}

    private record HeadPose(Method modelView, Object headPass) {}

    private record HandHooks(Method submit, Field renderHands, Field mainMenuHand, Field offMenuHand,
                             Field skipMain, Field skipOff, Field mainItem, Field offItem, Field teleportState,
                             Field teleportEnergy, Field teleportArc) {}

    private record HiddenHands(HandHooks hooks, Object arms, boolean skipMain, boolean skipOff) {}
}
