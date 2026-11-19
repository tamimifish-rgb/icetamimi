package com.tamimi.naqiice

import com.tamimi.naqiice.core.HandoverMethod
import com.tamimi.naqiice.core.handoverAmounts
import com.tamimi.naqiice.core.handoverError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HandoverTest {
    @Test fun handoverErrorRules() {
        // Cash: no slip needed.
        assertNull(handoverError(9000.0, HandoverMethod.Cash, 0.0, hasSlip = false))
        // Transfer: the whole amount, with a slip.
        assertNull(handoverError(9000.0, HandoverMethod.Transfer, 0.0, hasSlip = true))
        assertEquals("أرفق صورة أو ملف إشعار الإيداع", handoverError(9000.0, HandoverMethod.Transfer, 0.0, hasSlip = false))
        // Both: a transfer amount above zero and below the counted cash, with a slip.
        assertNull(handoverError(9000.0, HandoverMethod.Both, 5000.0, hasSlip = true))
        assertEquals("أرفق صورة أو ملف إشعار الإيداع", handoverError(9000.0, HandoverMethod.Both, 5000.0, hasSlip = false))
        assertEquals("مبلغ الإيداع يجب أن يكون أكبر من صفر وأقل من النقد المعدود", handoverError(9000.0, HandoverMethod.Both, 9000.0, hasSlip = true))
        assertEquals("مبلغ الإيداع يجب أن يكون أكبر من صفر وأقل من النقد المعدود", handoverError(9000.0, HandoverMethod.Both, 0.0, hasSlip = true))
        assertNotNull(handoverError(9000.0, HandoverMethod.Both, Double.NaN, hasSlip = true))
        // Nothing counted: only cash makes sense.
        assertNull(handoverError(0.0, HandoverMethod.Cash, 0.0, hasSlip = false))
        assertEquals("لا يوجد نقد لإيداعه؛ اختر نقد", handoverError(0.0, HandoverMethod.Transfer, 0.0, hasSlip = true))
    }

    @Test fun amountsPerMethod() {
        assertEquals(9000.0 to 0.0, handoverAmounts(9000.0, HandoverMethod.Cash, 1234.0))
        assertEquals(0.0 to 9000.0, handoverAmounts(9000.0, HandoverMethod.Transfer, 1234.0))
        assertEquals(4000.0 to 5000.0, handoverAmounts(9000.0, HandoverMethod.Both, 5000.0))
        // The server checks cash + transfer = counted exactly; the split must not drift.
        val (cash, transfer) = handoverAmounts(0.15, HandoverMethod.Both, 0.01)
        assertEquals(0.14, cash, 0.0)
        assertEquals(java.math.BigDecimal("0.15"), java.math.BigDecimal(cash.toString()) + java.math.BigDecimal(transfer.toString()))
    }
}
