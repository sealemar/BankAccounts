package org.sealemar.BankAccounts.functional

import io.kotlintest.matchers.shouldBe
import io.kotlintest.specs.StringSpec
import org.sealemar.BankAccounts.ConcurrentBank
import org.sealemar.BankAccounts.NoLimitBankAccount
import org.sealemar.BankAccounts.repeatIf
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BankTest : StringSpec() {
    init {
        "should transfer between NoLimitBankAccounts and always return a correct bank total when run in thread pool" {
            val initialBalance = 10
            val bank = ConcurrentBank(
                Array(10, { NoLimitBankAccount(initialBalance) }),
                10 * 1000,  // extremely high load system => increased timeouts
                10 * 1000
            )

            val random = Random()
            val assertions = ConcurrentLinkedQueue<AssertionError>()

            val workerFuncs = listOf({
                val act = { random.nextInt(bank.numberOfAccounts) }
                val a = act()
                val b = repeatIf(0, act) { it == a }!!

                bank.transfer(a, b, random.nextInt(initialBalance) + 1)
            }, {
                bank.totalBalance shouldBe initialBalance * bank.numberOfAccounts
            })

            Executors.newFixedThreadPool(20).let {
                try {
                    for(i in 1..1_000_000) {
                        it.execute {
                            try {
                                workerFuncs[random.nextInt(workerFuncs.size)]()
                            } catch (e : AssertionError) {
                                assertions.add(e)
                                throw e
                            }
                        }
                    }
                } finally {
                    it.shutdown()
                    it.awaitTermination(10, TimeUnit.MINUTES)
                }
            }

            assertions.isEmpty() shouldBe true

            bank.totalBalance shouldBe initialBalance * bank.numberOfAccounts
        }
    }
}
