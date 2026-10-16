package com.capital.accounting.finance

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.capital.accounting.api.*
import com.capital.accounting.auth.AuthViewModel
import kotlinx.serialization.encodeToString
import org.json.JSONObject
import java.util.Calendar
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

@Composable
fun AnalyticsPanel(model: AuthViewModel) {
    val section = LocalFinancialSection.current
    if (section.value != "more") return
    val state = model.state; val auth = state.auth ?: return
    var expanded by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) { Text("Бюджеты, отчеты и правила") }
    if (!expanded) return
    val analytics: AnalyticsModel = viewModel()
    LaunchedEffect(analytics.unauthorized) { if (analytics.unauthorized) model.sessionUnauthorized(auth.session.id) }
    val session = auth.credential(state.origin)
    var from by rememberSaveable { mutableStateOf("${analyticsMonth(0)}-01T00:00:00Z") }
    var to by rememberSaveable { mutableStateOf("${analyticsMonth(1)}-01T00:00:00Z") }
    var account by rememberSaveable { mutableStateOf<String?>(null) }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var tag by rememberSaveable { mutableStateOf<String?>(null) }
    var status by rememberSaveable { mutableStateOf("all") }
    val filter = listOf("account_id" to account,"category_id" to category,"tag_id" to tag,"status" to status).filter { it.second != null }.joinToString("") { "&${it.first}=${it.second}" }
    LaunchedEffect(state.origin,auth.profile.id,auth.workspace.id,auth.session.id,auth.workspace.sync_generation_id) { analytics.bind(session);if(!state.offline)analytics.load(from,to,filter) }
    DisposableEffect(state.origin,auth.profile.id,auth.workspace.id,auth.session.id,auth.workspace.sync_generation_id) { onDispose { analytics.clear() } }
    val enabled = !analytics.busy && !state.busy && !state.offline && state.persisted
    val mutable = enabled && analytics.pending == null
    Text("Предварительные операции включены; валюты раздельно. Период [от, до), UTC. Для загрузки и изменения настроек нужно соединение.")
    if(analytics.message.isNotEmpty())Text(analytics.message)
    if(analytics.busy)CircularProgressIndicator()
    OutlinedTextField(from,{from=it},label={Text("Отчет от (UTC)")},enabled=enabled)
    OutlinedTextField(to,{to=it},label={Text("Отчет до (UTC)")},enabled=enabled)
    TextButton(enabled=enabled,onClick={account=null;category=null;tag=null}){Text("Отчет: все счета, категории и теги")}
    state.accounts.forEach { a -> TextButton(enabled=enabled,onClick={account=a.id}){Text("Счет отчета: ${a.name}${if(account==a.id) " ✓" else ""}")} }
    state.categories.forEach { c -> TextButton(enabled=enabled,onClick={category=c.id}){Text("Категория отчета: ${c.name}${if(category==c.id) " ✓" else ""}")} }
    state.tags.forEach { t -> TextButton(enabled=enabled,onClick={tag=t.id}){Text("Тег отчета: ${t.name}${if(tag==t.id) " ✓" else ""}")} }
    for((value,title) in listOf("all" to "Все операции","posted" to "Подтвержденные","pending" to "Предварительные"))TextButton(enabled=enabled,onClick={status=value}){Text("Статус отчета: $title${if(status==value) " ✓" else ""}")}
    Button(enabled=enabled,onClick={analytics.load(from,to,filter)}){Text("Построить отчет")}
    analytics.pending?.let { p ->
        Text(if(p.rejected)"Запрос настроек отклонён. Обновите данные перед новым изменением." else "Сохранён запрос настроек. Повтор использует исходные данные.")
        if(p.rejected)TextButton(enabled=enabled,onClick={analytics.dismiss(from,to,filter)}){Text("Закрыть отклонённое изменение настроек")}
        else Button(enabled=enabled,onClick={analytics.retry(from,to,filter)}){Text("Повторить запрос настроек")}
    }
    analytics.report?.let { r ->
        Text("Доходы и расходы",style=MaterialTheme.typography.titleLarge)
        if(r.currencies.isEmpty())Text("Доходов и расходов за период нет.")
        r.currencies.forEach { t -> Text("${t.currency}: доход ${Money.displayAggregate(t.income_minor,t.currency)}, чистый расход ${Money.displayAggregate(t.expense_minor,t.currency)}");Text("Предварительные: доход ${Money.displayAggregate(t.pending_income_minor,t.currency)}, расход ${Money.displayAggregate(t.pending_expense_minor,t.currency)}") }
        Text("Расходы по категориям")
        r.categories.forEach { c ->
            Text("${state.categories.find{it.id==c.category_id}?.name?:"Без категории"}: ${Money.displayAggregate(c.expense_minor,c.currency)}")
            val total=r.categories.filter{it.currency==c.currency}.fold(java.math.BigInteger.ZERO){sum,v->sum+java.math.BigInteger(v.expense_minor).abs()}
            val progress=if(total.signum()==0)0f else java.math.BigInteger(c.expense_minor).abs().multiply(java.math.BigInteger.valueOf(1000)).divide(total).toFloat()/1000f
            LinearProgressIndicator(progress={progress})
        }
        Text("Баланс учитывает все движения счета. Фильтры категории и тега относятся только к доходам/расходам.")
        r.balances.forEach { b -> Text("${b.currency}: на начало ${Money.displayAggregate(b.opening_minor,b.currency)}, подтвержденный ${Money.displayAggregate(b.closing_minor,b.currency)}, с предварительными ${Money.displayAggregate(b.projected_closing_minor,b.currency)}") }
        var daily by remember{mutableStateOf(false)}
        TextButton(onClick={daily=!daily}){Text("Динамика остатков UTC")}
        if(daily)r.daily_balances.forEach{b->Text("${b.date}: ${Money.displayAggregate(b.closing_minor,b.currency)}; с предварительными ${Money.displayAggregate(b.projected_closing_minor,b.currency)}")}
        Button(enabled=enabled,onClick={runCatching{model.applyHistoryFilter(TransactionFilter(accountId=account,categoryId=category,tagId=tag,from=from,to=to,status=status.takeUnless{it=="all"}));section.value="transactions"}}){Text("Исходные операции отчета")}
    }
    var editor by remember{mutableStateOf("")};var selectedBudget by remember{mutableStateOf<Budget?>(null)};var selectedRule by remember{mutableStateOf<Rule?>(null)}
    var deletion by remember{mutableStateOf<Pair<String,String>?>(null)}
    Text("Месячные бюджеты",style=MaterialTheme.typography.titleLarge)
    Text("Включены дочерние категории и предварительные расходы. Пороги: 80% и 100%.")
    analytics.budgets.forEach{v->Text("${v.budget.name} · ${v.budget.month} · ${v.budget.timezone}");Text("Потрачено ${Money.displayAggregate(v.spent_minor,v.budget.currency)} из ${Money.displayAggregate(v.budget.limit_minor,v.budget.currency)}; осталось ${Money.displayAggregate(v.remaining_minor,v.budget.currency)}");Text("Предварительные ${Money.displayAggregate(v.pending_spent_minor,v.budget.currency)} · ${when(v.warning){"exceeded"->"Лимит достигнут или превышен";"approaching"->"Близко к лимиту";else->"В пределах лимита"}}")
        TextButton(enabled=mutable,onClick={selectedBudget=v.budget;editor="budget"}){Text("Изменить бюджет ${v.budget.name}")};TextButton(enabled=mutable,onClick={deletion="budgets/${v.budget.id}" to v.budget.version}){Text("Удалить бюджет ${v.budget.name}")}}
    Button(enabled=mutable,onClick={selectedBudget=null;editor="budget"}){Text("Новый бюджет")}
    Text("Правила категоризации",style=MaterialTheme.typography.titleLarge)
    analytics.rules.sortedWith(compareBy<Rule>{it.priority}.thenBy{it.id}).forEach{r->Text("${r.name} · ${r.priority} · ${if(r.enabled)"Включено" else "Выключено"}");TextButton(enabled=mutable,onClick={selectedRule=r;editor="rule"}){Text("Изменить правило ${r.name}")};TextButton(enabled=mutable,onClick={deletion="rules/${r.id}" to r.version}){Text("Удалить правило ${r.name}")}}
    Button(enabled=mutable,onClick={selectedRule=null;editor="rule"}){Text("Новое правило")}
    if(editor.isNotEmpty())key(editor,selectedBudget?.id,selectedRule?.id){PlanningEditor(editor,selectedBudget,selectedRule,state.categories,mutable,{editor=""}){path,method,body->analytics.save(path,method,body,from,to,filter);editor=""}}
    deletion?.let{(path,version)->AlertDialog(onDismissRequest={deletion=null},title={Text("Удалить настройку?")},confirmButton={TextButton(enabled=mutable,onClick={deletion=null;analytics.save(path,"DELETE",ApiClient.json.encodeToString(PlanningDelete(version)),from,to,filter)}){Text("Удалить настройку")}},dismissButton={TextButton(onClick={deletion=null}){Text("Отмена")}})}
    Button(enabled=mutable,onClick={analytics.preview(from,to)}){Text("Предпросмотр правил за период")}
    var batch by remember{mutableStateOf<BulkClassification?>(null)}
    analytics.preview?.let{p->if(p.has_more)Text("Первые 100 совпадений; после применения обновите предпросмотр.");if(p.batches.isEmpty())Text("Совпадений для неклассифицированных операций нет.");p.batches.forEachIndexed{i,b->TextButton(enabled=mutable&&!state.financeBlocked,onClick={batch=b}){Text("Проверить пакет ${i+1}: операций ${b.items.size}")}}}
    batch?.let{b->AlertDialog(onDismissRequest={batch=null},title={Text("Изменить категорию у ${b.items.size} операций?")},text={Text("Теги сохранятся. Изменение версии отклонит весь пакет.")},confirmButton={TextButton(enabled=mutable&&!state.financeBlocked,onClick={batch=null;model.submitClassification(ApiClient.json.encodeToString(b))}){Text("Подтвердить применение правил")}},dismissButton={TextButton(onClick={batch=null}){Text("Отмена")}})}
}

