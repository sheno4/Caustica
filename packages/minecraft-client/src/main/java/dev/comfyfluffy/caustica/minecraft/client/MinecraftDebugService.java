package dev.comfyfluffy.caustica.minecraft.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.comfyfluffy.caustica.config.CausticaConfig;
import dev.comfyfluffy.caustica.renderer.runtime.RendererOptions;
import dev.comfyfluffy.caustica.settings.Option;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Opt-in loopback control. Game state is read and changed only on the client or server thread. */
public final class MinecraftDebugService implements AutoCloseable {
    private static final Gson JSON = new Gson();
    private static MinecraftDebugService instance;
    private static final List<String> VIEWS = List.of("off", "normals", "albedo", "depth", "roughness",
            "motion", "specular", "specular-motion", "exposure", "metering-weight", "stable-validity",
            "stable-type", "primary-depth", "primary-motion", "trace-radiance", "stable-metadata");
    private final Minecraft client;
    private final HttpServer server;
    private final java.util.concurrent.ExecutorService httpExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final String token = UUID.randomUUID().toString();
    private final Path directory;
    private final Map<String, CompletableFuture<Object>> jobs = new ConcurrentHashMap<>();
    private final List<FrameWait> waits = new ArrayList<>();
    private final List<Runnable> captures = new ArrayList<>();
    private long frames;
    private long ticks;
    private Recording recording;

    private record FrameWait(long frames, long ticks, CompletableFuture<Object> future) { }

    public static void start(Minecraft client) {
        if (!Boolean.getBoolean("caustica.debug.enabled")) return;
        try {
            instance = new MinecraftDebugService(client);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot start Caustica debug service", e);
        }
    }

