package dev.kstep.tests

import dev.kstep.core.ap242.applicationContext
import dev.kstep.core.ap242.product
import dev.kstep.core.ap242.productContext
import dev.kstep.core.ap242.productDefinitionFormation
import dev.kstep.core.getOrThrow
import dev.kstep.step21.Part21Value
import dev.kstep.step21.Part21Writer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * [Part21Writer.emit]'s [startId] offset, [dev.kstep.step21.Part21EmitResult.nextId], and
 * [dev.kstep.step21.Part21EmitResult.idOf] — the exact numbering primitives
 * `dev.kstep.shape.Ap242ShapeExporter.export` composes its merge from (offsetting the geometry
 * subgraph by `structure.nextId - 1`, looking up bridge-entity targets via `idOf`), but which no
 * OCCT-gated test can exercise on a machine without the native OCCT bridge — see ADR-0009's
 * Folge-Welle notes and `Ap242ShapeExportRoundtripTest`, whose only test case is
 * `OcctKernel.availability()`-gated. Deliberately OCCT-independent: everything here is plain
 * `kstep-core` object-graph construction.
 */
class Part21WriterEmitTest :
    StringSpec({
        "emit with a non-default startId offsets every id by startId - 1, in post-order-DFS document order" {
            val appCtx = applicationContext { application = "config control" }.getOrThrow()
            val prodCtx =
                productContext {
                    name = "engineering"
                    frameOfReference = appCtx
                    disciplineType = "mechanical"
                }.getOrThrow()
            val prod =
                product("BRK-001") {
                    name = "Bracket"
                    frameOfReference = setOf(prodCtx)
                }.getOrThrow()
            val formation = productDefinitionFormation("BRK-001-F") { ofProduct = prod }.getOrThrow()

            val result = Part21Writer.emit(listOf(formation), startId = 7)

            // Post-order DFS from the single root `formation`: dependencies (appCtx, prodCtx,
            // prod) are discovered, and therefore numbered, before the root itself.
            result.instances.map { it.id } shouldBe listOf(7, 8, 9, 10)
            result.instances.map { it.entityName } shouldBe
                listOf("APPLICATION_CONTEXT", "PRODUCT_CONTEXT", "PRODUCT", "PRODUCT_DEFINITION_FORMATION")

            // nextId is the first id NOT used by this emission — exactly what
            // Ap242ShapeExporter.export subtracts 1 from to compute its geometry-subgraph offset.
            result.nextId shouldBe 11

            result.idOf(appCtx) shouldBe 7
            result.idOf(prodCtx) shouldBe 8
            result.idOf(prod) shouldBe 9
            result.idOf(formation) shouldBe 10

            // The PRODUCT_CONTEXT -> APPLICATION_CONTEXT reference is itself shifted by the
            // startId offset, not left pointing at the pre-offset id 1.
            val productContextInstance = result.instances.single { it.entityName == "PRODUCT_CONTEXT" }
            productContextInstance.args[1] shouldBe Part21Value.Ref(7)
        }

        "emit with the default startId = 1 numbers the first instance as #1" {
            val appCtx = applicationContext { application = "config control" }.getOrThrow()
            val result = Part21Writer.emit(listOf(appCtx))
            result.instances.single().id shouldBe 1
            result.nextId shouldBe 2
            result.idOf(appCtx) shouldBe 1
        }

        "idOf throws NoSuchElementException for an object not reachable from the emitted roots" {
            val reachable = applicationContext { application = "config control" }.getOrThrow()
            val unreachable = applicationContext { application = "other" }.getOrThrow()
            val result = Part21Writer.emit(listOf(reachable))
            shouldThrow<NoSuchElementException> { result.idOf(unreachable) }
        }
    })
