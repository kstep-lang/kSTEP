package dev.kstep.desktop.status

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class AgentNoticeTest :
    StringSpec({
        "the texts and the duration are fixed" {
            AgentNotice.EDITED shouldBe "Edited by agent — Undo to revert"
            AgentNotice.RUN shouldBe "Run started by agent"
            AgentNotice.DURATION_MS shouldBe 4000L
        }

        "the same text again gets a new nonce, so the timer restarts and nothing stacks" {
            val first = AgentNotice.nextNotice(null to 0, AgentNotice.EDITED)
            val second = AgentNotice.nextNotice(first, AgentNotice.EDITED)
            first shouldBe (AgentNotice.EDITED to 1)
            second shouldBe (AgentNotice.EDITED to 2)
            AgentNotice.nextNotice(second, AgentNotice.RUN) shouldBe (AgentNotice.RUN to 3)
        }
    })
