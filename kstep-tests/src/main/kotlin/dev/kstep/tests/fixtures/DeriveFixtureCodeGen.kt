package dev.kstep.tests.fixtures

import dev.kstep.express.codegen.CodeGenOptions
import dev.kstep.express.codegen.DerivedAttributeMode
import dev.kstep.express.codegen.ExpressKotlinCodeGenerator
import dev.kstep.express.semantic.ExpressSemanticModelBuilder
import java.io.File

/**
 * Entry point of the `generateDeriveFixtureKotlin` Gradle task (kstep-tests/build.gradle.kts):
 * generates Kotlin from the DERIVE fixture schema with [DerivedAttributeMode.STRICT], so that a
 * DERIVE the generator cannot translate fails the build instead of silently disappearing. The output
 * is compiled into the test source set, where `DerivedAttributeRuntimeParityTest` compares every
 * generated property with the reflective evaluator (ADR-0021).
 *
 * Usage: `<schemaFile> <packageName> <outputDir>`.
 */
fun main(args: Array<String>) {
    require(args.size == 3) { "usage: DeriveFixtureCodeGenKt <schemaFile> <packageName> <outputDir>" }
    val schema = ExpressSemanticModelBuilder.build(File(args[0]).readText()).schemas.single()
    val fileSpec =
        ExpressKotlinCodeGenerator.generateFile(
            schema,
            args[1],
            options = CodeGenOptions(derivedAttributeMode = DerivedAttributeMode.STRICT),
        )
    val outputDir = File(args[2])
    outputDir.mkdirs()
    fileSpec.writeTo(outputDir)
}
