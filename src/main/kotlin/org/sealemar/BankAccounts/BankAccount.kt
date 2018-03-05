package org.sealemar.BankAccounts

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

abstract class BankAccount(
    initialBalance : Int
) {
    internal var balance = AtomicInteger(initialBalance)

    fun getBalance() = balance.get()

    abstract fun transfer(toAccount : BankAccount, amount : Int)

    /**
     * Called inside payerAccount.transfer()
     */
    internal open fun credit(amount: Int) = balance.addAndGet(amount)
}

/**
 * Future payments are resolved in order, such that if
 * A has zero balance and
 * A owns B - $5 and
 * A owns C - $3, then
 * if A is paid $3, he does not pay C, but waits until
 * he gets at least $2 more to pay B first
 */
open class BankAccountWithFuturePayments(
    initialBalance: Int = 0,
    protected val retryCount: Int = 5
) : BankAccount(initialBalance) {

    data class Payment(val toAccount: BankAccount, val amount: Int)

    private val futurePayments = ConcurrentLinkedQueue<Payment>()
    private val futurePaymentsLatch = ConcurrentLatch()

    fun getFuturePayments() = futurePayments.toList()

    /**
     * Processes future payments.
     * Future payments are processed in order.
     * Newer payments are not processed until older obligations have been met.
     */
    fun processFuturePayments() {
        // ConcurrentLatch guarantees that another thread will not process the function
        // on the same account if the latch is closed.
        futurePaymentsLatch.open()?.use {
            generateSequence {
                futurePayments.peek()?.let {
                    transferWithFuturePayment(it.toAccount, it.amount, true)
                        .takeIf { it }  // stop generating sequence on the first transfer that fails
                        ?.let { futurePayments.poll() }
                }
            }.mapNotNull { it.toAccount as? BankAccountWithFuturePayments }
            .distinct()
            .toList()  // don't be lazy - collect all elements - process payments on the current account first
            .forEach {
                it.processFuturePayments()
            }
        }
    }

    private fun transferWithFuturePayment(toAccount: BankAccount, amount: Int, isFuturePayment: Boolean) : Boolean {
        return repeatIf(retryCount, {
            doTransfer(toAccount, amount, isFuturePayment)
        }) {
            it == 2
        }?.let {
            it == 0
        } ?: throw RetriesExhaustedException()
    }

    override fun transfer(toAccount: BankAccount, amount: Int) {
        assert(amount > 0) { "Amount should be positive, but it is $amount" }
        transferWithFuturePayment(toAccount, amount, false)
            .takeIf { it }
            ?.let {
                (toAccount as? BankAccountWithFuturePayments)?.processFuturePayments()
            }
    }

    /**
     * @return 0 if transfer succeeded
     *         1 if a future payment was created or it is a future payment and it can not be processed
     *         2 if transfer needs to be retried due to concurrency issues
     */
    private fun doTransfer(toAccount: BankAccount, amount: Int, isFuturePayment: Boolean) : Int {
        if(!isFuturePayment && futurePayments.isNotEmpty()) {
            futurePayments.add(Payment(toAccount, amount))
            return 1  // future payment created
        } else {
            val bal = balance.get()
            if(bal >= amount) {
                if (!balance.compareAndSet(bal, bal - amount)) {
                    return 2  // retry - concurrency issue
                }
            } else {
                processInsufficientBalance(toAccount, amount, bal)
                    .takeIf { it != 0 }
                    ?.let { result ->
                        if(result == 1 && !isFuturePayment) {
                            futurePayments.add(Payment(toAccount, amount))
                        }
                        return result
                    }
            }
            toAccount.credit(amount)
        }
        return 0  // transfer succeeded
    }

    /**
     * @return 0 if transfer succeeded
     *         1 if a future payment needs to be created
     *         2 if transfer needs to be retried due to concurrency issues
     */
    protected open fun processInsufficientBalance(toAccount: BankAccount, amount: Int, expectedBalance : Int) : Int {
        return 1  // future payment created
    }
}

class RetriesExhaustedException : RuntimeException()

/**
 * Balance can go below zero
 */
class NoLimitBankAccount(initialBalance: Int = 0) : BankAccount(initialBalance) {
    override fun transfer(toAccount: BankAccount, amount: Int) {
        assert(amount > 0) { "Amount should be positive, but it is $amount" }

        balance.addAndGet(-amount)
        toAccount.credit(amount)
    }
}

/**
 * NonNegativeBankAccount balance can not go below zero.
 * If an account does not have sufficient funds, a future payment is created
 * that is resolved once the account has enough money to pay.
 */
typealias NonNegativeBankAccount = BankAccountWithFuturePayments

/**
 * @param overdraftLimit negative number
 * @param balance can go negative up to overdraftLimit
 */
class OverdraftAccount(
    val overdraftLimit: Int,
    initialBalance: Int = 0
) {
    internal var balance = AtomicInteger(initialBalance)
    fun getBalance() = balance.get()
}

/**
 * If primary bank account does not have sufficient funds to pay, the
 * funds are drawn from an OverdraftAccount.
 * When OverdraftAccount has a negative balance, all payments to the
 * primary account are automatically transferred to the OverdraftAccount
 * until the balance there is brought to zero. All payments after that
 * stay on the primary account.
 */
class OverdraftBankAccount(
    val overdraftAccount: OverdraftAccount,
    initialBalance: Int = 0,
    retryCount: Int = 5
) : BankAccountWithFuturePayments(initialBalance, retryCount) {

    override fun credit(amount: Int) : Int {
        repeat(retryCount) {
            overdraftAccount.balance.get().let retry@ { overdraft ->
                val primeAccountDelta = if (overdraft + amount > 0) { overdraft + amount } else { 0 }
                val overdraftDelta = if (overdraft + amount >= 0) { 0 } else { overdraft + amount }
                if(overdraft < 0) {
                    if(!overdraftAccount.balance.compareAndSet(overdraft, overdraftDelta)) {
                        return@retry
                    }
                }
                return if(primeAccountDelta > 0) {
                    balance.addAndGet(primeAccountDelta)
                } else { getBalance() }
            }
        }
        throw RetriesExhaustedException()
    }

    override fun processInsufficientBalance(toAccount: BankAccount, amount: Int, expectedBalance: Int) : Int {
        val rollback = mutableListOf<() -> Unit>()

        try {
            if (expectedBalance > 0) {
                if (!balance.compareAndSet(expectedBalance, 0)) {
                    return 2
                }
                rollback.add { balance.addAndGet(expectedBalance) }
            }

            val overdraftDelta = amount - expectedBalance
            val overdraftBalance = overdraftAccount.balance.get()

            val result = (overdraftBalance - overdraftDelta)
                .takeIf { it >= overdraftAccount.overdraftLimit }
                ?.let {
                    overdraftAccount.balance.compareAndSet(overdraftBalance, it)
                        .takeIf { it }
                        ?.let {
                            rollback.clear()
                            0   // transfer succeeded
                        } ?: 2  // retry - concurrency issue
                } ?: 1  // insufficient funds

            return result
        } finally {
            rollback.reversed().forEach { it() }
        }
    }
}