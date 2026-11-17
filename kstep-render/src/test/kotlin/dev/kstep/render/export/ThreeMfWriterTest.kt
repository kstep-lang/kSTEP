package dev.kstep.render.export

import dev.kstep.geometry.MeshColor
import dev.kstep.geometry.Placement
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

private fun entries(bytes: ByteArray): LinkedHashMap<String, String> {
    val map = LinkedHashMap<String, String>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
        while (true) {
            val e = z.nextEntry ?: break
            map[e.name] = String(z.readBytes(), Charsets.UTF_8)
        }
    }
    return map
}

private fun parse(xml: String): Document {
    val f = DocumentBuilderFactory.newInstance()
    f.isNamespaceAware = true
    f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
    f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    return f.newDocumentBuilder().parse(xml.byteInputStream(Charsets.UTF_8))
}

private fun Document.els(name: String): List<Element> {
    val l = getElementsByTagNameNS("*", name)
    return (0 until l.length).map { l.item(it) as Element }
}

private fun model(bytes: ByteArray) = parse(entries(bytes).getValue("3D/3dmodel.model"))

/** Volume of the build (every item's object transformed by the 3MF row-vector rule) from the PARSED file. */
private fun buildVolume(doc: Document): Double {
    val objects =
        doc.els("object").associate { o ->
            val vs = o.getElementsByTagNameNS("*", "vertex")
            val verts =
                (0 until vs.length).map {
                    (vs.item(it) as Element).let { e ->
                        doubleArrayOf(
                            e.getAttribute("x").toDouble(),
                            e.getAttribute("y").toDouble(),
                            e.getAttribute("z").toDouble(),
                        )
                    }
                }
            val ts = o.getElementsByTagNameNS("*", "triangle")
            val tris =
                (0 until ts.length).map {
                    (ts.item(it) as Element).let { e ->
                        intArrayOf(
                            e.getAttribute("v1").toInt(),
                            e.getAttribute("v2").toInt(),
                            e.getAttribute("v3").toInt(),
                        )
                    }
                }
            o.getAttribute("id") to Pair(verts, tris)
        }
    var total = 0.0
    for (item in doc.els("item")) {
        val (verts, tris) = objects.getValue(item.getAttribute("objectid"))
        val m =
            if (item.hasAttribute("transform")) {
                item.getAttribute("transform").split(" ").map {
                    it.toDouble()
                }
            } else {
                listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0)
            }
        val w =
            verts.map { v ->
                doubleArrayOf(
                    v[0] * m[0] + v[1] * m[3] + v[2] * m[6] + m[9],
                    v[0] * m[1] + v[1] * m[4] + v[2] * m[7] + m[10],
                    v[0] * m[2] + v[1] * m[5] + v[2] * m[8] + m[11],
                )
            }
        val flat = tris.flatMap { t -> t.flatMap { w[it].toList() } }.toDoubleArray()
        total += signedVolume(flat)
    }
    return total
}

