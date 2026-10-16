package com.capital.accounting.imports

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import com.capital.accounting.CapitalApplication
import com.capital.accounting.api.credential
import com.capital.accounting.api.ApiClient
import kotlinx.serialization.encodeToString
import com.capital.accounting.auth.AuthViewModel
import com.capital.accounting.finance.Money
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun NotificationPanel(model:AuthViewModel) {
    val auth=model.state.auth ?: return
    val binding=auth.credential(model.state.origin).notificationBinding()
    key(binding.key) { NotificationPanelContent(model,binding) }
}

@Composable
private fun NotificationPanelContent(model:AuthViewModel,binding:NotificationBinding) {
    val context=LocalContext.current
    val store=(context.applicationContext as CapitalApplication).notificationInbox
    val scope=rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var inbox by remember { mutableStateOf<NotificationInbox?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var profileId by remember {mutableStateOf(java.util.UUID.randomUUID().toString())}
    var expectedCard by remember {mutableStateOf("")}
    var profileEnabled by remember {mutableStateOf(true)}
    var autoCreate by remember {mutableStateOf(false)}
    var testedProfile by remember {mutableStateOf<String?>(null)}
    var localPage by remember {mutableStateOf(0)}
    var packageName by remember { mutableStateOf("") }
    var template by remember { mutableStateOf("") }
    var account by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("expense") }
    var sample by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    var access by remember { mutableStateOf(false) }
    fun edit(value:NotificationProfile){profileId=value.id;packageName=value.packageName;template=value.template;account=value.accountId;kind=value.kind;expectedCard=value.expectedCardLastFour?:"";profileEnabled=value.enabled;autoCreate=value.autoCreatePending;testedProfile=null;sample=""}
    fun refresh(loadEditor:Boolean=false) { scope.launch {
        busy=true
        try {
            val loaded=store.read(binding);inbox=loaded
            if(loadEditor)loaded.profile?.let(::edit)
            access=NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        }catch(e:Exception) { if(e is CancellationException)throw e;message="Не удалось прочитать локальные предложения. Сохраненные данные не перезаписаны." }
        finally { busy=false }
    } }
    fun profile():NotificationProfile {
        val selected=model.state.accounts.single { it.id==account && it.deleted_at==null && it.archived_at==null }
        return NotificationProfile(profileId,packageName.trim(),template,selected.currency,kind,selected.id,expectedCardLastFour=expectedCard.ifEmpty {null},enabled=profileEnabled,autoCreatePending=autoCreate)
    }
    TextButton(onClick={expanded=!expanded;if(expanded)refresh(true)}) { Text("Банковские уведомления: локальный разбор") }
    if(!expanded)return
    val sources:SourcesModel=viewModel()
    val auth=model.state.auth?:return
    val saved=auth.credential(model.state.origin)
    LaunchedEffect(saved.origin,saved.ownerId,saved.workspaceId,saved.generationId,saved.sessionId,saved.token){sources.bind(saved);sources.load()}
    DisposableEffect(binding.key){onDispose{sources.clear()}}
    LaunchedEffect(sources.unauthorized){if(sources.unauthorized)model.sessionUnauthorized(auth.session.id)}
    LaunchedEffect(sources.delivered){if(sources.delivered?.first==binding)refresh()}
    LaunchedEffect(sources.items.any {it.state=="queued"},sources.busy){if(!sources.busy&&sources.items.any {it.state=="queued"}){delay(2000);sources.poll()}}
    LaunchedEffect(binding.key,inbox?.enabled,inbox?.configuredProfiles?.any {it.autoCreatePending&&it.enabled}) {
        if(inbox?.enabled==true&&inbox?.configuredProfiles?.any {it.autoCreatePending&&it.enabled}==true) {
            AutoSourceScheduler(context).schedule(binding)
            while(true){delay(3000);refresh();sources.automaticStatus()}
        }
    }
    var refreshed by remember {mutableStateOf<List<Pair<String,String>>>(emptyList())}
    val completed=sources.items.filter {it.state=="applied"}.map {it.id to it.version}
    LaunchedEffect(completed,model.state.busy){if(completed.isNotEmpty()&&completed!=refreshed&&!model.state.busy){refreshed=completed;model.loadOverview()}}
    Text("Доступ включается вами в настройках Android. Обрабатывается только выбранный пакет по вашему шаблону; полный текст не сохраняется и не отправляется. По умолчанию предложения остаются локальными. Автоматическая отправка и создание предварительных включаются отдельно для проверенного шаблона. Окончательное списание и отмена требуют явного подтверждения.")
    Text("Поддержка конкретного банка не подтверждена. Android может скрывать текст или пропускать уведомления; историю сверяйте с выпиской. Время предложения — время уведомления, а не доказанная дата покупки.")
    Text("Уведомлений без безопасного разбора: ${inbox?.skipped?:0}; отклонено из-за заполнения: ${inbox?.rejected?:0}; ошибок приёма в этом процессе: ${store.captureErrors.get()}. Сверяйте полноту с выпиской.")
    if(message.isNotEmpty())Text(message)
    if(busy)CircularProgressIndicator()
    Text(if(access)"Системный доступ включен" else "Системный доступ выключен")
    Button(onClick={
        try {
            val intent=if(Build.VERSION.SDK_INT>=30)Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,ComponentName(context,BankNotificationListener::class.java).flattenToString()) else Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            context.startActivity(intent)
        }catch(_:Exception) { message="Откройте доступ к уведомлениям для Capital в системных настройках." }
    }) { Text("Открыть настройки доступа к уведомлениям") }
    Button(enabled=!busy,onClick={refresh()}) { Text("Обновить доступ и предложения") }
    val enabled=!busy && !model.state.busy && model.state.persisted && !model.state.financeBlocked && !model.state.offline
    val knownProfile=inbox?.configuredProfiles?.find {it.id==profileId}
    val editable=enabled && (knownProfile==null || inbox?.items?.isEmpty()!=false)
    Text("Профили приложений и счетов (до 20)")
    inbox?.configuredProfiles?.forEach {value->TextButton(enabled=!busy,onClick={edit(value)}){Text("${value.packageName} · ${model.state.accounts.find {it.id==value.accountId}?.name?:"Счет недоступен"}${value.expectedCardLastFour?.let {" · *$it"}?:""}${if(value.enabled)"" else " · выключен"}")}}
    TextButton(enabled=enabled,onClick={profileId=java.util.UUID.randomUUID().toString();packageName="";template="";account="";kind="expense";expectedCard="";profileEnabled=true;autoCreate=false;testedProfile=null;sample=""}){Text("Добавить профиль другого приложения или карты")}
    if(!editable&&knownProfile!=null)Text("Привязка сохраненных наблюдений неизменяема. Для другого счета создайте новый профиль; старый можно выключить.")
    TextButton(enabled=enabled,onClick={profileEnabled=!profileEnabled}){Text(if(profileEnabled)"Профиль включен" else "Профиль выключен")}
    TextButton(enabled=enabled,onClick={autoCreate=!autoCreate}){Text(if(autoCreate)"Автоматическая доставка и предварительные: включены" else "Автоматическая доставка и предварительные: выключены")}
    Text("Автоматический режим отправляет только разобранные поля. Сервер создаёт предварительную операцию, если нет возможных дублей; неоднозначные случаи остаются для сверки. Старые ручные предложения автоматически не отправляются. Перед первым включением проверьте обезличенный пример.")
    OutlinedTextField(expectedCard,{expectedCard=it.filter {c->c in '0'..'9'}.take(4)},enabled=editable,label={Text("Фильтр последних 4 цифр карты (необязательно)")})
    BankAppPicker(packageName,editable){packageName=it}
    OutlinedTextField(template,{template=it},enabled=editable,label={Text("Шаблон текста уведомления")})
    Text("Фильтр карты требует токен {card}; при неоднозначном совпадении счет выбирается вручную.")
    Text("Токены: {amount}, {currency}, {merchant}, {card}. Сумма обязательна; карта — только последние 4 цифры. Остальной текст должен совпадать полностью.")
    model.state.accounts.filter { it.deleted_at==null && it.archived_at==null }.forEach { item ->
        TextButton(enabled=editable,onClick={account=item.id}) { Text("Счет уведомлений: ${item.name} · ${item.currency}${if(account==item.id)" ✓" else ""}") }
    }
    TextButton(enabled=editable,onClick={kind=if(kind=="expense")"income" else "expense"}) { Text(if(kind=="expense")"Направление: расход" else "Направление: доход") }
    OutlinedTextField(sample,{sample=it},label={Text("Обезличенный пример для проверки (не сохраняется)")})
    Button(enabled=!busy,onClick={
        message=try {
            val result=NotificationParser.parse(profile().copy(enabled=true),NotificationEnvelope(packageName.trim(),"local-preview",System.currentTimeMillis(),sample))
            if(result==null){testedProfile=null;"Нет безопасного однозначного совпадения."} else {testedProfile=ApiClient.json.encodeToString(profile().copy(enabled=true,autoCreatePending=false));"Предпросмотр: ${Money.display(result.amountMinor,result.currency)} · ${result.merchant}"}
        }catch(_:Exception) { "Проверьте счет, пакет и шаблон." }
        sample=""
    }) { Text("Проверить шаблон без сохранения операции") }
    Button(enabled=enabled,onClick={confirm=true}) {Text("Сохранить профиль приложения и счета")}
    if(inbox?.enabled==true) {
        Button(enabled=!busy,onClick={scope.launch {busy=true;try{inbox=store.disable(binding);message="Прием всех профилей остановлен. Уже отправленные задания проверяйте на сервере."}catch(e:Exception){if(e is CancellationException)throw e;message="Не удалось остановить прием."}finally{busy=false}}}){Text("Остановить прием всех профилей")}
    } else if(inbox?.configuredProfiles?.isNotEmpty()==true) {
        Text("В новой сессии проверьте сохраненные привязки перед включением. Старые предложения и запросы не переиздаются.")
        Button(enabled=enabled,onClick={scope.launch {busy=true;try{inbox=store.enable(binding);AutoSourceScheduler(context).schedule(binding);message="Прием сохраненных профилей включен для этой сессии."}catch(e:Exception){if(e is CancellationException)throw e;message="Проверьте профили и счета."}finally{busy=false}}}){Text("Включить прием сохраненных профилей")}
    }
    if(confirm)AlertDialog(onDismissRequest={confirm=false},title={Text("Сохранить профиль разбора?")},text={Text("Если прием сессии включен, активный профиль начнет сохранять разобранные поля выбранного приложения. Иначе включите прием отдельной кнопкой. Системный доступ Android включается отдельно. При включённом автоматическом режиме новые однозначные уведомления отправляются и могут создавать предварительные операции; окончательное списание требует отдельного подтверждения.")},confirmButton={TextButton(onClick={confirm=false;scope.launch { busy=true;try { val value=profile();val approved=knownProfile?.let {it.autoCreatePending&&it.copy(enabled=value.enabled,autoCreatePending=value.autoCreatePending)==value}==true
        require(!value.autoCreatePending||approved||testedProfile==ApiClient.json.encodeToString(value.copy(enabled=true,autoCreatePending=false)))
        inbox=store.configure(binding,value);if(inbox?.enabled==true)AutoSourceScheduler(context).schedule(binding);message="Профиль сохранен. Проверьте состояние приема и системный доступ." }catch(e:Exception) { if(e is CancellationException)throw e;message="Проверьте шаблон и счёт. Перед включением автоматического режима выполните успешный предпросмотр; для изменения сохранённой привязки создайте новый профиль." }finally{busy=false} }}){Text("Сохранить профиль")}},dismissButton={TextButton(onClick={confirm=false}){Text("Отмена")}})
    inbox?.let { value ->
        if(value.rejected>0)Text("Локальная очередь заполнена: пропущено ${value.rejected}. Сверьте операции с выпиской.")
        if(store.captureErrors.get()>0)Text("Не удалось сохранить часть уведомлений: ${store.captureErrors.get()}. Сверьте операции с выпиской.")
        Text("Предложений на проверку: ${value.items.count { it.state!="dismissed" }}. Квитанции повторов сохраняются локально; автоматической финансовой отправки нет.")
        val visible=value.items.filter {it.state!="dismissed"}.asReversed()
        val maximum=(visible.size-1).coerceAtLeast(0)/20
        LaunchedEffect(maximum){localPage=localPage.coerceAtMost(maximum)}
        if(localPage>0)TextButton(onClick={localPage--}){Text("Предыдущая страница локальных предложений")}
        if(localPage<maximum)TextButton(onClick={localPage++}){Text("Следующая страница локальных предложений")}
        visible.drop(localPage*20).take(20).forEach { item ->
            if(item.state=="ambiguous") {
                Text("Несколько профилей подходят к одному уведомлению. Выберите счет вручную; операция еще не создана.")
                item.alternatives.forEach {alternative->TextButton(enabled=enabled&&!sources.busy&&sources.pending==null,onClick={scope.launch {busy=true;try{inbox=store.choose(binding,item.eventId,alternative.profileId)}catch(e:Exception){if(e is CancellationException)throw e;message="Не удалось выбрать привязку."}finally{busy=false}}}){Text("Выбрать ${model.state.accounts.find {it.id==alternative.accountId}?.name?:"Недоступный счет"} · ${Money.display(alternative.amountMinor,alternative.currency)} · ${if(alternative.kind=="expense")"Расход" else "Доход"}")}}
            }
            if(item.state=="conflict") {
                Text("Уведомление изменилось. Исходные данные сохранены; новые поля требуют сверки.")
                item.revisions.forEach {revision->Text("Изменение: ${Money.display(revision.amountMinor,revision.currency)} · ${revision.merchant}")}
            }
            if(item.candidate==null)TextButton(enabled=!busy,onClick={scope.launch {busy=true;try{inbox=store.dismiss(binding,item.eventId)}catch(e:Exception){if(e is CancellationException)throw e;message="Не удалось исключить предложение."}finally{busy=false}}}){Text("Исключить локальное предложение, сохранив квитанцию")}
            item.candidate?.let { c ->
                Text("${Money.display(c.amountMinor,c.currency)} · ${c.merchant}")
                Text("Получено: ${java.util.Date(c.observedAtMillis)}${if(c.cardLastFour==null)"" else " · карта *${c.cardLastFour}"}")
                if(item.state=="conflict")Text("Уведомление изменилось. Исходное предложение сохранено; требуется сверка.")
                if(item.serverEventId!=null)Text("Предложение сохранено на сервере. Управляйте им в списке ниже.")
                if(item.state=="review"&&item.serverEventId==null)Button(enabled=enabled&&!sources.busy&&sources.pending==null,onClick={sources.upload(binding,c)}){Text("Отправить разобранное предложение на сервер")}
                TextButton(enabled=!busy,onClick={scope.launch { busy=true;try{inbox=store.dismiss(binding,item.eventId)}catch(e:Exception){if(e is CancellationException)throw e;message="Не удалось исключить предложение."}finally{busy=false} }}){Text("Исключить предложение, сохранив защиту от повтора")}
            }
        }
    }
    SourceControls(sources,model)
}
