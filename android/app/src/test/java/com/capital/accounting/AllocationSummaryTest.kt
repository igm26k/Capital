package com.capital.accounting

import com.capital.accounting.finance.*
import org.junit.Assert.*
import org.junit.Test

class AllocationSummaryTest {
    private fun parts(vararg amounts: String) = amounts.mapIndexed { index, amount -> AllocationDraft("part$index", null, amount) }
    @Test fun exactRemainingUnderAndOverAllocationWithoutRounding() {
        assertEquals(AllocationSummary("2,000 KWD", "1,001 KWD"), allocationSummary("3,001", "KWD", parts("1", "1")))
        assertEquals(AllocationSummary("3,001 KWD", "0,000 KWD"), allocationSummary("3,001", "KWD", parts("1", "2,001")))
        assertEquals(AllocationSummary("4,001 KWD", "−1,000 KWD"), allocationSummary("3,001", "KWD", parts("2", "2,001")))
    }
    @Test fun invalidAmountsDoNotProducePreviewAndLargeSumsRemainExact() {
        for (raw in listOf("", "bad", "0", "-1", "1,1", "9000000000000001")) assertNull(allocationSummary("1", "JPY", parts(raw, "1")))
        assertEquals(AllocationSummary("18000000000000000 JPY", "−17999999999999999 JPY"), allocationSummary("1", "JPY", parts("9000000000000000", "9000000000000000")))
    }
}
