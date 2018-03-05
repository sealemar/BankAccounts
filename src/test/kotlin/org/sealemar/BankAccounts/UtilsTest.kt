package org.sealemar.BankAccounts

import io.kotlintest.matchers.shouldBe
import io.kotlintest.specs.StringSpec

class ConcurrentLatchTest : StringSpec() {
    init {
        "should open latch once" {
            val latch = ConcurrentLatch()
            latch.open() shouldBe latch
            latch.open() shouldBe null
            latch.close()
            latch.open() shouldBe latch
            latch.close()
            latch.open()?.use {
                latch.open() shouldBe null
            } // latch will be auto closed after use
            latch.open() shouldBe latch
        }
    }
}


class RepeatIfTest : StringSpec() {
    init {
        "should repeat no more than n times" {
            val repeatCount = 5
            var repeats = 0
            repeatIf(repeatCount, { repeats += 1 }, { true })
            repeats shouldBe repeatCount
        }

        "should not repeat if condition is met" {
            val repeatCount = 5
            var repeats = 0
            repeatIf(repeatCount, { repeats += 1 }, { false })
            repeats shouldBe 1
        }
    }
}
