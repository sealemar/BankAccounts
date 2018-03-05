package org.sealemar.BankAccounts

import io.kotlintest.matchers.shouldBe
import io.kotlintest.specs.StringSpec

class NoLimitBankAccountTest : StringSpec() {
    init {
        "should transfer to another NoLimitBankAccount correctly" {
            val act1 = NoLimitBankAccount()
            val act2 = NoLimitBankAccount()
            val amount = 5

            act1.transfer(act2, amount)

            act1.getBalance() shouldBe -act2.getBalance()
            act2.getBalance() shouldBe amount
        }
    }
}

class NonNegativeBankAccountTest : StringSpec() {
    init {
        "should transfer to another NonNegativeBankAccount correctly" {
            val amount = 5
            val act1 = NonNegativeBankAccount(amount)
            val act2 = NonNegativeBankAccount()

            act1.transfer(act2, amount)

            act1.getBalance() shouldBe 0
            act2.getBalance() shouldBe amount
        }

        "should make future payment if funds are not sufficient and process that future payment automatically once funds are available" {
            val amount2 = 5
            val amount3 = 7
            val act1 = NonNegativeBankAccount()
            val act2 = NonNegativeBankAccount(amount2)
            val act3 = NonNegativeBankAccount(amount3)

            // 1 owns 2 - $7

            act1.transfer(act2, amount3)

            act1.getBalance() shouldBe 0
            act2.getBalance() shouldBe amount2
            act3.getBalance() shouldBe amount3

            act1.getFuturePayments() shouldBe listOf(BankAccountWithFuturePayments.Payment(act2, amount3))
            act2.getFuturePayments().isEmpty() shouldBe true
            act3.getFuturePayments().isEmpty() shouldBe true

            // 2 pays 1 - $5
            // 2 has 0 balance
            // 1 still owns 2 - $7

            act2.transfer(act1, amount2)

            act1.getBalance() shouldBe amount2
            act2.getBalance() shouldBe 0
            act3.getBalance() shouldBe amount3

            act1.getFuturePayments() shouldBe listOf(BankAccountWithFuturePayments.Payment(act2, amount3))
            act2.getFuturePayments().isEmpty() shouldBe true
            act3.getFuturePayments().isEmpty() shouldBe true

            // 1 owns 3 - $5
            // 1 still has $5, but can't pay 3, because he
            //      first owns 2 - $7

            act1.transfer(act3, amount2)

            act1.getBalance() shouldBe amount2
            act2.getBalance() shouldBe 0
            act3.getBalance() shouldBe amount3

            act1.getFuturePayments() shouldBe listOf(
                BankAccountWithFuturePayments.Payment(act2, amount3),
                BankAccountWithFuturePayments.Payment(act3, amount2)
            )
            act2.getFuturePayments().isEmpty() shouldBe true
            act3.getFuturePayments().isEmpty() shouldBe true

            // 2 owns 3 - $5
            // 1 still has $5, owns 2 - $7 and then owns 3 - $5

            act2.transfer(act3, amount2)

            act1.getBalance() shouldBe amount2
            act2.getBalance() shouldBe 0
            act3.getBalance() shouldBe amount3

            act1.getFuturePayments() shouldBe listOf(
                BankAccountWithFuturePayments.Payment(act2, amount3),
                BankAccountWithFuturePayments.Payment(act3, amount2)
            )
            act2.getFuturePayments() shouldBe listOf(
                BankAccountWithFuturePayments.Payment(act3, amount2)
            )
            act3.getFuturePayments().isEmpty() shouldBe true

            // 3 pays 1 - $7
            // that triggers future payments resolution:
            // 1)   1 pays 2 - $7
            // 2.1) 2 pays 3 - $5
            // 2.2) 1 pays 3 - $5
            // 2.1 and 2.2 will be done in parallel when multi-threaded
            // nobody owns anyone anything

            act3.transfer(act1, amount3)

            act1.getBalance() shouldBe 0
            act2.getBalance() shouldBe amount3 - amount2
            act3.getBalance() shouldBe amount2 + amount2

            act1.getFuturePayments().isEmpty() shouldBe true
            act2.getFuturePayments().isEmpty() shouldBe true
            act3.getFuturePayments().isEmpty() shouldBe true
        }
    }
}

class OverdraftBankAccountTest : StringSpec() {
    init {
        "should transfer from the primary account to another OverdraftBankAccount when it has funds" {
            val amount = 5
            val act1 = OverdraftBankAccount(OverdraftAccount(-amount), amount)
            val act2 = OverdraftBankAccount(OverdraftAccount(-amount))

            act1.transfer(act2, amount)

            act1.getBalance() shouldBe 0
            act2.getBalance() shouldBe amount

            act1.overdraftAccount.getBalance() shouldBe 0
            act2.overdraftAccount.getBalance() shouldBe 0
        }

        "should transfer from the overdraft account to another OverdraftBankAccount.primary when it does not have funds on primary" {
            val amount = 5
            val act1 = OverdraftBankAccount(OverdraftAccount(-amount))
            val act2 = OverdraftBankAccount(OverdraftAccount(-amount))

            act1.transfer(act2, amount)

            act1.getBalance() shouldBe 0
            act2.getBalance() shouldBe amount

            act1.overdraftAccount.getBalance() shouldBe -amount
            act2.overdraftAccount.getBalance() shouldBe 0
        }

        "should transfer from the primary account to another OverdraftBankAccount.overdraft when it has funds and the payee has a negative overdraft balance" {
            val amount = 5
            val act1 = OverdraftBankAccount(OverdraftAccount(-amount))
            val act2 = OverdraftBankAccount(OverdraftAccount(-amount, -amount))

            act1.transfer(act2, amount)

            act1.getBalance() shouldBe 0
            act2.getBalance() shouldBe 0

            act1.overdraftAccount.getBalance() shouldBe -amount
            act2.overdraftAccount.getBalance() shouldBe 0
        }

        "should transfer from the primary account to another OverdraftBankAccount.overdraft and primary when it has funds and the payee has a negative overdraft balance less than the payment amount" {
            val amount = 5
            val act1 = OverdraftBankAccount(OverdraftAccount(-amount))
            val act2 = OverdraftBankAccount(OverdraftAccount(-amount, -amount + 2))

            act1.transfer(act2, amount)

            act1.getBalance() shouldBe 0
            act2.getBalance() shouldBe 2

            act1.overdraftAccount.getBalance() shouldBe -amount
            act2.overdraftAccount.getBalance() shouldBe 0
        }

        "should create a future payment if it does not have funds on primary and overdraft is at limit" {
            val amount = 5
            val act1 = OverdraftBankAccount(OverdraftAccount(-amount, -amount))
            val act2 = OverdraftBankAccount(OverdraftAccount(-amount))

            act1.transfer(act2, amount)

            act1.getBalance() shouldBe 0
            act2.getBalance() shouldBe 0

            act1.overdraftAccount.getBalance() shouldBe -amount
            act2.overdraftAccount.getBalance() shouldBe 0

            act1.getFuturePayments() shouldBe listOf(BankAccountWithFuturePayments.Payment(act2, amount))
            act2.getFuturePayments().isEmpty() shouldBe true
        }
    }
}
