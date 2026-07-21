package dev.kstep.tests

import dev.kstep.express.semantic.ExpressSemanticModelBuilder
import dev.kstep.express.semantic.InheritanceResolver
import dev.kstep.express.semantic.SemanticModelException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Unit tests for [InheritanceResolver], the component that flattens a SUBTYPE OF chain into a
 * [dev.kstep.express.semantic.ResolvedEntity] for `ExpressKotlinCodeGenerator` to consume. See
 * `Ap242V1CodeGenTest` for the real-schema (NAUO/ProductContext) end-to-end verification of the
 * same resolver.
 */
class InheritanceResolverTest :
    StringSpec({
        "three-level single-inheritance chain flattens supertype-most-general-first, own attributes last" {
            val source =
                """
                SCHEMA chain_test;
                ENTITY grandparent;
                  a : STRING;
                END_ENTITY;
                ENTITY parent SUBTYPE OF (grandparent);
                  b : STRING;
                END_ENTITY;
                ENTITY child SUBTYPE OF (parent);
                  c : STRING;
                END_ENTITY;
                END_SCHEMA;
                """.trimIndent()
            val schema = ExpressSemanticModelBuilder.build(source).schemas.single()

            val resolved = InheritanceResolver.resolve(schema)
            val child = resolved.getValue("child")

            child.flattenedAttributes.map { it.attribute.name } shouldContainExactly listOf("a", "b", "c")
            child.flattenedAttributes.map { it.declaringEntity } shouldContainExactly
                listOf("grandparent", "parent", "child")
            child.ancestorChain shouldContainExactly listOf("grandparent", "parent")
            child.isInstantiable shouldBe true
        }

        "ABSTRACT SUPERTYPE resolves as not instantiable but still contributes its attributes" {
            val source =
                """
                SCHEMA abstract_test;
                ENTITY base ABSTRACT SUPERTYPE;
                  id : STRING;
                END_ENTITY;
                ENTITY derived SUBTYPE OF (base);
                  extra : STRING;
                END_ENTITY;
                END_SCHEMA;
                """.trimIndent()
            val schema = ExpressSemanticModelBuilder.build(source).schemas.single()

            val resolved = InheritanceResolver.resolve(schema)

            resolved.getValue("base").isInstantiable shouldBe false
            resolved.getValue("base").flattenedAttributes.map { it.attribute.name } shouldContainExactly listOf("id")
            resolved.getValue("derived").isInstantiable shouldBe true
            resolved.getValue("derived").flattenedAttributes.map { it.attribute.name } shouldContainExactly
                listOf("id", "extra")
        }

        "a SUBTYPE OF cycle throws SemanticModelException" {
            val source =
                """
                SCHEMA cycle_test;
                ENTITY a SUBTYPE OF (b);
                  x : STRING;
                END_ENTITY;
                ENTITY b SUBTYPE OF (a);
                  y : STRING;
                END_ENTITY;
                END_SCHEMA;
                """.trimIndent()
            val schema = ExpressSemanticModelBuilder.build(source).schemas.single()

            val exception = shouldThrow<SemanticModelException> { InheritanceResolver.resolve(schema) }
            exception.message shouldContain "cycle"
        }

        "a SUBTYPE OF chain deeper than 32 levels throws SemanticModelException" {
            // Declared deepest-first (e33 down to e1, root e0 last) rather than root-first: the
            // resolver caches each resolved entity, so if the entities were declared root-first,
            // resolve(schema)'s own top-level loop would already have cached every ancestor by
            // the time it reached the deepest entity, and the recursive descent this guard is
            // meant to catch would never actually happen (every ancestor lookup would be a cache
            // hit, one level deep, regardless of how long the chain is). Declaring the deepest
            // entity first forces resolve(schema) to hit it before any of its ancestors are
            // cached, so resolving it really does recurse the full, uncached chain depth.
            val entityCount = 34
            val source =
                buildString {
                    appendLine("SCHEMA deep_chain_test;")
                    for (i in entityCount - 1 downTo 1) {
                        appendLine("ENTITY e$i SUBTYPE OF (e${i - 1});")
                        appendLine("  a$i : STRING;")
                        appendLine("END_ENTITY;")
                    }
                    appendLine("ENTITY e0;")
                    appendLine("  a : STRING;")
                    appendLine("END_ENTITY;")
                    appendLine("END_SCHEMA;")
                }
            val schema = ExpressSemanticModelBuilder.build(source).schemas.single()

            val exception = shouldThrow<SemanticModelException> { InheritanceResolver.resolve(schema) }
            exception.message shouldContain "exceeds"
        }

        "SUBTYPE OF an unknown entity name throws SemanticModelException" {
            val source =
                """
                SCHEMA unresolved_supertype_test;
                ENTITY derived SUBTYPE OF (nonexistent_base);
                  extra : STRING;
                END_ENTITY;
                END_SCHEMA;
                """.trimIndent()
            val schema = ExpressSemanticModelBuilder.build(source).schemas.single()

            val exception = shouldThrow<SemanticModelException> { InheritanceResolver.resolve(schema) }
            exception.message shouldContain "nonexistent_base"
        }

        "SUBTYPE OF more than one supertype (EXPRESS multiple inheritance) throws SemanticModelException" {
            val source =
                """
                SCHEMA multiple_inheritance_test;
                ENTITY base_a;
                  a : STRING;
                END_ENTITY;
                ENTITY base_b;
                  b : STRING;
                END_ENTITY;
                ENTITY derived SUBTYPE OF (base_a, base_b);
                  extra : STRING;
                END_ENTITY;
                END_SCHEMA;
                """.trimIndent()
            val schema = ExpressSemanticModelBuilder.build(source).schemas.single()

            val exception = shouldThrow<SemanticModelException> { InheritanceResolver.resolve(schema) }
            exception.message shouldContain "multiple"
        }

        "a redeclared (SELF...RENAMED) attribute encountered while flattening throws SemanticModelException" {
            val source =
                """
                SCHEMA redeclared_inheritance_test;
                ENTITY base;
                  id : STRING;
                END_ENTITY;
                ENTITY derived SUBTYPE OF (base);
                  SELF\base.id RENAMED base_id : STRING;
                END_ENTITY;
                END_SCHEMA;
                """.trimIndent()
            val schema = ExpressSemanticModelBuilder.build(source).schemas.single()

            val exception = shouldThrow<SemanticModelException> { InheritanceResolver.resolve(schema) }
            exception.message shouldContain "RENAMED"
        }

        "a flattened property-name collision between two different ancestors throws SemanticModelException" {
            val source =
                """
                SCHEMA collision_test;
                ENTITY grandparent;
                  the_name : STRING;
                END_ENTITY;
                ENTITY parent SUBTYPE OF (grandparent);
                  the__name : STRING;
                END_ENTITY;
                END_SCHEMA;
                """.trimIndent()
            val schema = ExpressSemanticModelBuilder.build(source).schemas.single()

            val exception = shouldThrow<SemanticModelException> { InheritanceResolver.resolve(schema) }
            exception.message shouldContain "theName"
            exception.message shouldContain "grandparent"
            exception.message shouldContain "parent"
        }

        "resolveStandalone resolves a no-supertype entity trivially" {
            val source =
                """
                SCHEMA standalone_test;
                ENTITY widget;
                  id : STRING;
                END_ENTITY;
                END_SCHEMA;
                """.trimIndent()
            val schema = ExpressSemanticModelBuilder.build(source).schemas.single()
            val widget = schema.entities.single()

            val resolved = InheritanceResolver.resolveStandalone(widget)

            resolved.flattenedAttributes.map { it.attribute.name } shouldContainExactly listOf("id")
            resolved.ancestorChain shouldContainExactly emptyList()
            resolved.isInstantiable shouldBe true
        }

        "resolveStandalone rejects an entity that itself declares SUBTYPE OF" {
            val source =
                """
                SCHEMA standalone_rejects_subtype_test;
                ENTITY base;
                  id : STRING;
                END_ENTITY;
                ENTITY derived SUBTYPE OF (base);
                  extra : STRING;
                END_ENTITY;
                END_SCHEMA;
                """.trimIndent()
            val schema = ExpressSemanticModelBuilder.build(source).schemas.single()
            val derived = schema.entities.single { it.name == "derived" }

            shouldThrow<IllegalArgumentException> { InheritanceResolver.resolveStandalone(derived) }
        }
    })
