package dev.comfyfluffy.caustica.rt.geometry;

import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.comfyfluffy.caustica.rt.geometry.RtSceneGeometryManager.ReconcileAction.KEEP;
import static dev.comfyfluffy.caustica.rt.geometry.RtSceneGeometryManager.ReconcileAction.REPACK;
import static dev.comfyfluffy.caustica.rt.geometry.RtSceneGeometryManager.ReconcileAction.RELEASE;
import static dev.comfyfluffy.caustica.rt.geometry.RtSceneGeometryManager.ReconcileAction.REPLACE;
import static dev.comfyfluffy.caustica.rt.geometry.RtSceneGeometryManager.reconcileAction;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

final class RtSceneGeometryManagerReconcileTest {
    @Test
    void untouchedLiveMeshWithCurrentMaterialsIsKept() {
        assertEquals(KEEP, reconcileAction(false, false, true, false));
    }

    @Test
    void retainAlwaysReplacesRegardlessOfOtherSignals() {
        assertEquals(REPLACE, reconcileAction(true, false, true, false));
        assertEquals(REPLACE, reconcileAction(true, false, true, true));
        assertEquals(REPLACE, reconcileAction(true, false, false, false));
        // A provider that both releases and retains the same key in one frame is contradictory input;
        // retain wins rather than silently dropping the mesh.
        assertEquals(REPLACE, reconcileAction(true, true, true, false));
    }

    @Test
    void explicitReleaseWinsOverStaleMaterialsWhenNotRetained() {
        assertEquals(RELEASE, reconcileAction(false, true, true, false));
        assertEquals(RELEASE, reconcileAction(false, true, true, true));
    }

    @Test
    void deadProviderIsReleasedEvenWithoutAnExplicitCall() {
        assertEquals(RELEASE, reconcileAction(false, false, false, false));
        assertEquals(RELEASE, reconcileAction(false, false, false, true));
    }

    @Test
    void staleMaterialEpochRepacksOnlyWhenOtherwiseUntouched() {
        assertEquals(REPACK, reconcileAction(false, false, true, true));
    }

    @Test
    void sharedMeshHasOnePendingBuildUseRegardlessOfInstanceCount() {
        Object sharedCloudMesh = new Object();
        Object otherMesh = new Object();

        List<Object> unique = RtSceneGeometryManager.uniqueByIdentity(List.of(
                sharedCloudMesh, sharedCloudMesh, otherMesh, sharedCloudMesh));

        assertEquals(2, unique.size());
        assertSame(sharedCloudMesh, unique.getFirst());
        assertSame(otherMesh, unique.get(1));
    }
}
