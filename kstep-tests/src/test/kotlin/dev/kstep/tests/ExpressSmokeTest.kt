package dev.kstep.tests

import dev.kstep.express.ExpressParserFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldNotBe

class ExpressSmokeTest :
    StringSpec({
        "AP242 subset fixture parses without syntax errors" {
            val source =
                requireNotNull(
                    ExpressSmokeTest::class.java.getResourceAsStream("/ap242-subset.exp"),
                ) { "fixture ap242-subset.exp not found on test classpath" }
                    .bufferedReader()
                    .use { it.readText() }

            val tree = ExpressParserFactory.parse(source)

            tree shouldNotBe null
            tree.schemaDecl().size shouldBeGreaterThanOrEqual 1
        }
    })
