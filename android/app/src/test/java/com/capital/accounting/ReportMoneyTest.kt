package com.capital.accounting

import com.capital.accounting.finance.Money
import org.junit.Assert.assertEquals
import org.junit.Test

class ReportMoneyTest {
    @Test fun aggregateKeepsCurrencyScaleAboveIndividualEntryLimit() {
        assertEquals("18000000000000,000 KWD",Money.displayAggregate("18000000000000000","KWD"))
        assertEquals("−0,001 KWD",Money.displayAggregate("-1","KWD"))
        assertEquals("18000000000000000 JPY",Money.displayAggregate("18000000000000000","JPY"))
    }
}
