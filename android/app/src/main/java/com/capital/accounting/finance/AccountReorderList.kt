package com.capital.accounting.finance

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.capital.accounting.api.Account
import kotlinx.coroutines.isActive
import kotlin.math.abs

val LocalFinancialScrollState = staticCompositionLocalOf<ScrollState?> { null }

@Composable
fun AccountReorderList(accounts: List<Account>, enabled: Boolean, move: (String, Int) -> Unit, edit: (Account) -> Unit, editEnabled: Boolean = enabled) {
    val currentAccounts by rememberUpdatedState(accounts)
    val currentMove by rememberUpdatedState(move)
    val positions = remember { mutableStateMapOf<String, Rect>() }
    var dragging by remember { mutableStateOf<String?>(null) }
    var origin by remember { mutableFloatStateOf(0f) }
    var offset by remember { mutableFloatStateOf(0f) }
    var target by remember { mutableIntStateOf(-1) }
    val scroll = LocalFinancialScrollState.current
    val view = LocalView.current
    val edge = with(LocalDensity.current) { 120.dp.toPx() }
    val maxSpeed = with(LocalDensity.current) { 12.dp.toPx() }
    fun updateTarget() {
        val center = origin + offset
        target = currentAccounts.indices.minByOrNull { index ->
            positions[currentAccounts[index].id]?.let { abs(it.center.y - center) } ?: Float.MAX_VALUE
        } ?: -1
    }
    fun finish(cancel: Boolean = false) {
        val id = dragging
        val destination = target
        dragging = null; offset = 0f; target = -1
        if (!cancel && id != null && destination in currentAccounts.indices && currentAccounts.indexOfFirst { it.id == id } != destination) {
            currentMove(id, destination)
        }
    }
    LaunchedEffect(enabled, accounts.map { it.id }) {
        if (!enabled || (dragging != null && accounts.none { it.id == dragging })) finish(cancel = true)
    }
    LaunchedEffect(dragging) {
        if (dragging != null) while (isActive) {
            withFrameNanos { }
            val center = origin + offset
            val speed = when {
                center < edge -> -((edge - center) / 6).coerceAtMost(maxSpeed)
                center > view.height - edge -> ((center - view.height + edge) / 6).coerceAtMost(maxSpeed)
                else -> 0f
            }
            if (speed != 0f) scroll?.scrollBy(speed)
            updateTarget()
        }
    }
    Text("Перетащите счет за значок ⋮⋮, затем сохраните порядок.", style = MaterialTheme.typography.bodySmall)
    accounts.forEachIndexed { index, account ->
        key(account.id) {
            val active = dragging == account.id
            val dropTarget = dragging != null && target == index && !active
            Box(Modifier.fillMaxWidth().zIndex(if (active) 1f else 0f).onGloballyPositioned { coordinates ->
                val point = coordinates.positionInRoot()
                val bounds = Rect(point.x, point.y, point.x + coordinates.size.width, point.y + coordinates.size.height)
                if (positions[account.id] != bounds) positions[account.id] = bounds
            }) {
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth().graphicsLayer {
                        translationY = if (active) origin + offset - (positions[account.id]?.center?.y ?: origin) else 0f
                    },
                    colors = CardDefaults.outlinedCardColors(containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.outlinedCardElevation(defaultElevation = if (active) 8.dp else 0.dp),
                ) {
                    Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(account.name, fontWeight = FontWeight.SemiBold)
                            Text(Money.display(account.posted_balance_minor, account.currency), style = MaterialTheme.typography.bodySmall)
                            if (account.archived_at != null) Text("В архиве", style = MaterialTheme.typography.bodySmall)
                        }
                        Box(
                            Modifier.size(48.dp).semantics {
                                contentDescription = "Перетащить счет ${account.name}"
                                customActions = listOfNotNull(
                                    if (enabled && index > 0) CustomAccessibilityAction("Поднять счет") { currentMove(account.id, index - 1); true } else null,
                                    if (enabled && index < accounts.lastIndex) CustomAccessibilityAction("Опустить счет") { currentMove(account.id, index + 1); true } else null,
                                )
                            }.pointerInput(account.id, enabled) {
                                if (enabled) detectDragGestures(
                                    onDragStart = {
                                        positions[account.id]?.let { bounds ->
                                            dragging = account.id; origin = bounds.center.y; offset = 0f; target = index
                                        }
                                    },
                                    onDragEnd = { finish() },
                                    onDragCancel = { finish(cancel = true) },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        if (dragging == account.id) { offset += amount.y; updateTarget() }
                                    },
                                )
                            }, contentAlignment = Alignment.Center,
                        ) { CapitalIcon("drag", color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)) }
                        IconButton(enabled = editEnabled, onClick = { edit(account) }, modifier = Modifier.semantics { contentDescription = "Изменить счет ${account.name}" }) { CapitalIcon("settings") }
                    }
                }
                if (dropTarget) {
                    val from = accounts.indexOfFirst { it.id == dragging }
                    HorizontalDivider(Modifier.align(if (index < from) Alignment.TopCenter else Alignment.BottomCenter), thickness = 3.dp, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
