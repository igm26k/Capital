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
    var packageName by remember { mutableStateOf("") }
    var template by remember { mutableStateOf("") }
    var account by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("expense") }
    var sample by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    var access by remember { mutableStateOf(false) }
    fun refresh() { scope.launch {
        busy=true
        try {
            val loaded=store.read(binding);inbox=loaded
            loaded.profile?.let { packageName=it.packageName;template=it.template;account=it.accountId;kind=it.kind }
            access=NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        }catch(e:Exception) { if(e is CancellationException)throw e;message="Не удалось прочитать локальные предложения. Сохраненные данные не перезаписаны." }
        finally { busy=false }
    } }
    fun profile():NotificationProfile {
        val selected=model.state.accounts.single { it.id==account && it.deleted_at==null && it.archived_at==null }
        return NotificationProfile("personal-v1",packageName.trim(),template,selected.currency,kind,selected.id)
    }
    TextButton(onClick={expanded=!expanded;if(expanded)refresh()}) { Text("Банковские уведомления: локальный разбор") }
    if(!expanded)return
    val sources:SourcesModel=viewModel()
    val auth=model.state.auth?:return
    val saved=auth.credential(model.state.origin)
    LaunchedEffect(saved.origin,saved.ownerId,saved.workspaceId,saved.generationId,saved.sessionId,saved.token){sources.bind(saved);sources.load()}
    DisposableEffect(binding.key){onDispose{sources.clear()}}
    LaunchedEffect(sources.unauthorized){if(sources.unauthorized)model.sessionUnauthorized(auth.session.id)}
    LaunchedEffect(sources.delivered){if(sources.delivered?.first==binding)refresh()}
    LaunchedEffect(sources.items.any {it.state=="queued"},sources.busy){if(!sources.busy&&sources.items.any {it.state=="queued"}){delay(2000);sources.load()}}
    var refreshed by remember {mutableStateOf<List<Pair<String,String>>>(emptyList())}
    val completed=sources.items.filter {it.state=="applied"}.map {it.id to it.version}
    LaunchedEffect(completed,model.state.busy){if(completed.isNotEmpty()&&completed!=refreshed&&!model.state.busy){refreshed=completed;model.loadOverview()}}
    Text("Доступ включается вами в настройках Android. Обрабатывается только выбранный пакет по вашему шаблону; полный текст не сохраняется и не отправляется. Локальные предложения не меняют остатки. Отправка и каждое финансовое действие требуют вашего подтверждения.")
    Text("Поддержка конкретного банка не подтверждена. Android может скрывать текст или пропускать уведомления; историю сверяйте с выпиской. Время предложения — время уведомления, а не доказанная дата покупки.")
    if(message.isNotEmpty())Text(message)
    if(busy)CircularProgressIndicator()
    Text(if(access)"Системный доступ включен" else "Системный доступ выключен")
    Button(onClick={
        try {
            val intent=if(Build.VERSION.SDK_INT>=30)Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,ComponentName(context,BankNotificationListener::class.java).flattenToString()) else Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            context.startActivity(intent)
        }catch(_:Exception) { message="Откройте доступ к уведомлениям для Capital в системных настройках." }
    }) { Text("Открыть настройки доступа к уведомлениям") }
    Button(enabled=!busy,onClick=::refresh) { Text("Обновить доступ и предложения") }
    val enabled=!busy && !model.state.busy && model.state.persisted && !model.state.financeBlocked && !model.state.offline
    val editable=enabled && inbox?.enabled!=true && inbox?.items?.isEmpty()!=false
    OutlinedTextField(packageName,{packageName=it},enabled=editable,label={Text("Пакет банковского приложения")})
    OutlinedTextField(template,{template=it},enabled=editable,label={Text("Шаблон текста уведомления")})
    Text("Токены: {amount}, {currency}, {merchant}, {card}. Сумма обязательна; карта — только последние 4 цифры. Остальной текст должен совпадать полностью.")
    model.state.accounts.filter { it.deleted_at==null && it.archived_at==null }.forEach { item ->
        TextButton(enabled=editable,onClick={account=item.id}) { Text("Счет уведомлений: ${item.name} · ${item.currency}${if(account==item.id)" ✓" else ""}") }
    }
    TextButton(enabled=editable,onClick={kind=if(kind=="expense")"income" else "expense"}) { Text(if(kind=="expense")"Направление: расход" else "Направление: доход") }
    OutlinedTextField(sample,{sample=it},label={Text("Обезличенный пример для проверки (не сохраняется)")})
    Button(enabled=!busy,onClick={
        message=try {
            val result=NotificationParser.parse(profile(),NotificationEnvelope(packageName.trim(),"local-preview",System.currentTimeMillis(),sample))
            if(result==null)"Нет безопасного однозначного совпадения." else "Предпросмотр: ${Money.display(result.amountMinor,result.currency)} · ${result.merchant}"
        }catch(_:Exception) { "Проверьте счет, пакет и шаблон." }
        sample=""
    }) { Text("Проверить шаблон без сохранения операции") }
    if(inbox?.enabled==true) {
        Button(enabled=!busy,onClick={scope.launch { busy=true;try { inbox=store.disable(binding);message="Локальный прием остановлен. Системный доступ можно выключить в настройках." }catch(e:Exception) { if(e is CancellationException)throw e;message="Не удалось остановить прием." }finally{busy=false} }}) { Text("Остановить локальный прием") }
    } else Button(enabled=enabled,onClick={confirm=true}) { Text("Включить локальный прием выбранного приложения") }
    if(confirm)AlertDialog(onDismissRequest={confirm=false},title={Text("Включить локальный разбор?")},text={Text("Будут сохраняться сумма, валюта, продавец и последние 4 цифры карты только для выбранного приложения и текущего профиля. Остатки не изменятся. Системный доступ Android включается отдельно.")},confirmButton={TextButton(onClick={confirm=false;scope.launch { busy=true;try { inbox=store.configure(binding,profile());message="Локальный прием включен. Проверьте системный доступ." }catch(e:Exception) { if(e is CancellationException)throw e;message="Проверьте шаблон и счет. Изменение привязки при сохраненных предложениях запрещено." }finally{busy=false} }}){Text("Включить")}},dismissButton={TextButton(onClick={confirm=false}){Text("Отмена")}})
    inbox?.let { value ->
        if(value.rejected>0)Text("Локальная очередь заполнена: пропущено ${value.rejected}. Сверьте операции с выпиской.")
        if(store.captureErrors.get()>0)Text("Не удалось сохранить часть уведомлений: ${store.captureErrors.get()}. Сверьте операции с выпиской.")
        Text("Предложений на проверку: ${value.items.count { it.state!="dismissed" }}. Квитанции повторов сохраняются локально; автоматической финансовой отправки нет.")
        value.items.filter { it.state!="dismissed" }.takeLast(20).reversed().forEach { item ->
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
