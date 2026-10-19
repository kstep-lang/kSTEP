package dev.kstep.tests

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.STRING
import dev.kstep.express.codegen.Ap242V1CodeGen
import dev.kstep.express.codegen.CodeGenException
import dev.kstep.express.codegen.CodeGenOptions
import dev.kstep.express.codegen.EXPECTED_CHAIN_DERIVED
import dev.kstep.express.codegen.EXPECTED_EMITTED_DEFINED_TYPES
import dev.kstep.express.codegen.EXPECTED_POPULATION_DERIVED
import dev.kstep.express.codegen.EXPECTED_UNSUPPORTED_DERIVED
import dev.kstep.express.codegen.ap242DriftProblems
import dev.kstep.express.semantic.ExpressSemanticModelBuilder
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.net.JarURLConnection
import java.net.URL

/**
 * M1 Welle 4, deliverable 3: regenerates the six V1 AP242 entities from the real-schema
 * extraction (`ap242-v1-entities.exp`, see its header comment for provenance) via
 * [Ap242V1CodeGen] — the same object backing the `generateExpressKotlin` Gradle task (see
 * kstep-express/build.gradle.kts). As of the SUBTYPE OF inheritance-flattening wave, all six
 * entities generate (previously five of six; `next_assembly_usage_occurrence`'s real SUBTYPE OF
 * chain now flattens via `dev.kstep.express.semantic.InheritanceResolver` instead of throwing).
 */
private fun syntheticSchema(body: String) =
    ExpressSemanticModelBuilder
        .build("SCHEMA synthetic_ap242_test;\n$body\nEND_SCHEMA;")
        .schemas
        .single()

private fun topLevelClassNames(packagePath: String): Set<String> {
    val urls: List<URL> =
        Thread
            .currentThread()
            .contextClassLoader
            .getResources(packagePath)
            .toList()
    val names = mutableSetOf<String>()
    urls.forEach { url ->
        when (url.protocol) {
            "file" -> File(url.toURI()).listFiles().orEmpty().forEach { names += it.name }
            "jar" -> {
                // The JarFile is cached/shared by the JVM; deliberately not closed here.
                val jar = (url.openConnection() as JarURLConnection).jarFile
                jar.entries().asSequence().forEach { entry ->
                    if (entry.name.startsWith("$packagePath/") && '/' !in entry.name.removePrefix("$packagePath/")) {
                        names += entry.name.removePrefix("$packagePath/")
                    }
                }
            }
            else -> error("unsupported classpath protocol ${url.protocol} for $url")
        }
    }
    return names
        .filter { it.endsWith(".class") }
        .map { it.removeSuffix(".class") }
        .filter { '$' !in it && !it.endsWith("Kt") && it != "package-info" }
        .toSet()
}

