package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.database.AppDatabase
import com.example.data.dao.BillDao
import com.example.data.dao.PermissionDao
import com.example.data.dao.UserDao
import com.example.data.model.BillEntity
import com.example.data.model.MeterReadingEntity
import com.example.data.model.ReadingReminderEntity
import com.example.data.model.BillStatus
import com.example.data.model.RolePermissionEntity
import com.example.data.model.PaymentEntity
import com.example.data.model.UserEntity
import kotlinx.coroutines.flow.Flow

class BillingRepository(
    private val db: AppDatabase,
    private val userDao: UserDao,
    private val billDao: BillDao,
    private val permissionDao: PermissionDao,
    private val meterReadingDao: com.example.data.dao.MeterReadingDao,
    private val readingReminderDao: com.example.data.dao.ReadingReminderDao,
    private val paymentDao: com.example.data.dao.PaymentDao
) {
    val allUsers: Flow<List<UserEntity>> = userDao.getAllUsers()
    val allBills: Flow<List<BillEntity>> = billDao.getAllBills()
    val allMeterReadings: Flow<List<MeterReadingEntity>> = meterReadingDao.getAll()
    val activeReadingReminders: Flow<List<ReadingReminderEntity>> = readingReminderDao.getActive()
    val allPayments: Flow<List<PaymentEntity>> = paymentDao.getAll()

    fun getBillsByStatus(status: String): Flow<List<BillEntity>> = billDao.getBillsByStatus(status)

    fun getBillsByUserId(userId: String): Flow<List<BillEntity>> = billDao.getBillsByUserId(userId)

    fun getBillsByAdminId(adminId: String): Flow<List<BillEntity>> = billDao.getBillsByAdminId(adminId)

    fun getUsersByAdminId(adminId: String): Flow<List<UserEntity>> = userDao.getUsersByAdminId(adminId)

    fun getPermissionsByRole(role: String): Flow<List<RolePermissionEntity>> = permissionDao.getPermissionsByRole(role)

    suspend fun getBillById(id: String): BillEntity? = billDao.getBillById(id)
    suspend fun getUserById(id: String): UserEntity? = userDao.getUserById(id)

    suspend fun getBillsForUserOnce(userId: String): List<BillEntity> = billDao.getBillsForUserOnce(userId)

    suspend fun getOutstandingBillsForUser(userId: String): List<BillEntity> =
        billDao.getOutstandingBillsForUser(userId)

    /** آخر قراءة عداد محفوظة للمشترك (تُستخدم كقراءة سابقة تلقائية). */
    suspend fun getLastReadingForUser(userId: String): Double {
        val reading = meterReadingDao.getForUser(userId).maxByOrNull { it.createdAt }?.currentReading
        return reading ?: (billDao.getBillsForUserOnce(userId).maxByOrNull { it.createdAt }?.currentReading ?: 0.0)
    }

    /** إجمالي متأخرات المشترك = مجموع المتبقي من فواتيره غير المسددة. */
    suspend fun getArrearsForUser(userId: String): Double =
        billDao.getOutstandingBillsForUser(userId).sumOf { it.remainingAmount }

    suspend fun insertBill(bill: BillEntity): Unit = billDao.insertBill(bill)
    suspend fun insertMeterReading(reading: MeterReadingEntity) = meterReadingDao.insert(reading)
    suspend fun insertReadingReminder(reminder: ReadingReminderEntity) = readingReminderDao.insert(reminder)
    suspend fun completeReadingReminder(id: String) = readingReminderDao.complete(id)
    suspend fun deleteReadingReminder(id: String) = readingReminderDao.delete(id)
    suspend fun updateBill(bill: BillEntity) = billDao.updateBill(bill)
    suspend fun deleteMeterReading(id: String) = meterReadingDao.deleteById(id)
    suspend fun deleteBill(bill: BillEntity) = billDao.deleteBill(bill)
    suspend fun insertPayment(payment: PaymentEntity) = paymentDao.insert(payment)

    /**
     * يسجل التحصيل وتحديث الفاتورة والرصيد الدائن ورقم الإيصال في معاملة Room واحدة.
     * paymentId ثابت للعملية الواحدة؛ إعادة إرسال المعرف نفسه تعيد الإيصال الموجود ولا تكرر التحصيل.
     */
    suspend fun recordPaymentAtomically(
        billId: String,
        amountPaidNow: Double,
        date: String,
        method: String,
        collectorId: String,
        collectorName: String,
        paymentId: String
    ): Pair<BillEntity, PaymentEntity>? = db.withTransaction {
        require(amountPaidNow > 0.0) { "يجب أن يكون مبلغ التحصيل أكبر من صفر" }

        paymentDao.getById(paymentId)?.let { existing ->
            val existingBill = billDao.getBillById(existing.billId) ?: return@withTransaction null
            return@withTransaction existingBill to existing
        }

        val bill = billDao.getBillById(billId) ?: return@withTransaction null
        val beforeRemaining = bill.remainingAmount.coerceAtLeast(0.0)
        if (beforeRemaining <= 0.0 || bill.status == BillStatus.CARRIED.name) return@withTransaction null

        val appliedToBill = minOf(amountPaidNow, beforeRemaining)
        val excessCredit = (amountPaidNow - appliedToBill).coerceAtLeast(0.0)
        val newPaid = (bill.paidAmount + appliedToBill).coerceAtMost(bill.totalAmount)
        val newRemaining = (bill.totalAmount - newPaid).coerceAtLeast(0.0)
        val now = System.currentTimeMillis()
        val status = when {
            newRemaining <= 0.0 -> BillStatus.PAID.name
            newPaid > 0.0 -> BillStatus.PARTIAL.name
            else -> BillStatus.UNPAID.name
        }

        billDao.updateBillPayment(
            billId, status, newPaid, newRemaining, date, method, collectorName, now
        )

        if (excessCredit > 0.0) {
            val subscriber = userDao.getUserById(bill.userId)
                ?: error("تعذر حفظ الرصيد الدائن: المشترك غير موجود")
            userDao.updateUser(subscriber.copy(creditBalance = subscriber.creditBalance + excessCredit))
        }

        val payment = PaymentEntity(
            id = paymentId,
            adminId = bill.adminId,
            billId = bill.id,
            userId = bill.userId,
            invoiceNumber = bill.invoiceNumber,
            userName = bill.userName,
            amount = amountPaidNow,
            method = method,
            collectorId = collectorId,
            collectorName = collectorName,
            paymentDate = date,
            paymentAt = now,
            beforeRemaining = beforeRemaining,
            afterRemaining = newRemaining,
            receiptNumber = paymentDao.nextReceiptNumber()
        )
        check(paymentDao.insert(payment) != -1L) { "تعذر حفظ إيصال التحصيل؛ تم التراجع عن العملية" }
        val updated = billDao.getBillById(billId) ?: error("تعذر قراءة الفاتورة بعد التحصيل")
        updated to payment
    }
    suspend fun getPaymentsForBill(billId: String): List<PaymentEntity> = paymentDao.getForBill(billId)

    suspend fun markBillCarriedForward(id: String) = billDao.markBillCarriedForward(id)

    /**
     * تسجيل دفعة على فاتورة مع دعم الدفع الجزئي.
     * @return الفاتورة بعد التحديث، أو null إن لم تكن موجودة.
     */
    suspend fun registerPayment(
        billId: String,
        amountPaidNow: Double,
        date: String,
        method: String,
        collectorName: String
    ): BillEntity? {
        val bill = billDao.getBillById(billId) ?: return null
        // لا تسمح بتسجيل دفعة جديدة على فاتورة مسددة/ذات رصيد زائد.
        if (bill.remainingAmount <= 0.0) return null
        // السماح بالدفع الزائد: إذا دفع المشترك أكثر من المستحق، يُحفظ الفرق كرصيد
        // سالب في remainingAmount ليُخصم تلقائياً من الفاتورة القادمة.
        val newPaid = bill.paidAmount + amountPaidNow
        val newRemaining = bill.totalAmount - newPaid
        val paymentAt = System.currentTimeMillis()
        val newStatus = when {
            newRemaining <= 0.0 -> BillStatus.PAID.name
            newPaid > 0.0 -> BillStatus.PARTIAL.name
            else -> BillStatus.UNPAID.name
        }
        billDao.updateBillPayment(billId, newStatus, newPaid, newRemaining, date, method, collectorName, paymentAt)
        return billDao.getBillById(billId)
    }

    suspend fun insertUser(user: UserEntity): Unit = userDao.insertUser(user)
    suspend fun updateUser(user: UserEntity) = userDao.updateUser(user)
    suspend fun deleteUser(user: UserEntity) = userDao.deleteUser(user)
    suspend fun updateUserStatus(id: String, isActive: Boolean) = userDao.updateUserStatus(id, isActive)

    suspend fun updatePermission(id: String, isGranted: Boolean) = permissionDao.updatePermissionStatus(id, isGranted)
}
