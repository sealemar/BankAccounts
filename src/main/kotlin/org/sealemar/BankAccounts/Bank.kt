package org.sealemar.BankAccounts

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

interface Bank {
    val totalBalance : Int
    val numberOfAccounts : Int
    fun transfer(fromAccount: Int, toAccount: Int, amount: Int)
}

class ConcurrentBank<BankAccountElement : BankAccount>(
    private val accounts: Array<BankAccountElement>,
    private val totalBalanceTimeout: Long = 2 * 1000,
    private val transferTimeout: Long = 2 * 1000
) : Bank {
    private val semTransfer = AtomicInteger(0)
    private val semBalance = AtomicBoolean(false)

    /**
     * totalBalance will always return the bank total balance across all
     * accounts once all transfers have been fully settled.
     *
     * BankAccount.transfer(anotherAccount, amount)
     *
     * is debit first, then credit, so for a short period of time
     * there is less total balance across all accounts then it should be.
     * totalBalance property takes care of non-blocking synchronization.
     */
    override val totalBalance: Int
        get() {
            var locked = false
            return repeatWithTimeout(totalBalanceTimeout, {
                (locked || semBalance.compareAndSet(false, true)).takeIf { it }?.let {
                    locked = true
                    semTransfer.get().takeUnless { it > 0 }?.let {
                        accounts.sumBy { it.getBalance() }
                    }
                }.also {
                    if (it == null) {
                        Thread.yield()
                    }
                }
            }) {
                it == null
            }.also {
                semBalance.set(false)
            } ?: throw RetriesExhaustedException()
        }

    override val numberOfAccounts: Int
        get() = accounts.size

    /**
     * @param fromAccount account id of the payer
     * @param toAccount account id of the payee
     * @param amount
     *
     * This function respects requests for totalBalance. If a request for totalBalance
     * was made, this function will wait until the request has been fulfilled.
     */
    override fun transfer(fromAccount: Int, toAccount: Int, amount: Int) {
        assert(fromAccount != toAccount) { "fromAccount MUST NOT BE equal to toAccount" }
        repeatWithTimeout(transferTimeout, {
            semTransfer.incrementAndGet().let {
                semBalance.get().takeUnless { it }?.let {
                    accounts[fromAccount].transfer(accounts[toAccount], amount)
                    true
                }
            }.also {
                semTransfer.decrementAndGet()
                if(it == null) {
                    Thread.yield()
                }
            }
        }) {
            it == null
        } ?: throw RetriesExhaustedException()
    }
}