@Composable
private fun PlanningEditor(kind:String,budget:Budget?,rule:Rule?,categories:List<Category>,enabled:Boolean,close:()->Unit,save:(String,String,String)->Unit){
    var name by rememberSaveable{mutableStateOf(budget?.name?:rule?.name?:"")};var category by rememberSaveable{mutableStateOf(budget?.category_id?:rule?.category_id?:categories.firstOrNull{it.archived_at==null}?.id?:"")}
    var currency by rememberSaveable{mutableStateOf(budget?.currency?:"EUR")};var month by rememberSaveable{mutableStateOf(budget?.month?:analyticsMonth(0))};var zone by rememberSaveable{mutableStateOf(budget?.timezone?:TimeZone.getDefault().id)}
    var limit by rememberSaveable{mutableStateOf(budget?.let{Money.displayAggregate(it.limit_minor,it.currency).substringBefore(' ').replace('−','-')}?:"")}
    var priority by rememberSaveable{mutableStateOf(rule?.priority?.toString()?:"100")};var txKind by rememberSaveable{mutableStateOf(rule?.transaction_kind?:"expense")};var payee by rememberSaveable{mutableStateOf(rule?.payee_contains?:"")};var note by rememberSaveable{mutableStateOf(rule?.note_contains?:"")};var active by rememberSaveable{mutableStateOf(rule?.enabled?:true)};var error by remember{mutableStateOf("")}
    Text(if(kind=="budget")"Редактор бюджета" else "Редактор правила");if(error.isNotEmpty())Text(error)
    OutlinedTextField(name,{name=it},label={Text("Название настройки")},enabled=enabled)
    categories.filter{it.archived_at==null}.forEach{c->TextButton(enabled=enabled,onClick={category=c.id}){Text("Категория настройки: ${c.name}${if(category==c.id)" ✓" else ""}")}}
    if(kind=="budget"){
        Money.scales.keys.forEach{c->TextButton(enabled=enabled,onClick={currency=c}){Text("Валюта бюджета: $c${if(currency==c)" ✓" else ""}")}}
        OutlinedTextField(month,{month=it},label={Text("Месяц бюджета YYYY-MM")},enabled=enabled);OutlinedTextField(zone,{zone=it},label={Text("Часовой пояс бюджета")},enabled=enabled);OutlinedTextField(limit,{limit=it},label={Text("Лимит бюджета")},enabled=enabled)
    }else{OutlinedTextField(priority,{priority=it},label={Text("Приоритет правила")},enabled=enabled);TextButton(enabled=enabled,onClick={txKind=if(txKind=="expense")"income" else "expense"}){Text("Тип правила: $txKind")};OutlinedTextField(payee,{payee=it},label={Text("Получатель содержит")},enabled=enabled);OutlinedTextField(note,{note=it},label={Text("Примечание содержит")},enabled=enabled);TextButton(enabled=enabled,onClick={active=!active}){Text(if(active)"Правило включено" else "Правило выключено")}}
    Button(enabled=enabled,onClick={runCatching{
        require(name.isNotBlank()&&category.isNotBlank());val data=JSONObject().put("name",name).put("category_id",category)
        if(kind=="budget"){val amount=Money.minor(limit,currency);require(java.math.BigInteger(amount).signum()>0);data.put("currency",currency).put("month",month).put("timezone",zone).put("limit_minor",amount)}else{require(payee.isNotBlank()||note.isNotBlank());data.put("priority",priority.toInt()).put("enabled",active).put("transaction_kind",txKind).put("payee_contains",payee).put("note_contains",note)}
        val id=if(kind=="budget")budget?.id else rule?.id;val version=if(kind=="budget")budget?.version else rule?.version
        if(id==null)data.put("id",UUID.randomUUID().toString())else data.put("expected_version",version)
        save((if(kind=="budget")"budgets" else "rules")+(id?.let{"/$it"}?:""),if(id==null)"POST" else "PUT",data.toString())
    }.onFailure{error="Проверьте название, категорию, сумму и условия."}}){Text("Сохранить настройку")}
    TextButton(onClick=close){Text("Отмена настройки")}
}

private fun analyticsMonth(offset: Int): String {
    val date = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); add(Calendar.MONTH, offset) }
    return SimpleDateFormat("yyyy-MM", Locale.ROOT).format(date.time)
}
