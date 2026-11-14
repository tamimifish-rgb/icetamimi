package com.tamimi.naqiice.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Pure rules for invoice cancel / replace requests, ported from the web app's
// src/lib/invoice-change.ts and covered by InvoiceChangeTest. The server
// (pureice_request_invoice_change / pureice_review_invoice_change) is authoritative;
// these checks only stop obviously invalid requests before they are sent.

val CHANGE_KIND_LABEL = mapOf("cancel" to "طلب إلغاء", "replace" to "طلب تعديل")
val CHANGE_STATUS_LABEL = mapOf("pending" to "بانتظار الاعتماد", "approved" to "معتمد", "rejected" to "مرفوض")

/** Truck stock the replacement may use = what is left + what the old invoice gives back. */
fun availableForReplacement(remaining: Double, oldItems: List<Row>, variantId: String): Double =
    remaining + oldItems.filter { it.str("variant_id") == variantId }.sumOf { it.num("qty") }

fun validateReason(reason: String): String {
    val text = reason.trim()
    if (text.isEmpty()) throw IllegalStateException("اكتب سبب الطلب")
    if (text.length > 500) throw IllegalStateException("السبب طويل؛ اختصره إلى ٥٠٠ حرف")
    return text
}

/**
 * [debt] is the customer's current debt, which still includes the old invoice's credit,
 * so the replacement is judged against the state without the old invoice.
 */
fun validateReplacement(
    lines: List<SaleLine>, cash: Double, available: (String) -> Double, debt: Double, oldCredit: Double, creditLimit: Double,
): SaleTotals {
    if (lines.isEmpty()) throw IllegalStateException("أضف صنفاً واحداً على الأقل")
    if (lines.any { !it.qty.isFinite() || it.qty <= 0 || !it.unitPrice.isFinite() || it.unitPrice <= 0 }) throw IllegalStateException("الكمية أو السعر غير صحيح")
    if (lines.any { it.qty > available(it.variantId) }) throw IllegalStateException("المخزون غير كاف للكمية الجديدة")
    val total = lines.sumOf { lineTotal(it) }
    if (!cash.isFinite() || cash < 0 || cash > total) throw IllegalStateException("النقد يجب أن يكون بين صفر والإجمالي")
    val totals = saleTotals(lines, cash)
    val debtWithoutOld = debt - oldCredit
    if (exceedsCreditLimit(creditLimit, debtWithoutOld, totals.creditAmount)) throw IllegalStateException("تجاوز حد الائتمان")
    if (debtWithoutOld + totals.creditAmount < 0) throw IllegalStateException("تم تحصيل جزء من دين هذه الفاتورة؛ لا يمكن تخفيضها إلى أقل من المحصّل")
    return totals
}

/** The corrected invoice exactly as the server will store it on approval. */
fun replacementPayload(id: String, invoiceNo: String, customerId: String, lines: List<SaleLine>, cash: Double, itemId: () -> String): JsonObject {
    val totals = saleTotals(lines, cash)
    return buildJsonObject {
        put("id", id); put("invoice_no", invoiceNo); put("customer_id", customerId)
        put("total", jnum(totals.total)); put("cash_paid", jnum(totals.cashPaid)); put("credit_amount", jnum(totals.creditAmount))
        put("items", JsonArray(lines.map { l ->
            buildJsonObject {
                put("id", itemId()); put("sale_id", id); put("variant_id", l.variantId)
                put("qty", jnum(l.qty)); put("unit_price", jnum(l.unitPrice)); put("line_total", jnum(lineTotal(l)))
            }
        }))
    }
}

/** Items of a stored invoice snapshot or replacement, whichever key it uses. */
fun changeItems(doc: Row?): List<Row> = when {
    doc == null -> emptyList()
    doc["items"].orNull() != null -> doc.rows("items")
    else -> doc.rows("sale_items")
}

/** Cash actually handed to the driver on an invoice snapshot (stored cash_paid includes any deposit). */
fun snapshotCash(snapshot: Row?): Double = (snapshot?.num("cash_paid") ?: 0.0) - (snapshot?.obj("deposit")?.num("amount") ?: 0.0)
