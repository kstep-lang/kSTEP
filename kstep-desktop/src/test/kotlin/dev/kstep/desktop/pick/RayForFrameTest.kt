package dev.kstep.desktop.pick

import dev.kstep.render.mesh.Camera
import dev.kstep.render.mesh.MeshProjection
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/** The picking ray is built from the frame the canvas drew with, so a pixel picks what is drawn there. */
class RayForFrameTest :
    StringSpec({
        val coords =
            TestScenes.triangle(0.0, 0.0, 0.0) + TestScenes.triangle(20.0, 0.0, 0.0) +
                TestScenes.triangle(0.0, 30.0, 4.0)
        val (mesh, table) = TestScenes.scene(coords)
        val index = PickIndex(mesh, table)

        data class Pose(
            val camera: Camera,
            val scale: Double?,
            val panX: Double,
            val panY: Double,
        )

        val poses =
            listOf(
                Pose(Camera.ISOMETRIC, null, 0.0, 0.0),
                Pose(Camera.ISOMETRIC, 14.0, 40.0, -25.0),
                Pose(Camera.of(80.0, 25.0), 9.0, -12.0, 8.0),
                Pose(Camera.of(-120.0, 55.0), 20.0, 0.0, 0.0),
            )

        "the centre of every drawn triangle picks the part, with pan and zoom" {
            for (pose in poses) {
                val framed =
                    MeshProjection.projectFramed(
                        mesh,
                        800.0,
                        600.0,
                        pose.camera,
                        pose.scale,
                        pose.panX,
                        pose.panY,
                    )
                val frame = framed.frame.shouldNotBeNull()
                framed.triangles.isNotEmpty() shouldBe true
                for (t in framed.triangles) {
                    val px = (t.ax + t.bx + t.cx) / 3.0
                    val py = (t.ay + t.by + t.cy) / 3.0
                    index.pick(rayFor(frame, px, py, index.sceneRadius)) shouldBe 0
                }
            }
        }

        "a pixel far outside the model picks nothing" {
            for (pose in poses) {
                val framed =
                    MeshProjection.projectFramed(
                        mesh,
                        800.0,
                        600.0,
                        pose.camera,
                        pose.scale,
                        pose.panX,
                        pose.panY,
                    )
                val frame = framed.frame.shouldNotBeNull()
                index.pick(rayFor(frame, 5000.0, 5000.0, index.sceneRadius)).shouldBeNull()
                index.pick(rayFor(frame, -5000.0, 20.0, index.sceneRadius)).shouldBeNull()
            }
        }

        "the ray starts in front of the whole scene and runs along the view direction" {
            val frame = MeshProjection.projectFramed(mesh, 800.0, 600.0).frame.shouldNotBeNull()
            val ray = rayFor(frame, 400.0, 300.0, index.sceneRadius)
            ray.dir.toList() shouldBe frame.view.toList()
            // every scene vertex lies at a positive ray parameter
            for (i in 0 until coords.size / 3) {
                var t = 0.0
                for (k in 0 until 3) t += (coords[i * 3 + k] - ray.origin[k]) * ray.dir[k]
                (t > 0.0) shouldBe true
            }
        }
    })
