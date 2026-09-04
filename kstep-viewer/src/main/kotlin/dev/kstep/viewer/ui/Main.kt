package dev.kstep.viewer.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.kstep.geometry.ColoredMesh
import dev.kstep.geometry.MeshColor
import dev.kstep.geometry.MeshComposition
import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.PlacedMesh
import dev.kstep.geometry.Placement

/**
 * Opens a real, on-screen window showing a static isometric demo assembly. Opt-in only -- run
 * explicitly via `./gradlew :kstep-viewer:run`, never as part of `check` (see
 * `kstep-viewer/build.gradle.kts` and docs/adr/ADR-0010-occt-triangulation-and-viewer.adoc).
 *
 * The demo scene is THREE parts merged into one world mesh (kSTEP's
 * multi-shape-composition-and-fill-light wave, see
 * `docs/adr/ADR-0013-multi-shape-composition-and-fill-light.adoc`) -- a base plate, a pillar
 * standing on it, and a filleted block next to the pillar -- rather than the single static box
 * every prior viewer wave showed. Since kSTEP's viewer-pan-and-material-colors wave (see
 * docs/adr/ADR-0017-viewer-pan-and-part-colors.adoc), each part also carries its own [MeshColor]
 * and the three are merged via [MeshComposition.mergeColored] into a [ColoredMesh] instead of a
 * plain `TriangleMesh` -- [ShapeCanvas]'s `ColoredMesh` overload draws the result, one call still
 * built here before the first composition, not per frame.
 */
private fun buildDemoMesh(): ColoredMesh {
    // Each part is triangulated and its OcctShape closed before the next part is built -- see
    // this wave's ADR's Security section for why sequential `use { }` blocks (rather than holding
    // all three shapes open at once) are the leak-safe shape here: a `use { }` block closes its
    // shape on ANY exit, including an exception thrown while building or triangulating a LATER
    // part, so no part can leak a still-open native handle regardless of where construction fails.
    val plateMesh = OcctKernel.makeBox(60.0, 40.0, 6.0).use { it.triangulate() }
    val pillarMesh = OcctKernel.makeBox(10.0, 10.0, 30.0).use { it.triangulate() }
    // fillet()'s input shape is neither modified nor consumed -- it must still be closed by its
    // own caller (see OcctKernel.fillet's KDoc) -- hence the nested `use { }`: the OUTER block
    // closes the plain box, the INNER block closes the fillet RESULT, once its own triangulation
    // is done. edgeIndex = 0 rounds a single edge (see OcctKernel.fillet's KDoc on why edge
    // indices are opaque/construction-specific -- rounding ALL of a box's bottom edges would need
    // edge indices individually measured per this wave's own ADR, deliberately out of scope for
    // this one demo shape).
    val roundedMesh =
        OcctKernel.makeBox(20.0, 20.0, 12.0).use { box ->
            OcctKernel.fillet(box, edgeIndex = 0, radius = 3.0).use { it.triangulate() }
        }

    // Colors chosen for a mid-range, roughly evenly-spaced luminance spread (relative
    // luminance L = 0.2126*r + 0.7152*g + 0.0722*b) so all three parts stay visually distinct
    // in the same isometric key/fill lighting -- plate ~=0.474, pillar ~=0.808, rounded block
    // ~=0.656 -- and every channel sits inside MeshColor's own recommended 0.40..0.95 band (see
    // that class's KDoc) so no part's shadow side goes unreadably dark.
    return MeshComposition.mergeColored(
        listOf(
            PlacedMesh(plateMesh, color = MeshColor(0.42, 0.48, 0.58)),
            PlacedMesh(pillarMesh, Placement.translation(10.0, 15.0, 6.0), MeshColor(0.92, 0.80, 0.55)),
            PlacedMesh(roundedMesh, Placement.translation(32.0, 10.0, 6.0), MeshColor(0.50, 0.70, 0.68)),
        ),
    )
}

fun main() =
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "kSTEP Viewer",
            state = rememberWindowState(width = 900.dp, height = 700.dp),
        ) {
            when (val availability = OcctKernel.availability()) {
                is OcctAvailability.Available -> {
                    // runCatching, not a bare call: OCCT being reported Available does not
                    // guarantee triangulate()/fillet() succeed for every part (OcctGeometryException
                    // from a Standard_Failure, or IllegalArgumentException from the MAX_TRIANGLES
                    // guard). Left unguarded, that exception unwinds out of composition and kills
                    // the whole window with a stack trace -- exactly the unexplained-failure state
                    // UnavailableNotice exists to prevent, just reached from the Available branch
                    // instead of the Unavailable one. A failure in ANY one of the three parts fails
                    // the whole scene visibly (no silent partial render) -- see this wave's ADR.
                    val meshResult = remember { runCatching { buildDemoMesh() } }
                    meshResult.fold(
                        onSuccess = { mesh -> ShapeCanvas(mesh, Modifier.fillMaxSize()) },
                        onFailure = { error ->
                            UnavailableNotice(
                                reason = error.message ?: error.toString(),
                                modifier = Modifier.fillMaxSize(),
                                heading = "kSTEP Viewer could not triangulate the demo scene.",
                                showInstallInstructions = false,
                            )
                        },
                    )
                }
                is OcctAvailability.Unavailable -> UnavailableNotice(availability.reason, Modifier.fillMaxSize())
            }
        }
    }
