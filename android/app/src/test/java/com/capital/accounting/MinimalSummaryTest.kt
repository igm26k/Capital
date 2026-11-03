package com.capital.accounting

import com.capital.accounting.api.*
import com.capital.accounting.finance.operationTotals
import com.capital.accounting.finance.summaryOperations
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger

class MinimalSummaryTest {
    private val account = "00000000-0000-4000-8000-000000000001"
    private fun item(kind: String, currency: String, amount: String) = Transaction(
        id = kind, workspace_id = "workspace", version = "1", created_at = "", updated_at = "", deleted_at = null,
        kind = kind, status = "posted", occurred_at = "2026-11-03T09:00:00Z", occurred_timezone = "UTC", note = "", payee = "Кофе", reason = null,
        parent_transaction_id = null, fee_transaction_id = null, rate = null,
        entries = listOf(Entry("entry", account, currency, amount)), allocations = emptyList(), tag_ids = emptyList())
    @Test fun multiCurrencyTransferDoesNotInflateIncomeAndExpenseAndAmountsStayExact() {
        val expense = item("expense", "KWD", "-12345")
        val income = item("income", "KWD", "9000000000000000")
        val transfer = item("transfer", "KWD", "-50000").copy(entries = listOf(Entry("a", account, "KWD", "-50000"), Entry("b", "other", "USD", "16000")))
        val totals = operationTotals(listOf(expense, income, transfer, item("refund", "JPY", "20")))
        assertEquals(BigInteger("9000000000000000") to BigInteger("12345"), totals["KWD"])
        assertEquals(BigInteger.ZERO to BigInteger("-20"), totals["JPY"])
        assertFalse(totals.containsKey("USD"))
    }
    @Test fun filtersIncludePendingAndUseExclusiveEndWithFractionalSeconds() {
        val pending = item("income", "EUR", "100").copy(status = "pending", occurred_at = "2026-11-03T09:00:00.001Z")
        val expense = item("expense", "EUR", "-50")
        val filter = TransactionFilter(accountId = account, from = "2026-11-03T09:00:00Z", to = "2026-11-03T09:00:00.001Z", search = "КОФЕ")
        assertEquals(listOf(expense), summaryOperations(listOf(pending, expense), filter))
        assertEquals(2, summaryOperations(listOf(pending, expense), filter.copy(to = null)).size)
        assertEquals(listOf(pending), summaryOperations(listOf(pending, expense), filter.copy(to = null, status = "pending")))
    }
}
