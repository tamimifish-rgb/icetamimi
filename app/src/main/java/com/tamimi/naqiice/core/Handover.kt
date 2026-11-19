package com.tamimi.naqiice.core

/** How the distributor hands the counted cash to the company at closing. */
enum class HandoverMethod { Cash, Transfer, Both }

/**
 * (cash, transfer) for [counted] handed over by [method]. With [HandoverMethod.Both] the
 * distributor enters only the transfer; the rest is cash, so the two always add up.
 */
fun handoverAmounts(counted: Double, method: HandoverMethod, transfer: Double): Pair<Double, Double> = when (method) {
    HandoverMethod.Cash -> counted to 0.0
    HandoverMethod.Transfer -> 0.0 to counted
    HandoverMethod.Both -> (counted - transfer) to transfer
}

/** Null when the handover can be sent, otherwise the message to show. */
fun handoverError(counted: Double, method: HandoverMethod, transfer: Double, hasSlip: Boolean): String? {
    if (method == HandoverMethod.Cash) return null
    if (counted <= 0.0) return "لا يوجد نقد لإيداعه؛ اختر نقد"
    if (method == HandoverMethod.Both && !(transfer > 0.0 && transfer < counted)) return "مبلغ الإيداع يجب أن يكون أكبر من صفر وأقل من النقد المعدود"
    if (!hasSlip) return "أرفق صورة أو ملف إشعار الإيداع"
    return null
}
