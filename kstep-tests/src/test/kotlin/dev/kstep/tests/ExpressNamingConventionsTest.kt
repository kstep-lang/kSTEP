package dev.kstep.tests

import dev.kstep.express.codegen.NamingConventions
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ExpressNamingConventionsTest :
    StringSpec({
        "toClassName converts snake_case entity names to PascalCase" {
            NamingConventions.toClassName("person_and_organization") shouldBe "PersonAndOrganization"
            NamingConventions.toClassName("product") shouldBe "Product"
            NamingConventions.toClassName("next_assembly_usage_occurrence") shouldBe "NextAssemblyUsageOccurrence"
        }

        "toPropertyName converts snake_case attribute names to camelCase" {
            NamingConventions.toPropertyName("the_person") shouldBe "thePerson"
            NamingConventions.toPropertyName("id") shouldBe "id"
            NamingConventions.toPropertyName("relating_product_definition") shouldBe "relatingProductDefinition"
        }

        "escapeIfKotlinKeyword backtick-escapes a Kotlin hard keyword" {
            NamingConventions.escapeIfKotlinKeyword("class") shouldBe "`class`"
            NamingConventions.escapeIfKotlinKeyword("id") shouldBe "id"
        }
    })
