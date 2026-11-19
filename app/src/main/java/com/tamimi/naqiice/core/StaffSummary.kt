package com.tamimi.naqiice.core

/** One employee's figures for a period, and his account balance. */
data class StaffSummary(
    val sales: Double,
    val invoices: Int,
    val cashSales: Double,
    val creditSales: Double,
    val collected: Double,
    val campaigns: Int,
    /** Approved closings' shortages in the period, charged or not. */
    val shortages: Double,
    /** What he owes the company: charged shortages less salary deductions and adjustments. */
    val balance: Double,
)

/**
 * [sales] are his invoices (total, cash_paid, credit_amount, trip_id), [payments] his debt
 * collections, [closings] his closings and [ledger] his account entries (positive = owed).
 */
fun staffSummary(sales: List<Row>, payments: List<Row>, closings: List<Row>, ledger: List<Row>): StaffSummary = StaffSummary(
    sales = sales.sumOf { it.num("total") },
    invoices = sales.size,
    cashSales = sales.sumOf { it.num("total") - it.num("credit_amount") },
    creditSales = sales.sumOf { it.num("credit_amount") },
    collected = payments.sumOf { it.num("amount") },
    campaigns = sales.mapNotNull { it.str("trip_id") }.toSet().size,
    shortages = closings.filter { it.str("status") == "approved" && it.num("difference") < 0 }.sumOf { -it.num("difference") },
    balance = ledger.sumOf { it.num("amount") },
)
