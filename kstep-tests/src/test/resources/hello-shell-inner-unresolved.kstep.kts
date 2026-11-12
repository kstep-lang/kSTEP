// hello-shell-inner-unresolved.kstep.kts -- a name without an image must fail, never guess (ADR-0041).
// Top and front are opened: the edge between them borders only opened faces, so it has no inner copy. Expected: the export
// fails with KSTEP-S-011 and the message "no inner image".
import dev.kstep.geometry.feature.BoxFeature
import dev.kstep.geometry.feature.FeatureHistory
import dev.kstep.geometry.feature.GeneratedEdges
import dev.kstep.geometry.feature.SelectFaces
import dev.kstep.geometry.feature.SelectedFilletFeature
import dev.kstep.geometry.feature.ShellFeature

val history = FeatureHistory()
history.add(BoxFeature(40.0, 30.0, 20.0))
history.add(
    ShellFeature(
        0,
        SelectFaces(Faces.atMax(Direction3.Z) or Faces.atMin(Direction3.Y), expected = 2),
        2.0,
    ),
)
history.add(
    SelectedFilletFeature(
        1,
        GeneratedEdges(
            1,
            listOf(ShellInnerEdges(Edges.atMax(Direction3.Z) and Edges.atMin(Direction3.Y))),
            GeneratedRole.CAVITY,
            expected = 1,
        ),
        1.0,
    ),
)
history.rebuild().final
