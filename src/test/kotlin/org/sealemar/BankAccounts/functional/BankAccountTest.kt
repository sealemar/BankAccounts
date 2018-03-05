package org.sealemar.BankAccounts.functional

import io.kotlintest.matchers.shouldBe
import io.kotlintest.specs.StringSpec
import org.sealemar.BankAccounts.*
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit


inline fun <reified E : BankAccount>testInThreadPool(initialBalance : Int, noinline elementInitializer: (Int) -> E, noinline assertResultFunction: (Array<E>) -> Unit) {
    val accounts = Array(10, elementInitializer)

    val random = Random()

    Executors.newFixedThreadPool(20).let {
        try {
            for(i in 1..1_000_000) {
                it.execute {
                    val act = { random.nextInt(accounts.size) }
                    val a = act()
                    val b = repeatIf(0, act) { it == a }!!

                    accounts[a].transfer(accounts[b], random.nextInt(initialBalance) + 1)
                }
            }
        } finally {
            it.shutdown()
            it.awaitTermination(10, TimeUnit.MINUTES)
        }
    }

    assertResultFunction(accounts)
}

fun <E : BankAccountWithFuturePayments> creditAndProcessFuturePayments(accounts: Array<E>) {
    accounts.map {
        Pair(it, it.getFuturePayments().sumBy { it.amount })
    }.filter {
        it.second > 0
    }.forEach { (account, amount) ->
        with(account) {
            credit(amount)
            processFuturePayments()
        }
    }
}


class NoLimitBankAccountTest : StringSpec() {
    init {
        "should transfer to another NoLimitBankAccount correctly on threads" {
            val initialBalance = 10

            testInThreadPool(
                initialBalance,
                { NoLimitBankAccount(initialBalance) }
            ) { accounts ->
                accounts.sumBy { it.getBalance() } shouldBe initialBalance * accounts.size
            }
        }
    }
}


class NonNegativeBankAccountTest : StringSpec() {
    init {
        "should transfer to another NonNegativeBankAccount correctly on threads" {
            val initialBalance = 10

            testInThreadPool(
                initialBalance,
                { NonNegativeBankAccount(initialBalance) }
            ) { accounts ->
                accounts.sumBy { it.getBalance() } shouldBe initialBalance * accounts.size
                creditAndProcessFuturePayments(accounts)
                accounts.sumBy { it.getFuturePayments().sumBy { it.amount } } shouldBe 0
            }
        }
    }
}


class OverdraftBankAccountTest : StringSpec() {
    init {
        "should transfer to another OverdraftBankAccount correctly on threads" {
            val initialBalance = 10
            val overdraftLimit = -20

            testInThreadPool(
                initialBalance,
                { OverdraftBankAccount(OverdraftAccount(overdraftLimit), initialBalance) }
            ) { accounts ->
                accounts.sumBy { it.getBalance() + it.overdraftAccount.getBalance() } shouldBe initialBalance * accounts.size
                creditAndProcessFuturePayments(accounts)
                accounts.sumBy { it.getFuturePayments().sumBy { it.amount } } shouldBe 0
                accounts.all { it.overdraftAccount.getBalance() == 0 } shouldBe true
            }
        }
    }
}
