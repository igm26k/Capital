package com.capital.accounting.finance

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import com.capital.accounting.api.Category

data class CategoryAppearance(val icon: String, val color: Color)
fun categoryAppearance(id: String?, categories: List<Category>): CategoryAppearance {
    var item = categories.find { it.id == id }
    val name = item?.name.orEmpty()
    val seen = mutableSetOf<String>()
    while (item?.parent_id != null && seen.add(item!!.id)) {
        val parent = categories.find { it.id == item!!.parent_id } ?: break
        item = parent
    }
    var hash = 2166136261L
    item?.id.orEmpty().forEach { hash = ((hash xor it.code.toLong()) * 16777619L) and 0xffffffffL }
    val hue = ((hash % 360000) / 1000.0).toFloat()
    val color = if (id == null) Color(0xff777777) else Color(androidx.core.graphics.ColorUtils.HSLToColor(floatArrayOf(hue, .55f, .4f)))
    val rules = listOf(
        "food" to Regex("продукт|еда|питан|ресторан|кафе|кофе|food|grocer|restaurant|coffee", RegexOption.IGNORE_CASE),
        "transport" to Regex("транспорт|авто|бензин|такси|метро|парков|transport|car|fuel|taxi", RegexOption.IGNORE_CASE),
        "home" to Regex("дом|жиль|квартир|коммун|аренд|home|house|rent|utilit", RegexOption.IGNORE_CASE),
        "health" to Regex("здоров|медиц|аптек|врач|health|medic|pharmacy", RegexOption.IGNORE_CASE),
        "travel" to Regex("путеше|отпуск|билет|отел|travel|hotel|flight", RegexOption.IGNORE_CASE),
        "education" to Regex("образов|учеб|книг|курс|education|book|school", RegexOption.IGNORE_CASE),
        "leisure" to Regex("развлеч|спорт|кино|хобби|досуг|entertain|sport|cinema|hobby", RegexOption.IGNORE_CASE),
        "work" to Regex("работ|зарплат|доход|бизнес|work|salary|income|business", RegexOption.IGNORE_CASE),
        "shop" to Regex("покуп|одежд|обув|техник|shop|cloth|electronic", RegexOption.IGNORE_CASE)
    )
    val icon = rules.find { it.second.containsMatchIn(name) }?.first
        ?: rules.find { it.second.containsMatchIn(item?.name.orEmpty()) }?.first ?: "category"
    return CategoryAppearance(icon, color)
}
@Composable
fun CategoryIcon(id: String?, categories: List<Category>) {
    val appearance = categoryAppearance(id, categories)
    CapitalIcon(appearance.icon, Modifier.size(22.dp), appearance.color)
}
