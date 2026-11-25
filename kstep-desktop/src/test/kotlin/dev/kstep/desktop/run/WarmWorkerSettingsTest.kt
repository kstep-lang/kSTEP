package dev.kstep.desktop.run

import dev.kstep.desktop.run.WarmWorkerSettings.Mode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class WarmWorkerSettingsTest :
    StringSpec({
        fun resolve(
            property: String? = null,
            env: String? = null,
            maxRuns: String? = null,
            idle: String? = null,
        ) = WarmWorkerSettings.resolve(property, env, maxRuns, idle)

        "nothing set gives the default mode, spare" {
            resolve().mode shouldBe Mode.SPARE
            resolve().maxRuns shouldBe 1
            resolve().enabled shouldBe true
        }

        "off, false, 0 and the other off words switch the warm worker off" {
            for (word in listOf("off", "false", "0", "no", "OFF", " False ", "cold", "disabled")) {
                resolve(property = word).mode shouldBe Mode.OFF
                resolve(property = word).enabled shouldBe false
            }
        }

        "reuse takes the run count within its bounds and the default outside them" {
            resolve(property = "reuse").mode shouldBe Mode.REUSE
            resolve(property = "reuse").maxRuns shouldBe 20
            resolve(property = "reuse", maxRuns = "5").maxRuns shouldBe 5
            resolve(property = "reuse", maxRuns = "1").maxRuns shouldBe 20
            resolve(property = "reuse", maxRuns = "101").maxRuns shouldBe 20
            resolve(property = "reuse", maxRuns = "many").maxRuns shouldBe 20
            resolve(property = "spare", maxRuns = "5").maxRuns shouldBe 1
        }

        "the idle time is bounded, outside the bounds it is the default" {
            resolve(idle = "5000").idleMillis shouldBe 5000
            resolve(idle = "999").idleMillis shouldBe 120_000
            resolve(idle = "600001").idleMillis shouldBe 120_000
            resolve(idle = "x").idleMillis shouldBe 120_000
        }

        "the property wins over the environment, the environment is used without a property" {
            resolve(property = "off", env = "reuse").mode shouldBe Mode.OFF
            resolve(env = "reuse").mode shouldBe Mode.REUSE
            resolve(env = "off").mode shouldBe Mode.OFF
        }

        "an unknown value never switches reuse on" {
            resolve(property = "turbo").mode shouldBe Mode.SPARE
            resolve(property = "reuse!").mode shouldBe Mode.SPARE
            resolve(property = "").mode shouldBe Mode.SPARE
        }
    })