    private MinecraftDebugService(Minecraft client) throws IOException {
        this.client = client;
        directory = client.gameDirectory.toPath().resolve("caustica-debug").toAbsolutePath();
        Files.createDirectories(directory);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api", this::handle);
        server.setExecutor(httpExecutor);
        server.start();
        int port = server.getAddress().getPort();
        Files.writeString(directory.resolve("session.json"), JSON.toJson(Map.of("protocolVersion", 1,
                "pid", ProcessHandle.current().pid(), "port", port, "token", token,
                "baseUrl", "http://127.0.0.1:" + port)));
        CausticaMod.LOGGER.info("Caustica debug service listening on 127.0.0.1:{}", port);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!exchange.getRequestURI().getPath().equals("/api") || !exchange.getRequestMethod().equals("POST")) {
                reply(exchange, 405, Map.of("ok", false, "error", "Use POST /api"));
                return;
            }
            if (! ("Bearer " + token).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                reply(exchange, 401, Map.of("ok", false, "error", "Invalid token"));
                return;
            }
            try {
                JsonObject request = JsonParser.parseString(new String(exchange.getRequestBody().readNBytes(65537),
                        StandardCharsets.UTF_8)).getAsJsonObject();
                String op = request.get("op").getAsString();
                if (op.equals("job")) {
                    String id = request.get("jobId").getAsString();
                    var future = jobs.get(id);
                    if (future == null) throw new IllegalArgumentException("Unknown job " + id);
                    Map<String, Object> result = new LinkedHashMap<>();
                    if (!future.isDone()) result.put("state", "pending");
                    else {
                        try {
                            result.put("result", future.join());
                            result.put("state", "completed");
                        } catch (Exception e) {
                            result.put("state", "failed");
                            result.put("error", e.getCause() == null ? e.toString() : e.getCause().toString());
                        }
                    }
                    reply(exchange, 200, Map.of("ok", true, "result", result));
                    return;
                }
                long timeout = request.has("timeoutMs") ? request.get("timeoutMs").getAsLong() : 30000;
                if (timeout < 1 || timeout > 600000) throw new IllegalArgumentException("timeoutMs must be 1..600000");
                var future = new CompletableFuture<Object>().orTimeout(timeout, TimeUnit.MILLISECONDS);
                String id = UUID.randomUUID().toString();
                jobs.put(id, future);
                future.whenComplete((result, error) -> CompletableFuture.delayedExecutor(10, TimeUnit.MINUTES)
                        .execute(() -> jobs.remove(id, future)));
                client.execute(() -> {
                    if (future.isDone()) return;
                    try { execute(op, request, future); }
                    catch (Exception e) { future.completeExceptionally(e); }
                });
                reply(exchange, 202, Map.of("ok", true, "jobId", id));
            } catch (Exception e) {
                reply(exchange, 400, Map.of("ok", false, "error", e.toString()));
            }
        }
    }

    private static void reply(HttpExchange exchange, int status, Object response) throws IOException {
        byte[] bytes = JSON.toJson(response).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private void execute(String op, JsonObject request, CompletableFuture<Object> future) throws Exception {
        switch (op) {
            case "schema" -> future.complete(Map.of("views", VIEWS,
                    "images", dev.comfyfluffy.caustica.renderer.runtime.RtFrameRenderer.debugImageNames(),
                    "operations", List.of("schema", "status",
                    "settings.get", "settings.set", "view.set", "input.set", "wait", "command", "screenshot", "image.capture", "jfr.start", "jfr.dump", "jfr.stop", "client.stop", "job")));
            case "status" -> future.complete(status());
            case "settings.get" -> future.complete(settings());
            case "settings.set" -> {
                var values = request.getAsJsonObject("values");
                var options = RendererOptions.settings();
                Map<Option<?>, Object> normalized = new LinkedHashMap<>();
                for (var entry : values.entrySet()) {
                    var option = options.stream().filter(o -> o.id().equals(entry.getKey())).findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("Unknown setting " + entry.getKey()));
                    if (CausticaConfig.store().overridden(CausticaConfig.FEATURE, option))
                        throw new IllegalArgumentException("Setting has a JVM override: " + option.id());
                    normalized.put(option, option.normalize(JSON.fromJson(entry.getValue(), Object.class)));
                }
                normalized.forEach((option, value) -> CausticaConfig.store().apply(CausticaConfig.FEATURE, option, value));
                future.complete(settings());
            }
            case "view.set" -> {
                int view = VIEWS.indexOf(request.get("name").getAsString());
                if (view < 0) throw new IllegalArgumentException("Unknown view; see schema");
                var option = RendererOptions.Rt.Composite.DEBUG_VIEW;
                if (CausticaConfig.store().overridden(CausticaConfig.FEATURE, option))
                    throw new IllegalArgumentException("Debug view has a JVM override");
                CausticaConfig.store().apply(CausticaConfig.FEATURE, option, view);
                future.complete(Map.of("name", VIEWS.get(view), "value", view));
            }
            case "wait" -> {
                long frameCount = request.has("frames") ? request.get("frames").getAsLong() : 0;
                long tickCount = request.has("ticks") ? request.get("ticks").getAsLong() : 0;
                if (frameCount < 0 || tickCount < 0 || frameCount + tickCount < 1)
                    throw new IllegalArgumentException("Specify positive frames or ticks");
                requireWorld();
                waits.add(new FrameWait(Math.addExact(frames, frameCount), Math.addExact(ticks, tickCount), future));
            }
            case "input.set" -> {
                requireWorld();
                client.setScreenAndShow(null);
                client.options.keyUp.setDown(request.has("forward") && request.get("forward").getAsBoolean());
                client.options.keySprint.setDown(request.has("sprint") && request.get("sprint").getAsBoolean());
                future.complete(Map.of("forward", client.options.keyUp.isDown(),
                        "sprint", client.options.keySprint.isDown()));
            }
            case "command" -> command(request, future);
            case "screenshot", "image.capture" -> {
                requireWorld();
                captures.add(() -> {
                    if (future.isDone()) return;
                    try {
                        if (op.equals("screenshot")) screenshot(future);
                        else {
                            if (request.has("names")) {
                                List<Object> images = new ArrayList<>();
                                for (var name : request.getAsJsonArray("names")) images.add(captureImage(name.getAsString()));
                                future.complete(Map.of("images", images));
                            } else future.complete(captureImage(request.get("name").getAsString()));
                        }
                    } catch (Exception e) { future.completeExceptionally(e); }
                });
            }
            case "jfr.start" -> {
                if (recording != null) throw new IllegalStateException("Debug recording already running");
                recording = new Recording(Configuration.getConfiguration("profile"));
                recording.setName("Caustica debug");
                List<String> events = new ArrayList<>();
                if (request.has("events")) {
                    for (var name : request.getAsJsonArray("events")) events.add(name.getAsString());
                } else {
                    events.addAll(List.of("Frame", "CpuStage", "FramePreparation", "FrameCounter", "GeometryVisibility",
                            "GeometryBuildReadyLatency", "BlasCommandRecord", "EntityMeshFrame", "EntityMeshPublication", "Exposure",
                            "GpuStage", "NeeFrame", "TerrainState", "TerrainJob"));
                }
                for (String name : events)
                    recording.enable("dev.comfyfluffy.caustica." + name).withThreshold(java.time.Duration.ZERO);
                recording.start();
                future.complete(Map.of("recordingId", recording.getId()));
            }
            case "jfr.dump", "jfr.stop" -> {
                if (recording == null) throw new IllegalStateException("No debug recording running");
                Path output = directory.resolve("recording-" + UUID.randomUUID() + ".jfr");
                if (op.equals("jfr.stop")) recording.stop();
                recording.dump(output);
                if (op.equals("jfr.stop")) { recording.close(); recording = null; }
                future.complete(Map.of("path", output.toString()));
            }
            case "client.stop" -> {
                future.complete(Map.of("stopping", true));
                CompletableFuture.delayedExecutor(1, TimeUnit.SECONDS).execute(() -> client.execute(client::stop));
            }
            default -> throw new IllegalArgumentException("Unknown operation " + op);
        }
    }

    private void requireWorld() {
        if (client.level == null || client.player == null) throw new IllegalStateException("No world/player loaded");
    }

    private void command(JsonObject request, CompletableFuture<Object> future) {
        requireWorld();
        String command = request.get("command").getAsString();
        if (command.startsWith("/")) command = command.substring(1);
        String text = command;
        String target = request.has("target") ? request.get("target").getAsString() : "server";
        if (target.equals("client")) {
            client.player.connection.sendCommand(text);
            future.complete(Map.of("sent", true));
        } else if (target.equals("server")) {
            var server = client.getSingleplayerServer();
            if (server == null) throw new IllegalStateException("No integrated server; use target client to send normally");
            UUID playerId = client.player.getUUID();
            server.execute(() -> {
                if (future.isDone()) return;
                try {
                    var player = server.getPlayerList().getPlayer(playerId);
                    if (player == null) throw new IllegalStateException("Player disconnected");
                    var source = player.createCommandSourceStack();
                    int result = server.getCommands().getDispatcher().execute(text, source);
                    future.complete(Map.of("result", result));
                } catch (Exception e) { future.completeExceptionally(e); }
            });
        } else throw new IllegalArgumentException("target must be client or server");
    }

    private Map<String, Object> settings() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Option<?> option : RendererOptions.settings()) result.put(option.id(), Map.of(
                "value", CausticaConfig.get(option), "overridden", CausticaConfig.store().overridden(CausticaConfig.FEATURE, option)));
        return result;
    }

    private Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ready", client.level != null && client.player != null);
        result.put("paused", client.isPaused());
        result.put("window", Map.of("width", client.getWindow().getWidth(),
                "height", client.getWindow().getHeight()));
        result.put("frames", frames);
        result.put("ticks", ticks);
        result.put("frameActive", CausticaClientComposition.current().runtime().frameActive());
        if (client.level != null) result.put("world", Map.of("dimension", client.level.dimension().toString(),
                "gameTime", client.level.getGameTime()));
        if (client.player != null) result.put("player", Map.of("x", client.player.getX(), "y", client.player.getY(),
                "z", client.player.getZ(), "yaw", client.player.getYRot(), "pitch", client.player.getXRot()));
        if (client.level != null) {
            var camera = client.gameRenderer.mainCamera();
            var position = camera.position();
            result.put("camera", Map.of("x", position.x, "y", position.y, "z", position.z, "yaw", camera.yaw()));
        }
        result.put("settings", settings());
        result.put("latestFrame", CausticaClientComposition.current().runtime().telemetry().latestFrame());
        return result;
    }

    private Object captureImage(String name) throws IOException {
        Path output = directory.resolve("image-" + UUID.randomUUID() + ".exr");
        var metadata = CausticaClientComposition.current().runtime().exportLatestDebugImage(name, output);
        if (metadata == null) throw new IllegalStateException("No completed renderer image available");
        return Map.of("path", output.toString(), "metadata", metadata);
    }

    private void screenshot(CompletableFuture<Object> future) throws IOException {
        requireWorld();
        String name = "caustica-debug-" + UUID.randomUUID() + ".png";
        Path output = client.gameDirectory.toPath().resolve("screenshots").resolve(name).toAbsolutePath();
        long frameId = CausticaClientComposition.current().runtime().telemetry().frameSerial();
        Screenshot.grab(client.gameDirectory, name, client.gameRenderer.mainRenderTarget(), 1, message -> {
            if (Files.isRegularFile(output)) future.complete(Map.of("path", output.toString(), "frameId", frameId));
            else future.completeExceptionally(new IOException(message.getString()));
        });
    }

    public static void frameRendered(boolean active) {
        if (instance == null) return;
        if (active && instance.client.level != null) instance.frames++;
        var pending = List.copyOf(instance.captures);
        instance.captures.clear();
        pending.forEach(Runnable::run);
        instance.advanceWaits();
    }

    public static void tick() {
        if (instance == null) return;
        if (instance.client.level != null && !instance.client.isPaused()) instance.ticks++;
        instance.advanceWaits();
    }

    private void advanceWaits() {
        waits.removeIf(wait -> {
            if (wait.future().isDone()) return true;
            if (client.level == null) wait.future().completeExceptionally(new IllegalStateException("World unloaded"));
            else if (frames >= wait.frames() && ticks >= wait.ticks())
                wait.future().complete(Map.of("frames", frames, "ticks", ticks));
            return wait.future().isDone();
        });
    }

    public static void stop() {
        if (instance != null) { instance.close(); instance = null; }
    }

    @Override public void close() {
        server.stop(0);
        httpExecutor.close();
        jobs.values().forEach(job -> job.completeExceptionally(new IllegalStateException("Client shutting down")));
        if (recording != null) { recording.close(); recording = null; }
        try { Files.deleteIfExists(directory.resolve("session.json")); }
        catch (IOException e) { CausticaMod.LOGGER.warn("Cannot remove debug discovery file", e); }
    }
}