class ThreeMfWriterTest :
    StringSpec({
        "a box is a well-formed package with welded vertices" {
            val r = ThreeMfWriter.write(MeshExportScene("box", listOf(part("box", boxMesh()))))
            val e = entries(r.bytes)
            e.keys.toList() shouldBe listOf("[Content_Types].xml", "_rels/.rels", "3D/3dmodel.model")
            e.getValue("_rels/.rels").contains("/3D/3dmodel.model") shouldBe true
            e.getValue("[Content_Types].xml").contains("3dmanufacturing-3dmodel+xml") shouldBe true
            val doc = model(r.bytes)
            doc.documentElement.getAttribute("unit") shouldBe "millimeter"
            doc.documentElement.namespaceURI shouldBe "http://schemas.microsoft.com/3dmanufacturing/core/2015/02"
            doc.els("vertex").size shouldBe 8
            doc.els("triangle").size shouldBe 12
            doc.els("object").size shouldBe 1
            doc.els("item").size shouldBe 1
            doc.els("item")[0].hasAttribute("transform") shouldBe false
            r.watertight shouldBe true
            r.format shouldBe "3mf"
            buildVolume(doc) shouldBe (6000.0 plusOrMinus 1e-6)
        }

        "colours become basematerials, alpha is carried as #RRGGBBAA" {
            val scene =
                MeshExportScene(
                    "c",
                    listOf(
                        part("red", boxMesh(), color = MeshColor(1.0, 0.0, 0.0)),
                        part(
                            "glass",
                            boxMesh(),
                            Placement.translation(50.0, 0.0, 0.0),
                            color = MeshColor(0.0, 0.0, 1.0, 0.5),
                        ),
                        part("plain", boxMesh(), Placement.translation(100.0, 0.0, 0.0)),
                    ),
                )
            val doc = model(ThreeMfWriter.write(scene).bytes)
            doc.els("base").map { it.getAttribute("displaycolor") } shouldBe listOf("#FF0000", "#0000FF80")
            val objs = doc.els("object")
            objs[0].getAttribute("pindex") shouldBe "0"
            objs[1].getAttribute("pindex") shouldBe "1"
            objs[2].hasAttribute("pid") shouldBe false
        }

        "two instances of one part are one object and two build items" {
            val scene =
                MeshExportScene(
                    "a",
                    listOf(part("p", boxMesh(), Placement.IDENTITY, Placement.translation(0.0, 50.0, 0.0))),
                )
            val r = ThreeMfWriter.write(scene)
            val doc = model(r.bytes)
            doc.els("object").size shouldBe 1
            doc.els("item").size shouldBe 2
            r.buildItemCount shouldBe 2
            buildVolume(doc) shouldBe (12000.0 plusOrMinus 1e-6)
        }

        "a rotated placement keeps the 3MF row-vector transform consistent with the STL world positions" {
            val placement =
                Placement
                    .rotationZ(
                        90.0,
                    ).then(Placement.rotationX(30.0))
                    .then(Placement.translation(7.0, -3.0, 11.0))
            val scene = MeshExportScene("r", listOf(part("p", boxMesh(), placement)))
            val doc = model(ThreeMfWriter.write(scene).bytes)
            val m =
                doc
                    .els("item")[0]
                    .getAttribute("transform")
                    .split(" ")
                    .map { it.toDouble() }
            val v = doubleArrayOf(3.0, 5.0, 7.0)
            val x = v[0] * m[0] + v[1] * m[3] + v[2] * m[6] + m[9]
            val y = v[0] * m[1] + v[1] * m[4] + v[2] * m[7] + m[10]
            val z = v[0] * m[2] + v[1] * m[5] + v[2] * m[8] + m[11]
            val r = placement.rowMajor3x4()
            x shouldBe (r[0] * 3 + r[1] * 5 + r[2] * 7 + r[3] plusOrMinus 1e-8)
            y shouldBe (r[4] * 3 + r[5] * 5 + r[6] * 7 + r[7] plusOrMinus 1e-8)
            z shouldBe (r[8] * 3 + r[9] * 5 + r[10] * 7 + r[11] plusOrMinus 1e-8)
            // and the world-space STL of the same scene has the same bounding box
            val stl = readStl(StlWriter.write(scene).bytes)
            var maxZ = -1e9
            for (i in 0 until stl.count * 3) maxZ = maxOf(maxZ, stl.coords[3 * i + 2])
            val mz =
                (0 until 8).maxOf { i ->
                    val px =
                        if (i and 1 !=
                            0
                        ) {
                            10.0
                        } else {
                            0.0
                        }
                    ; val py =
                        if (i and 2 !=
                            0
                        ) {
                            20.0
                        } else {
                            0.0
                        }
                    ; val pz =
                        if (i and 4 !=
                            0
                        ) {
                            30.0
                        } else {
                            0.0
                        }
                    ; r[8] * px + r[9] * py + r[10] * pz + r[11]
                }
            maxZ shouldBe (mz plusOrMinus 1e-4)
            buildVolume(doc) shouldBe (6000.0 plusOrMinus 1e-5)
        }

        "two identical writes produce identical bytes" {
            val scene =
                MeshExportScene(
                    "box",
                    listOf(part("box", boxMesh(), Placement.rotationZ(30.0), color = MeshColor(0.2, 0.4, 0.6))),
                )
            ThreeMfWriter.write(scene).bytes.contentEquals(ThreeMfWriter.write(scene).bytes) shouldBe true
        }
    })
