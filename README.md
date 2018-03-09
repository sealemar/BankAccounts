# Bank Accounts exercise

## Problem Statement

1. Thread-safe bank-transfer: Bob pays Alice on one thread. Alice pays Bob on another thread. It's ok to let balances go below zero. Avoid deadlock.

2. Now, block payer if payer's balance *would* go below zero and wait on a "condition variable." Avoid deadlock and starvation (payer blocked forever even if other people pay him enough to get unblocked).

3. Now, add in "overdraw." If payer's main account would go below zero, then (with thread-safety), pull funds from a private back-up account that is allowed to go negative up to a certain limit. Negative balance means payer now has scheduled periodic (e.g. monthly) obligation to pay from regular account into private back-up ("overdraft") account. If main account is zero and overdraft account is at max negative, all payments to payer go to overdraft until it's full. Avoid deadlock and starvation.

4. Implement synchronization mechanism to ensure that the total balance across all account is thread-safe and always correct.

## Solution

### Approach

All balances and amounts are expressed in integers for the following reasons:

- Floating point numbers are more expensive in terms of storage and computation
- Floating point arithmetic is only correct to a certain precision
- Integers are easy to use as fixed point decimals, which money is anyway

Transfer is debit first, then credit, to protect from situations when more than one thread operates on the same account. If the payee is credited before the payer has been debited, then for a short period of time there is more money between the two accounts, then there should be, so another thread may initiate another transaction on the payer. However if transfer is debit first, then for a short period of time there is less money between the two accounts, but that is OK, because another thread will not be able to transfer more money from the payer than it should have, but if it tries to transfer money from the payee, before the payee has been credited, then a future payment is created, that will be immediately resolved, once the credit has been completed on the first thread, because every credit calls a function to resolve future payments.

### First Problem

The first problem is fairly straight forward and does not require special techniques other than making sure that `account.balance` operations are atomic. Since mutexes and thread blocking primitives are generally quite expensive, [java.util.concurrent.atomic.AtomicInteger](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/atomic/AtomicInteger.html) was chosen for `account.balance`.

### Second Problem

The second problem is solved by adding a [java.util.concurrent.ConcurrentLinkedQueue](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ConcurrentLinkedQueue.html) to keep track of future payments. Example:

> When A pays B $5, but has only $2, instead of blocking A, a future payment is created on A to fulfill the obligation once A has sufficient funds. Future payments are processed in order, such that if A owns B $5 and A owns C $2, and A has a balance of `$2 <= x < $5`, A does not pay C, because A has to pay B first.

Each time a future payment succeeds, the payee is tried to process his future payments, such that if A has a future payment for B and that payment has been processed, then B tries to resolve his future payments. It might so happen that A has a future payment for B and B has a future payment for A. Future payments are fully resolved for the calling account first before processing payees.

### Third Problem

Since an overdraft account is used when there is not enough funds on the primary account, and since no thread-blocking primitives are used, the implementation has a rollback mechanism, which is executed, when the primary account and the overdraft account of a payee need to be debited in one transaction. Example:

> A owns B $5, but has only $2 on the primary account, so A is debited with $2, but he has not enough room on the overdraft account, or overdraft account debit fails due to concurrency issuse _(overdraftAccount.balance.compareAndSet)_, then the transaction is rolled back and A is credited with $2.

### Fourth Problem

Implementation of Bank interface `[ConcurrentBank]` has `totalBalance` property, which will always return the bank total balance across all accounts once all transfers have been fully settled.

```
BankAccount.transfer(anotherAccount, amount)
```

is debit first, then credit, so for a short period of time there is less total balance across all accounts then it should be. `totalBalance` property takes care of non-blocking synchronization.

## Set up

This repository is an IntelliJ Kotlin project.

- Download [IntelliJ](https://www.jetbrains.com/idea/download) _(Community Edition is OK)_.
- Install [JDK 8](http://www.oracle.com/technetwork/java/javase/downloads/jdk8-downloads-2133151.html). _Note: Kotlin uses `[jvm 1.8], so later versions of JDK might not work`_
- Import project
- Run `Gradle->Verification->test` target. _Note: Gradle tab is usually located on the right of the IntelliJ window_
