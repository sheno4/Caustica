package dev.comfyfluffy.caustica.minecraft.client.terrain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class TerrainWindowTest {
    @Test
    void windowAvailabilityInvalidatesCachedHaloOutsideRetainedColumnsBeforeEligibility() {
        var window = new TerrainWindow();
        window.apply(observation(0, 0, 1, 4, 5, 0, 2), key -> { }, key -> { }, (key, loaded) -> { }, keys -> { });
        var cache = new RtSectionSnapshots.Cache(8);
        var column = new Object();
        var palette = new Object();
        var invalidated = new ArrayList<Long>();
        var changes = new ArrayList<Boolean>();
        for (boolean loaded : new boolean[]{false, true}) {
            for (int y = 3; y <= 6; y++) cache.put(key(2, y), column, palette);
            invalidated.clear();
            var next = loaded ? observation(0, 0, 1, 4, 5, 0, 2) : observation(0, 0, 1, 4, 5, 0);
            window.apply(next, key -> fail("Halo changes must not add retained sections"),
                    key -> fail("Halo changes must not remove retained sections"), (key, present) -> {
                        assertEquals(RtTerrain.columnKey(2, 0), key.longValue());
                        for (int y = 3; y <= 6; y++) assertNull(cache.get(key(2, y), column));
                        changes.add(present);
                    }, keys -> {
                        invalidated.addAll(keys);
                        keys.forEach(cache::invalidate);
                    });
            assertEquals(List.of(key(2, 3), key(2, 4), key(2, 5), key(2, 6)).stream().sorted().toList(),
                    invalidated.stream().sorted().toList());
            assertEquals(1, window.columns());
        }
        assertEquals(List.of(false, true), changes);
    }

    @Test
    void liveAvailabilityFeedbackInvalidatesCachedHaloBeforeEligibility() {
        var window = new TerrainWindow();
        window.apply(observation(0, 0, 1, 4, 5, 0, 2), key -> { }, key -> { }, (key, loaded) -> { }, keys -> { });
        var cache = new RtSectionSnapshots.Cache(8);
        var column = new Object();
        var invalidated = new ArrayList<Long>();
        var changes = new ArrayList<Boolean>();
        for (boolean loaded : new boolean[]{false, true}) {
            for (int y = 3; y <= 6; y++) cache.put(key(2, y), column, new Object());
            invalidated.clear();
            window.observe(RtTerrain.columnKey(2, 0), loaded, (key, present) -> {
                for (int y = 3; y <= 6; y++) assertNull(cache.get(key(2, y), column));
                changes.add(present);
            }, keys -> {
                invalidated.addAll(keys);
                keys.forEach(cache::invalidate);
            });
            assertEquals(List.of(key(2, 3), key(2, 4), key(2, 5), key(2, 6)).stream().sorted().toList(),
                    invalidated.stream().sorted().toList());
            assertEquals(1, window.columns());
        }
        window.observe(RtTerrain.columnKey(2, 0), true,
                (key, present) -> fail("Unchanged availability must not notify"),
                keys -> fail("Unchanged availability must not invalidate"));
        assertEquals(List.of(false, true), changes);
    }

    @Test
    void removalInvalidationsPrecedeReplacementRequests() {
        var window = new TerrainWindow();
        window.apply(observation(0, 0, 1, 0, 0, 0), key -> { }, key -> { }, (key, loaded) -> { }, keys -> { });
        var events = new ArrayList<String>();
        window.apply(observation(0, 0, 1, 1, 1, 0), key -> events.add("want"),
                key -> events.add("remove"), (key, loaded) -> { }, keys -> events.add("invalidate"));
        assertEquals(List.of("invalidate", "remove", "want"), events);
    }

    @Test
    void departureAndReturnInvalidateThePriorRequestAndSnapshotEntry() {
        var window = new TerrainWindow();
        var updates = new TerrainUpdates<String>();
        var invalidated = new ArrayList<Long>();
        java.util.function.LongConsumer remove = key -> { invalidated.add(key); updates.remove(key); };
        window.apply(observation(0, 0, 1, 0, 0, 0), updates::want, remove, (key, loaded) -> { }, keys -> { });
        var old = updates.sections.get(key(0, 0)).request;
        window.apply(observation(0, 0, 1, 0, 0), updates::want, remove, (key, loaded) -> { }, keys -> { });
        window.apply(observation(0, 0, 1, 0, 0, 0), updates::want, remove, (key, loaded) -> { }, keys -> { });
        assertEquals(List.of(key(0, 0)), invalidated);
        assertFalse(old.valid());
        assertNotSame(old, updates.sections.get(key(0, 0)).request);
        assertTrue(updates.awaitingExtraction(updates.sections.get(key(0, 0)).request));
    }

    @Test
    void retainsUnchangedColumnsAndReplacesOnlyEnteredAndDepartedSections() {
        var window = new TerrainWindow();
        var wanted = new ArrayList<Long>();
        var removed = new ArrayList<Long>();
        var available = new LinkedHashMap<Long, Boolean>();
        window.apply(observation(0, 0, 1, 0, 1, 0, 1), wanted::add, removed::add, available::put, keys -> { });
        assertEquals(List.of(key(0, 0), key(0, 1), key(1, 0), key(1, 1)).stream().sorted().toList(),
                wanted.stream().sorted().toList());
        wanted.clear();
        removed.clear();
        available.clear();
        window.apply(observation(0, 0, 1, 0, 1, 0, 1), wanted::add, removed::add, available::put, keys -> { });
        assertTrue(wanted.isEmpty());
        assertTrue(removed.isEmpty());
        assertTrue(available.isEmpty());
        window.apply(observation(1, 0, 1, 0, 1, 1, 2), wanted::add, removed::add, available::put, keys -> { });
        assertEquals(List.of(key(2, 0), key(2, 1)), wanted);
        assertEquals(List.of(key(0, 0), key(0, 1)), removed);
        assertEquals(Boolean.FALSE, available.get(RtTerrain.columnKey(0, 0)));
        assertEquals(Boolean.TRUE, available.get(RtTerrain.columnKey(2, 0)));
    }

    @Test
    void heightChangesReplaceSectionRangeAndHaloOnlySuppliesEligibility() {
        var window = new TerrainWindow();
        var wanted = new ArrayList<Long>();
        var removed = new ArrayList<Long>();
        window.apply(observation(0, 0, 1, 0, 0, 0, 2), wanted::add, removed::add, (key, loaded) -> { }, keys -> { });
        assertEquals(List.of(key(0, 0)), wanted);
        assertEquals(1, window.columns());
        wanted.clear();
        window.apply(observation(0, 0, 1, -1, 1, 0, 2), wanted::add, removed::add, (key, loaded) -> { }, keys -> { });
        assertEquals(List.of(key(0, 0)), removed);
        assertEquals(List.of(key(0, -1), key(0, 0), key(0, 1)), wanted);
    }

    @Test
    void liveEligibilityFeedbackAndResetHaveIndependentRetainedState() {
        var window = new TerrainWindow();
        var changes = new LinkedHashMap<Long, Boolean>();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) window.observe(RtTerrain.columnKey(x, z), true, changes::put, keys -> { });
        }
        assertTrue(window.neighborsLoaded(0, 0));
        changes.clear();
        window.observe(RtTerrain.columnKey(0, 0), true, changes::put, keys -> { });
        assertTrue(changes.isEmpty());
        window.observe(RtTerrain.columnKey(1, 1), false, changes::put, keys -> { });
        assertFalse(window.neighborsLoaded(0, 0));
        window.clear();
        assertEquals(0, window.columns());
        window.observe(RtTerrain.columnKey(1, 1), true, changes::put, keys -> { });
        assertFalse(window.neighborsLoaded(0, 0));
    }

    private static long key(int x, int y) { return RtTerrain.sectionKey(x, y, 0); }

    private static TerrainWindow.Observation observation(int x, int z, int radius, int minY, int maxY, int... columns) {
        long[] keys = new long[columns.length];
        for (int i = 0; i < columns.length; i++) keys[i] = RtTerrain.columnKey(columns[i], 0);
        return new TerrainWindow.Observation(0, x, z, radius, minY, maxY, keys);
    }
}