class Ap242V1CodeGenTest :
    StringSpec({
        "codegen succeeds for all ten target entities against real-schema ground truth" {
            val outcome = Ap242V1CodeGen.generate()

            outcome.generatedEntityNames shouldContainExactlyInAnyOrder
                listOf(
                    "product",
                    "product_definition",
                    "product_definition_formation",
                    "next_assembly_usage_occurrence",
                    "approval",
                    "person_and_organization",
                    "product_definition_occurrence",
                    "name_attribute",
                    "description_attribute",
                    "id_attribute",
                )
            outcome.skipped.shouldBeEmpty()
        }

        "real-schema next_assembly_usage_occurrence flattens its SUBTYPE OF chain to six parameters" {
            val outcome = Ap242V1CodeGen.generate()

            val nauo = outcome.fileSpec.typeSpecs.single { it.name == "NextAssemblyUsageOccurrence" }
            val parameters = nauo.primaryConstructor!!.parameters
            parameters.map { it.name } shouldBe
                listOf(
                    "id",
                    "name",
                    "description",
                    "relatingProductDefinition",
                    "relatedProductDefinition",
                    "referenceDesignator",
                )
            // SELECT product_definition_or_reference (ADR-0023), no longer the narrowed product_definition.
            val productDefinitionType = ClassName(Ap242V1CodeGen.DEFAULT_PACKAGE_NAME, "ProductDefinitionOrReference")
            parameters.single { it.name == "id" }.type shouldBe STRING
            parameters.single { it.name == "name" }.type shouldBe STRING
            parameters.single { it.name == "description" }.type shouldBe STRING.copy(nullable = true)
            parameters.single { it.name == "relatingProductDefinition" }.type shouldBe productDefinitionType
            parameters.single { it.name == "relatedProductDefinition" }.type shouldBe productDefinitionType
            parameters.single { it.name == "referenceDesignator" }.type shouldBe STRING.copy(nullable = true)
        }

        "product's real-schema constructor resolves id/name TYPE aliases to String and includes frame_of_reference" {
            val outcome = Ap242V1CodeGen.generate()

            val product = outcome.fileSpec.typeSpecs.single { it.name == "Product" }
            val parameters = product.primaryConstructor!!.parameters
            parameters.map { it.name } shouldBe listOf("id", "name", "description", "frameOfReference")
            parameters.single { it.name == "id" }.type shouldBe STRING
            parameters.single { it.name == "name" }.type shouldBe STRING
            // description is OPTIONAL text -> nullable String.
            parameters.single { it.name == "description" }.type shouldBe STRING.copy(nullable = true)
        }

        "approval's real-schema constructor resolves level (a label TYPE alias) to String" {
            val outcome = Ap242V1CodeGen.generate()

            val approval = outcome.fileSpec.typeSpecs.single { it.name == "Approval" }
            val levelParameter = approval.primaryConstructor!!.parameters.single { it.name == "level" }
            levelParameter.type shouldBe STRING
        }

        "the emitted file contains all target entities, support entities and the four SELECTs, fully self-contained" {
            val outcome = Ap242V1CodeGen.generate()

            outcome.fileSpec.typeSpecs.map { it.name } shouldContainExactlyInAnyOrder
                listOf(
                    "Product",
                    "ProductDefinition",
                    "ProductDefinitionFormation",
                    "NextAssemblyUsageOccurrence",
                    "Approval",
                    "PersonAndOrganization",
                    "ApprovalStatus",
                    "Person",
                    "Organization",
                    "ProductContext",
                    "ProductDefinitionContext",
                    "ApplicationContext",
                    "ProductDefinitionOccurrence",
                    "NameAttribute",
                    "DescriptionAttribute",
                    "IdAttribute",
                    "ProductDefinitionOrReference",
                    "NameAttributeSelect",
                    "DescriptionAttributeSelect",
                    "IdAttributeSelect",
                )
        }

        // kSTEP M2 Welle 10: generate() defaults to CodeGenOptions() (plain public constructor,
        // unchanged from every test above — the regression proof that the new option is
        // opt-in, not a silent behavior change).
        "generate() with the default CodeGenOptions still emits a plain public constructor" {
            val outcome = Ap242V1CodeGen.generate()
            val product = outcome.fileSpec.typeSpecs.single { it.name == "Product" }
            product.primaryConstructor!!.modifiers shouldBe emptySet()
        }

        // Ap242V1CodeGen.CORE_MODULE_OPTIONS is what generateExpressKotlin's main() actually uses
        // to feed kstep-core's own source set (see kstep-express/build.gradle.kts) — this is the
        // direct proof that option reaches the emitted FileSpec correctly, independent of the
        // Gradle wiring (already separately verified by kstep-core:compileKotlin succeeding).
        "generate() with CORE_MODULE_OPTIONS emits an internal constructor and @ConsistentCopyVisibility" {
            val outcome = Ap242V1CodeGen.generate(options = Ap242V1CodeGen.CORE_MODULE_OPTIONS)
            val product = outcome.fileSpec.typeSpecs.single { it.name == "Product" }
            product.primaryConstructor!!.modifiers shouldBe setOf(KModifier.INTERNAL)
            product.annotations.map { it.typeName.toString() } shouldContainExactlyInAnyOrder
                listOf("kotlin.ConsistentCopyVisibility")
        }

        "generate() with CORE_MODULE_OPTIONS still generates the same sixteen types as the default options" {
            val default = Ap242V1CodeGen.generate()
            val coreModule = Ap242V1CodeGen.generate(options = Ap242V1CodeGen.CORE_MODULE_OPTIONS)
            coreModule.generatedEntityNames shouldContainExactlyInAnyOrder default.generatedEntityNames
            coreModule.fileSpec.typeSpecs.map { it.name } shouldContainExactlyInAnyOrder
                default.fileSpec.typeSpecs.map { it.name }
        }
        "the real V1 extraction emits exactly the four SELECTs as sealed interfaces and no enumeration" {
            val outcome = Ap242V1CodeGen.generate()

            outcome.emittedDefinedTypeNames.toSet() shouldBe EXPECTED_EMITTED_DEFINED_TYPES
            outcome.fileSpec.typeSpecs.none { it.isEnum } shouldBe true
            outcome.fileSpec.typeSpecs
                .filter { it.kind == com.squareup.kotlinpoet.TypeSpec.Kind.INTERFACE }
                .map { it.name } shouldContainExactlyInAnyOrder
                listOf(
                    "ProductDefinitionOrReference",
                    "NameAttributeSelect",
                    "DescriptionAttributeSelect",
                    "IdAttributeSelect",
                )
        }

        "only reachable TYPEs are emitted, even when the caller passes the default ALL scope" {
            val schema =
                syntheticSchema(
                    """
                    ENTITY target_e;
                      s : used_enum;
                    END_ENTITY;
                    TYPE used_enum = ENUMERATION OF (a);
                    END_TYPE;
                    TYPE unused_enum = ENUMERATION OF (b);
                    END_TYPE;
                    """.trimIndent(),
                )

            val outcome = Ap242V1CodeGen.generate(schema, listOf("target_e"), emptyList(), "x.y", CodeGenOptions())

            outcome.fileSpec.typeSpecs.map { it.name } shouldContainExactlyInAnyOrder listOf("TargetE", "UsedEnum")
            outcome.emittedDefinedTypeNames shouldBe listOf("used_enum")
        }

        "a reachable SELECT with a member entity outside targets and support entities fails loudly" {
            val schema =
                syntheticSchema(
                    """
                    ENTITY target_e;
                      p : pick;
                    END_ENTITY;
                    ENTITY other_e;
                      v : STRING;
                    END_ENTITY;
                    TYPE pick = SELECT (other_e);
                    END_TYPE;
                    """.trimIndent(),
                )

            val exception =
                shouldThrow<CodeGenException> {
                    Ap242V1CodeGen.generate(schema, listOf("target_e"), emptyList(), "x.y", CodeGenOptions())
                }
            exception.message shouldContain "no emitted Kotlin implementor"
        }

        "generated names that collide with hand-written kstep-core types are rejected" {
            val enumCollision =
                syntheticSchema(
                    """
                    ENTITY target_e;
                      s : product_builder;
                    END_ENTITY;
                    TYPE product_builder = ENUMERATION OF (a);
                    END_TYPE;
                    """.trimIndent(),
                )
            val enumException =
                shouldThrow<CodeGenException> {
                    Ap242V1CodeGen.generate(enumCollision, listOf("target_e"), emptyList(), "x.y", CodeGenOptions())
                }
            enumException.message shouldContain "ProductBuilder"
            enumException.message shouldContain "kstep-core"

            val entityCollision =
                syntheticSchema(
                    """
                    ENTITY validation_result;
                      v : STRING;
                    END_ENTITY;
                    """.trimIndent(),
                )
            val entityException =
                shouldThrow<CodeGenException> {
                    Ap242V1CodeGen.generate(
                        entityCollision,
                        listOf("validation_result"),
                        emptyList(),
                        "x.y",
                        CodeGenOptions(),
                    )
                }
            entityException.message shouldContain "ValidationResult"
        }

        "the reserved-name list covers every hand-written top-level class of the kstep-core packages" {
            val actual = topLevelClassNames("dev/kstep/core") + topLevelClassNames("dev/kstep/core/ap242")

            actual.isNotEmpty() shouldBe true
            (actual - Ap242V1CodeGen.KSTEP_CORE_RESERVED_CLASS_NAMES) shouldBe emptySet()
        }

        "every real V1 DERIVE clause is translated: five population-dependent functions and one SELECT-cast chain" {
            val outcome = Ap242V1CodeGen.generate()

            outcome.unsupportedDerivedAttributes.shouldBeEmpty()
            outcome.populationDerivedAttributes.map { "${it.entity}.${it.attribute}" } shouldContainExactlyInAnyOrder
                listOf(
                    "product_definition.name",
                    "person_and_organization.name",
                    "person_and_organization.description",
                    "application_context.id",
                    "application_context.description",
                )
            outcome.chainDerivedAttributes.map { "${it.entity}.${it.attribute}" } shouldBe
                listOf("next_assembly_usage_occurrence.product_definition_occurrence_id")
            ap242DriftProblems(outcome) shouldBe emptyList()

            // Population-dependent DERIVEs are member functions taking a scope, never properties; only the
            // chain DERIVE is a (nullable) property, so the shape seen by Part 21, MCP and the builders is unchanged.
            outcome.fileSpec.typeSpecs.filter { it.primaryConstructor != null }.forEach { type ->
                val extra =
                    type.propertySpecs.map { it.name } -
                        type.primaryConstructor!!
                            .parameters
                            .map { it.name }
                            .toSet()
                extra shouldBe
                    if (type.name ==
                        "NextAssemblyUsageOccurrence"
                    ) {
                        listOf("productDefinitionOccurrenceId")
                    } else {
                        emptyList()
                    }
            }
            val product = outcome.fileSpec.typeSpecs.single { it.name == "ProductDefinition" }
            val name = product.funSpecs.single { it.name == "name" }
            name.parameters.map { it.name to it.type.toString() } shouldBe
                listOf("scope" to "dev.kstep.express.validation.DerivedValueScope")
            name.returnType shouldBe STRING.copy(nullable = true)
            outcome.fileSpec.typeSpecs
                .single { it.name == "NextAssemblyUsageOccurrence" }
                .propertySpecs
                .single { it.name == "productDefinitionOccurrenceId" }
                .type shouldBe STRING.copy(nullable = true)
        }

        "the drift guard reports a mismatch in both directions" {
            val outcome = Ap242V1CodeGen.generate()

            ap242DriftProblems(outcome, expectedPopulation = EXPECTED_POPULATION_DERIVED - "product_definition.name")
                .single() shouldContain "unexpected [product_definition.name]"
            ap242DriftProblems(outcome, expectedChain = EXPECTED_CHAIN_DERIVED + "x.y").single() shouldContain
                "missing [x.y]"
            ap242DriftProblems(outcome, expectedUnsupported = EXPECTED_UNSUPPORTED_DERIVED + "x.z")
                .single() shouldContain "missing [x.z]"
            ap242DriftProblems(outcome).shouldBeEmpty()
        }

        "the generated population-dependent function documents its semantics" {
            val outcome = Ap242V1CodeGen.generate()
            val kdoc =
                outcome.fileSpec.typeSpecs
                    .single { it.name == "ApplicationContext" }
                    .funSpecs
                    .single { it.name == "id" }
                    .kdoc
                    .toString()
            kdoc shouldContain "get_id_value(SELF)"
            kdoc shouldContain "IndeterminateDerivedValueException"
            outcome.fileSpec.typeSpecs.shouldNotBeEmpty()
        }
    })
