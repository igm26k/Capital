package com.capital.accounting.imports

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.capital.accounting.CapitalApplication
import com.capital.accounting.api.ApiClient
import com.capital.accounting.api.credential
import com.capital.accounting.auth.AuthViewModel
import com.capital.accounting.finance.CapitalSelect
import com.capital.accounting.finance.capitalMoney
import com.capital.accounting.finance.openingInstant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun NotificationPanel(model:AuthViewModel) {
    val auth=model.state.auth?:return
    val binding=auth.credential(model.state.origin).notificationBinding()
    key(binding.key){BankMessagesContent(model,binding)}
}

@Composable
private fun BankMessagesContent(model:AuthViewModel,binding:NotificationBinding) {
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val store=(context.applicationContext as CapitalApplication).notificationInbox
    val scope=rememberCoroutineScope()
    val sources:SourcesModel=viewModel()
    val auth=model.state.auth?:return
    val saved=auth.credential(model.state.origin)
    var inbox by remember {mutableStateOf<NotificationInbox?>(null)}
    var busy by remember {mutableStateOf(false)}
    var message by remember {mutableStateOf("")}
    var pushAccess by remember {mutableStateOf(false)}
    var smsAccess by remember {mutableStateOf(false)}
    var editor by rememberSaveable {mutableStateOf(false)}
    var profileId by rememberSaveable {mutableStateOf("")}
    var bank by rememberSaveable {mutableStateOf("")}
    var channel by rememberSaveable {mutableStateOf("push")}
    var sender by rememberSaveable {mutableStateOf("")}
    var packageName by rememberSaveable {mutableStateOf("")}
    var template by rememberSaveable {mutableStateOf("")}
    var accountId by rememberSaveable {mutableStateOf("")}
    var kind by rememberSaveable {mutableStateOf("expense")}
    var textSource by rememberSaveable {mutableStateOf("body")}
    var separator by rememberSaveable {mutableStateOf(",")}
    var card by rememberSaveable {mutableStateOf("")}
    var enabledRule by rememberSaveable {mutableStateOf(true)}
    var automatic by rememberSaveable {mutableStateOf(false)}
    var testText by remember {mutableStateOf("")}
    var tested by remember {mutableStateOf<String?>(null)}
    var selectedId by rememberSaveable {mutableStateOf("")}
    var rawText by remember {mutableStateOf("")}
    var occurred by rememberSaveable {mutableStateOf("")}
    var preview by remember {mutableStateOf<NotificationCandidate?>(null)}
    var reviewOpen by rememberSaveable {mutableStateOf(true)}
    var localPage by rememberSaveable {mutableIntStateOf(0)}
    var deleting by remember {mutableStateOf<NotificationProfile?>(null)}
    var autoConfirm by remember {mutableStateOf(false)}
    val accounts=model.state.accounts.filter {it.deleted_at==null&&it.archived_at==null}
    val profiles=inbox?.configuredProfiles.orEmpty()
    val online=!model.state.busy&&!model.state.financeBlocked&&model.state.persisted&&!model.state.offline
    val localEnabled=!busy&&model.state.persisted&&inbox!=null
    fun permissions(){pushAccess=NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName);smsAccess=ContextCompat.checkSelfPermission(context,Manifest.permission.RECEIVE_SMS)==PackageManager.PERMISSION_GRANTED}
    fun refresh(){scope.launch {try{inbox=store.read(binding);permissions()}catch(e:Exception){if(e is CancellationException)throw e;message="Не удалось прочитать настройки банков. Исходные данные сохранены."}}}
    fun mutate(action:suspend()->NotificationInbox){if(busy)return;scope.launch {busy=true;try{inbox=action()}catch(e:Exception){if(e is CancellationException)throw e;message=e.message?.takeIf {it.startsWith("Выберите")||it.startsWith("Проверьте")}?:"Не удалось сохранить изменения. Для изменения использованной привязки создайте новое правило."}finally{busy=false}}}
    fun edit(value:NotificationProfile?){
        profileId=value?.id?:UUID.randomUUID().toString();bank=value?.bankName?.ifEmpty {value.packageName}?:""
        channel=value?.channel?:"push";sender=value?.sender?:"";packageName=value?.packageName?:""
        template=value?.template?:"";accountId=value?.accountId?:accounts.firstOrNull()?.id.orEmpty();kind=value?.kind?:"expense"
        textSource=value?.textSource?:"body";separator=value?.decimalSeparator?:",";card=value?.expectedCardLastFour?:"";enabledRule=value?.enabled?:true;automatic=value?.autoCreatePending?:false
        tested=null;testText="";editor=true;message=""
    }
    fun definition():NotificationProfile {
        require(bank.isNotBlank()){"Проверьте название банка."}
        val account=requireNotNull(accounts.find {it.id==accountId}){"Выберите активный счет."}
        val origin=if(channel=="sms")smsPackage(sender) else packageName.trim()
        return NotificationProfile(profileId,origin,template.replace("\r\n","\n").trim(),account.currency,kind,account.id,expectedCardLastFour=card.ifEmpty {null},enabled=enabledRule,autoCreatePending=automatic,bankName=bank.trim(),channel=channel,sender=if(channel=="sms")sender.trim() else "",decimalSeparator=separator,textSource=if(channel=="sms")"body" else textSource).also(NotificationParser::validate)
    }
    fun saveRule(){
        try {
            val value=definition()
            val old=profiles.find {it.id==value.id}
            require(!value.autoCreatePending||old?.let {it.autoCreatePending&&it.copy(enabled=value.enabled,bankName=value.bankName)==value}==true||tested==ApiClient.json.encodeToString(value.copy(enabled=true,autoCreatePending=false))){"Проверьте шаблон перед включением автоматического режима."}
            mutate {store.configure(binding,value).also {editor=false;message="Правило банка сохранено на этом устройстве.";if(it.enabled)AutoSourceScheduler(context).schedule(binding)}}
        }catch(e:Exception){message=e.message?.takeIf {it.startsWith("Проверьте")||it.startsWith("Выберите")}?:"Проверьте банк, отправителя, счет, шаблон, валюту и четыре цифры карты."}
    }
    val permissionRequest=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->smsAccess=granted;message=if(granted)"Доступ к новым SMS разрешен. Включите прием банков ниже." else "Android не предоставил доступ к SMS. Можно вставлять текст вручную; повторное разрешение доступно в настройках приложения, если его допускает устройство и установщик."}
    LaunchedEffect(binding.key,saved.token){sources.bind(saved);if(!model.state.offline)sources.load();refresh()}
    DisposableEffect(binding.key){onDispose{sources.clear()}}
    DisposableEffect(lifecycle,binding.key){val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_RESUME)refresh()};lifecycle.addObserver(observer);onDispose{lifecycle.removeObserver(observer)}}
    LaunchedEffect(sources.unauthorized){if(sources.unauthorized)model.sessionUnauthorized(auth.session.id)}
    LaunchedEffect(sources.delivered){if(sources.delivered?.first==binding)refresh()}
    LaunchedEffect(sources.items.any {it.state=="queued"},sources.busy){if(!sources.busy&&sources.items.any {it.state=="queued"}){delay(2000);sources.poll()}}
    LaunchedEffect(binding.key,inbox?.enabled){if(inbox?.enabled==true){AutoSourceScheduler(context).schedule(binding);while(true){delay(3000);refresh();if(!sources.busy&&online)sources.automaticStatus()}}}
    var refreshed by remember {mutableStateOf<List<Pair<String,String>>>(emptyList())}
    val completed=sources.items.filter {it.state=="applied"}.map {it.id to it.version}
    LaunchedEffect(completed,model.state.busy){if(completed.isNotEmpty()&&completed!=refreshed&&!model.state.busy){refreshed=completed;model.loadOverview()}}

    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("Банки и уведомления",style=MaterialTheme.typography.titleLarge)}
    Text("Добавьте банк, отправителя, счет и шаблон. Правила сохраняются на этом устройстве; предложения на сервере доступны и в веб-версии.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    if(message.isNotEmpty())Text(message,color=MaterialTheme.colorScheme.primary)
    if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
    Button(enabled=localEnabled&&profiles.size<20,onClick={edit(null)}){Text("Добавить банк")}
    Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)){
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
            Text("Прием новых сообщений",style=MaterialTheme.typography.titleMedium)
            Text("Push: ${if(pushAccess)"доступ включен" else "доступ выключен"} · SMS: ${if(smsAccess)"доступ включен" else "доступ выключен"}",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={try{val intent=if(Build.VERSION.SDK_INT>=30)Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,ComponentName(context,BankNotificationListener::class.java).flattenToString()) else Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);context.startActivity(intent)}catch(_:Exception){message="Откройте доступ к уведомлениям Capital в настройках Android."}}){Text("Разрешить push-уведомления")}
            TextButton(enabled=!smsAccess,onClick={permissionRequest.launch(Manifest.permission.RECEIVE_SMS)}){Text("Разрешить новые SMS")}
            Text("Обрабатываются только выбранные приложения и SMS-отправители. История SMS не читается. Без системного разрешения доступна ручная вставка.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Button(enabled=localEnabled&&(inbox?.enabled==true||profiles.any {it.enabled}),onClick={mutate{if(inbox?.enabled==true)store.disable(binding).also {message="Прием остановлен. Принятые сервером задания сохраняются."} else store.enable(binding).also {AutoSourceScheduler(context).schedule(binding);message="Прием включен для этой сессии."}}}){Text(if(inbox?.enabled==true)"Остановить прием" else "Включить прием")}
            Text("Прием: ${if(inbox?.enabled==true)"включен" else "выключен"}",style=MaterialTheme.typography.labelMedium)
        }
    }
    profiles.forEach {value->Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)){
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
            Text(value.bankName.ifEmpty {value.packageName},style=MaterialTheme.typography.titleSmall)
            Text("${if(value.channel=="sms")"SMS · ${value.sender}" else "Push · ${value.packageName}"} · ${if(value.kind=="expense")"Расход" else "Доход"}",style=MaterialTheme.typography.bodySmall)
            Text("${model.state.accounts.find {it.id==value.accountId}?.name?:"Счет недоступен"} · ${value.currency}${value.expectedCardLastFour?.let {" · *$it"}?:""}${if(value.enabled)"" else " · Выключено"}",style=MaterialTheme.typography.bodySmall)
            FlowRow{TextButton(enabled=localEnabled,onClick={edit(value)}){Text("Изменить")};TextButton(enabled=localEnabled,onClick={mutate{store.configure(binding,value.copy(enabled=!value.enabled))}}){Text(if(value.enabled)"Выключить" else "Включить")};TextButton(enabled=localEnabled,onClick={deleting=value}){Text("Удалить")}}
        }
    }}
    deleting?.let {value->AlertDialog(onDismissRequest={deleting=null},title={Text("Удалить правило банка?")},text={Text("Предложения, операции и защита от повторов сохранятся.")},confirmButton={TextButton(enabled=localEnabled,onClick={deleting=null;mutate {store.removeProfile(binding,value.id)}}){Text("Удалить правило")}},dismissButton={TextButton(onClick={deleting=null}){Text("Отмена")}})}
    if(editor){
        val known=profiles.find {it.id==profileId}
        val used=inbox?.items?.any {it.referencesProfile(profileId)}==true
        val editable=localEnabled&&(known==null||!used)
        Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text(if(known==null)"Новый банк" else "Правило банка",style=MaterialTheme.typography.titleMedium)
            OutlinedTextField(bank,{bank=it.take(80)},enabled=localEnabled,label={Text("Название банка")},modifier=Modifier.fillMaxWidth())
            CapitalSelect("Тип сообщения",channel,listOf("sms" to "SMS","push" to "Push-уведомление"),editable){channel=it}
            if(channel=="sms")OutlinedTextField(sender,{sender=it.take(100)},enabled=editable,label={Text("Отправитель SMS")},modifier=Modifier.fillMaxWidth()) else {
                BankAppPicker(packageName,editable){packageName=it}
                CapitalSelect("Текст push-уведомления",textSource,listOf("body" to "Текст сообщения","title" to "Заголовок","title_body" to "Заголовок и текст через перенос строки"),editable){textSource=it}
            }
            CapitalSelect("Счет банка",accountId,accounts.map {it.id to "${it.name} · ${it.currency}"},editable){accountId=it}
            CapitalSelect("Тип операции",kind,listOf("expense" to "Расход","income" to "Доход"),editable){kind=it}
            OutlinedTextField(template,{template=it.take(1000)},enabled=editable,label={Text("Шаблон сообщения")},modifier=Modifier.fillMaxWidth(),minLines=3)
            Text("Например: Оплата {amount} {currency} в {merchant}, карта *{card}. Нужна {amount}; остальные токены необязательны. Текст и пробелы должны совпадать с сообщением.",style=MaterialTheme.typography.bodySmall)
            CapitalSelect("Десятичный разделитель",separator,listOf("," to "Запятая: 1 234,56","." to "Точка: 1,234.56","" to "Прежний формат"),editable){separator=it}
            OutlinedTextField(card,{card=it.filter {c->c in '0'..'9'}.take(4)},enabled=editable,label={Text("Последние 4 цифры карты (необязательно)")},modifier=Modifier.fillMaxWidth())
            if(!editable&&used)Text("Привязка использованных сообщений защищена. Для другого счета или формата создайте новое правило.",style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(testText,{testText=it.take(8192);tested=null},label={Text("Пример для проверки шаблона")},modifier=Modifier.fillMaxWidth(),minLines=2)
            TextButton(enabled=localEnabled,onClick={try{val value=definition().copy(enabled=true,autoCreatePending=false);val result=NotificationParser.parse(value,bankMessageEnvelope(value,1,testText));requireNotNull(result);tested=ApiClient.json.encodeToString(value);message="Распознано: ${capitalMoney(result.amountMinor,result.currency)} · ${result.merchant}"}catch(_:Exception){tested=null;message="Пример не совпал. Проверьте шаблон, валюту, разделитель и карту."};testText=""}){Text("Проверить шаблон")}
            Row{Checkbox(enabledRule,{enabledRule=it},enabled=localEnabled);Text("Правило включено")}
            TextButton(enabled=localEnabled,onClick={if(automatic)automatic=false else autoConfirm=true}){Text(if(automatic)"Автоматические предварительные: включены" else "Автоматические предварительные: выключены")}
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton(enabled=!busy,onClick={editor=false;testText=""}){Text("Отмена")};Button(enabled=localEnabled,onClick=::saveRule){Text("Сохранить правило")}}
        }}
    }
    if(autoConfirm)AlertDialog(onDismissRequest={autoConfirm=false},title={Text("Автоматические предварительные операции?")},text={Text("После успешной проверки шаблона новые однозначные сообщения будут отправляться на сервер и могут создавать предварительные операции. Окончательное подтверждение выполняется отдельно. Ручные примеры автоматически не отправляются.")},confirmButton={TextButton(onClick={automatic=true;autoConfirm=false}){Text("Включить для этого правила")}},dismissButton={TextButton(onClick={autoConfirm=false}){Text("Отмена")}})
    Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)){Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("Распознать сообщение",style=MaterialTheme.typography.titleMedium)
        CapitalSelect("Банк и правило",selectedId,profiles.filter {it.enabled}.map {it.id to "${it.bankName.ifEmpty {it.packageName}} · ${if(it.channel=="sms")"SMS" else "Push"} · ${if(it.kind=="expense")"Расход" else "Доход"}"},localEnabled){selectedId=it;preview=null}
        OutlinedTextField(occurred,{occurred=it;preview=null},label={Text("Дата сообщения (YYYY-MM-DDTHH:MM:SS)")},modifier=Modifier.fillMaxWidth())
        OutlinedTextField(rawText,{rawText=it.take(8192);preview=null},label={Text("Текст сообщения")},modifier=Modifier.fillMaxWidth(),minLines=3)
        Text("Укажите исходное время сообщения в часовом поясе устройства. SMS и push одной покупки связывайте с одной операцией при проверке.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(enabled=localEnabled,onClick={try{val value=requireNotNull(profiles.find {it.id==selectedId&&it.enabled});require(accounts.any {it.id==value.accountId&&it.currency==value.currency});val instant=openingInstant(occurred,TimeZone.getDefault().id);val millis=SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.ROOT).apply {timeZone=TimeZone.getTimeZone("UTC")}.parse(instant)!!.time;preview=requireNotNull(NotificationParser.parse(value,bankMessageEnvelope(value,millis,rawText)));message=""}catch(_:Exception){preview=null;message="Проверьте правило, активный счет, дату, время и текст сообщения."}}){Text("Распознать")}
        preview?.let {candidate->
            Text("${if(candidate.kind=="expense")"Расход" else "Доход"} · ${capitalMoney(candidate.amountMinor,candidate.currency)}",style=MaterialTheme.typography.titleMedium)
            Text("${accounts.find {it.id==candidate.accountId}?.name?:"Счет недоступен"} · ${candidate.merchant.ifEmpty {"Без получателя"}}${candidate.cardLastFour?.let {" · *$it"}?:""}")
            Button(enabled=localEnabled&&!sources.busy&&sources.pending==null,onClick={mutate {
                store.review(binding,candidate).also {value->
                    val item=value.items.single {it.eventId==candidate.eventId}
                    if(item.state=="review"&&item.serverEventId==null&&online)sources.upload(binding,requireNotNull(item.candidate))
                    message=if(item.state=="dismissed")"Сообщение уже исключено: повтор не создал новое предложение." else if(item.serverEventId!=null)"Сообщение уже принято сервером." else if(item.state=="conflict")"Параметры сообщения изменились. Исходное предложение сохранено для сверки." else if(online)"Сообщение сохранено для отправки. Подтвердите операцию отдельно." else "Сохранено на устройстве. Отправьте предложение после подключения."
                    rawText="";preview=null;reviewOpen=true
                }
            }}){Text("Добавить в предложения")}
        }
    }}
    TextButton(onClick={reviewOpen=!reviewOpen;if(reviewOpen){refresh();if(online&&!sources.busy)sources.load()}}){Text(if(reviewOpen)"Скрыть предложения" else "Банковские предложения")}
    if(reviewOpen){
        val value=inbox
        val visible=value?.items.orEmpty().filter {it.state!="dismissed"}.asReversed()
        val maximum=(visible.size-1).coerceAtLeast(0)/20
        LaunchedEffect(maximum){localPage=localPage.coerceAtMost(maximum)}
        Text("На устройстве: ${visible.size}",style=MaterialTheme.typography.titleMedium)
        if((value?.skipped?:0)>0||(value?.rejected?:0)>0||store.captureErrors.get()>0)Text("Не распознано: ${value?.skipped?:0}; очередь заполнена: ${value?.rejected?:0}; ошибок приема: ${store.captureErrors.get()}. Сверьте историю с выпиской.",style=MaterialTheme.typography.bodySmall)
        Row{if(localPage>0)TextButton(onClick={localPage--}){Text("Предыдущие")};if(localPage<maximum)TextButton(onClick={localPage++}){Text("Следующие")}}
        visible.drop(localPage*20).take(20).forEach {item->Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
            item.candidate?.let {candidate->
                Text("${if(candidate.kind=="expense")"Расход" else "Доход"} · ${capitalMoney(candidate.amountMinor,candidate.currency)}",style=MaterialTheme.typography.titleSmall)
                Text(candidate.merchant.ifEmpty {"Без получателя"})
                Text("${model.state.accounts.find {it.id==candidate.accountId}?.name?:"Счет недоступен"} · ${Date(candidate.observedAtMillis)}",style=MaterialTheme.typography.bodySmall)
                if(item.serverEventId!=null)Text("Сохранено на сервере",style=MaterialTheme.typography.labelSmall)
                if(item.state=="review"&&item.serverEventId==null)TextButton(enabled=online&&!sources.busy&&sources.pending==null,onClick={sources.upload(binding,candidate)}){Text("Отправить предложение")}
            }
            if(item.state=="ambiguous"){
                Text("Подошло несколько правил. Выберите счет.")
                item.alternatives.forEach {alternative->TextButton(enabled=localEnabled&&!sources.busy&&sources.pending==null,onClick={mutate{store.choose(binding,item.eventId,alternative.profileId)}}){Text("${model.state.accounts.find {it.id==alternative.accountId}?.name?:"Счет недоступен"} · ${capitalMoney(alternative.amountMinor,alternative.currency)}")}}
            }
            if(item.state=="conflict")Text("Уведомление изменилось. Исходные данные сохранены; проверьте сверку вручную.")
            TextButton(enabled=localEnabled,onClick={mutate{store.dismiss(binding,item.eventId)}}){Text("Исключить предложение")}
        }}}
        SourceControls(sources,model)
    }
}
