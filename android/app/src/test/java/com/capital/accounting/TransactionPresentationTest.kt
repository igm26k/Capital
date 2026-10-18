package com.capital.accounting

import com.capital.accounting.finance.capitalMoney
import com.capital.accounting.finance.transactionNote
import com.capital.accounting.finance.transactionReason
import org.junit.Assert.*
import org.junit.Test

class TransactionPresentationTest {
    @Test fun importedProvenanceIsHiddenWithoutDiscardingUserNotes() {
        assertEquals("Кофе\nВстреча", transactionNote("Wallet · строка 68\nКофе\nКатегория Wallet: Food\nВстреча"))
        assertEquals("Обмен", transactionNote("Обмен\nЗачисление Wallet · строка 69 · EUR"))
        assertEquals("Категория Wallet: мой текст", transactionNote("Категория Wallet: мой текст"))
        assertEquals("Покупка Wallet", transactionNote("Покупка Wallet"))
    }
    @Test fun correctionReasonsUseCapitalTerms() {
        assertEquals("Корректировка остатка", transactionReason("Корректировка Wallet: отсутствует источник поступления"))
        assertEquals("Начальный остаток", transactionReason("Начальный остаток Wallet"))
        assertEquals("Переоценка", transactionReason("Переоценка"))
        assertNull(transactionReason(null))
    }
    @Test fun compactAmountsPreserveEveryMinorUnitAndCurrency() {
        assertEquals("499,65 €", capitalMoney("49965", "EUR"))
        assertEquals("−0,01 $", capitalMoney("-1", "USD"))
        assertEquals("2 800,00 ₽", capitalMoney("280000", "RUB"))
        assertEquals("180 000 000 000 000,00 €", capitalMoney("18000000000000000", "EUR"))
    }
}
