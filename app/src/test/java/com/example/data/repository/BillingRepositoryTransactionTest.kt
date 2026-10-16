package com.example.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.example.data.database.AppDatabase
import com.example.data.model.BillEntity
import com.example.data.model.PaymentEntity
import com.example.data.model.UserEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BillingRepositoryTransactionTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: BillingRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = BillingRepository(
            db, db.userDao(), db.billDao(), db.permissionDao(),
            db.meterReadingDao(), db.readingReminderDao(), db.paymentDao()
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedBill(total: Double = 1000.0): BillEntity {
        db.userDao().insertUser(UserEntity(id = "subscriber-1", name = "مشترك", creditBalance = 0.0))
        val bill = BillEntity(
            id = "bill-1", invoiceNumber = "#1", userId = "subscriber-1",
            userName = "مشترك", totalAmount = total, remainingAmount = total
        )
        db.billDao().insertBill(bill)
        return bill
    }

    @Test
    fun partialPaymentsCreateDistinctReceiptsAndSettleExactly() = runBlocking {
        seedBill()
        val amounts = listOf(300.0, 200.0, 500.0)
        amounts.forEachIndexed { i, amount ->
            assertNotNull(repository.recordPaymentAtomically(
                "bill-1", amount, "2026-10-16", "نقدي", "admin", "الإدارة", "payment-$i"
            ))
        }
        val bill = db.billDao().getBillById("bill-1")!!
        val receipts = db.paymentDao().getForBill("bill-1")
        assertEquals(1000.0, bill.paidAmount, 0.001)
        assertEquals(0.0, bill.remainingAmount, 0.001)
        assertEquals(3, receipts.size)
        assertEquals(3, receipts.map { it.receiptNumber }.distinct().size)
        assertEquals(listOf(1L, 2L, 3L), receipts.map { it.receiptNumber })
    }

    @Test
    fun overpaymentBecomesSubscriberCreditAndIsNotStoredAsNegativeBillBalance() = runBlocking {
        seedBill()
        repository.recordPaymentAtomically(
            "bill-1", 1200.0, "2026-10-16", "نقدي", "admin", "الإدارة", "overpay-1"
        )
        val bill = db.billDao().getBillById("bill-1")!!
        val subscriber = db.userDao().getUserById("subscriber-1")!!
        val receipt = db.paymentDao().getById("overpay-1")!!
        assertEquals(0.0, bill.remainingAmount, 0.001)
        assertEquals(1000.0, bill.paidAmount, 0.001)
        assertEquals(200.0, subscriber.creditBalance, 0.001)
        assertEquals(1200.0, receipt.amount, 0.001)
        assertEquals(0.0, receipt.afterRemaining, 0.001)
    }

    @Test
    fun retryWithSamePaymentIdDoesNotDuplicateCollection() = runBlocking {
        seedBill()
        val first = repository.recordPaymentAtomically(
            "bill-1", 300.0, "2026-10-16", "نقدي", "admin", "الإدارة", "retry-safe-id"
        )!!
        val retry = repository.recordPaymentAtomically(
            "bill-1", 300.0, "2026-10-16", "نقدي", "admin", "الإدارة", "retry-safe-id"
        )!!
        assertEquals(first.second.id, retry.second.id)
        assertEquals(1, db.paymentDao().getForBill("bill-1").size)
        assertEquals(300.0, db.billDao().getBillById("bill-1")!!.paidAmount, 0.001)
    }

    @Test
    fun settledBillRejectsAdditionalPayment() = runBlocking {
        seedBill()
        repository.recordPaymentAtomically(
            "bill-1", 1000.0, "2026-10-16", "نقدي", "admin", "الإدارة", "full-pay"
        )
        val second = repository.recordPaymentAtomically(
            "bill-1", 1.0, "2026-10-16", "نقدي", "admin", "الإدارة", "too-late"
        )
        assertNull(second)
        assertEquals(1, db.paymentDao().getForBill("bill-1").size)
    }
}
