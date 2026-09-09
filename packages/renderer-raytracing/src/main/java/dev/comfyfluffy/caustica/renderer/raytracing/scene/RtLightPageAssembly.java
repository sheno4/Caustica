package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneSnapshot;
import dev.comfyfluffy.caustica.engine.scene.SnapshotList;

import dev.comfyfluffy.caustica.renderer.raytracing.scene.RtRetainedSceneBackend.SceneLight;
import dev.comfyfluffy.caustica.renderer.raytracing.scene.RtRetainedSceneBackend.SceneContent;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Translates edited light pages while sharing unchanged immutable scene light values. */
final class RtLightPageAssembly {
    private Map<List<RetainedSceneSnapshot.Light>, Map<SceneId, List<SceneLight>>> pages = new IdentityHashMap<>();
    private Map<SceneId, SceneContent> content = Map.of();

    void clear() {
        pages.clear();
        content = Map.of();
    }

    Map<SceneId, SceneContent> resolve(
            List<RetainedSceneSnapshot.Scene> scenes, List<RetainedSceneSnapshot.Light> lights) {
        Map<SceneId, List<List<SceneLight>>> scenePages = new IdentityHashMap<>();
        for (var scene : scenes) scenePages.put(scene.id(), new ArrayList<>());
        Map<List<RetainedSceneSnapshot.Light>, Map<SceneId, List<SceneLight>>> next = new IdentityHashMap<>();
        for (var page : SnapshotList.pagesOf(lights)) {
            var translated = pages.get(page);
            if (translated == null) translated = translate(page);
            next.put(page, translated);
            translated.forEach((scene, value) -> {
                var target = scenePages.get(scene);
                if (target == null) throw new IllegalArgumentException("light names an absent scene");
                target.add(value);
            });
        }
        Map<SceneId, SceneContent> result = new IdentityHashMap<>();
        for (var scene : scenes) {
            var previous = content.get(scene.id());
            var values = scenePages.get(scene.id());
            List<SceneLight> assembled = previous != null
                    && samePages(SnapshotList.pagesOf(previous.lights()), values)
                    ? previous.lights() : SnapshotList.ofPages(values);
            result.put(scene.id(), previous != null && previous.environment() == scene.environment()
                    && previous.lights() == assembled ? previous
                    : new SceneContent(scene.environment(), assembled));
        }
        pages = next;
        content = result;
        return result;
    }

    private static Map<SceneId, List<SceneLight>> translate(List<RetainedSceneSnapshot.Light> page) {
        Map<SceneId, List<SceneLight>> values = new IdentityHashMap<>();
        for (var light : page) {
            values.computeIfAbsent(light.scene(), ignored -> new ArrayList<>())
                    .add(new SceneLight(light.identity(), light.descriptor()));
        }
        values.replaceAll((scene, lights) -> List.copyOf(lights));
        return values;
    }

    private static boolean samePages(List<?> previous, List<?> current) {
        if (previous.size() != current.size()) return false;
        for (int index = 0; index < current.size(); index++) {
            if (previous.get(index) != current.get(index)) return false;
        }
        return true;
    }
}
