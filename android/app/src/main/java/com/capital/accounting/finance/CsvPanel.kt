package com.capital.accounting.finance

import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.capital.accounting.api.*
import com.capital.accounting.auth.AuthViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

@Composable
fun CsvPanel(model:AuthViewModel){
    if(LocalFinancialSection.current.value!="more")return
    if (LocalSettingsGroup.current != "data") return
    val state=model.state;val auth=state.auth?:return
    var expanded by rememberSaveable{mutableStateOf(false)}
    TextButton(onClick={expanded=!expanded}){Text("Импорт и экспорт CSV")};if(!expanded)return
    val csv:CsvModel=viewModel();val context=LocalContext.current;val coroutine=rememberCoroutineScope()
    LaunchedEffect(csv.unauthorized){if(csv.unauthorized)model.sessionUnauthorized(auth.session.id)}
    LaunchedEffect(state.origin,auth.profile.id,auth.workspace.id,auth.session.id,auth.workspace.sync_generation_id){csv.bind(auth.credential(state.origin))}
    DisposableEffect(state.origin,auth.profile.id,auth.workspace.id,auth.session.id,auth.workspace.sync_generation_id){onDispose{csv.clear()}}
    var uri by remember{mutableStateOf<android.net.Uri?>(null)};var content by remember{mutableStateOf<String?>(null)};var error by remember{mutableStateOf("")}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri=it;content=null}
    LaunchedEffect(uri){val selected=uri?:return@LaunchedEffect;try{content=withContext(Dispatchers.IO){val out=ByteArrayOutputStream();context.contentResolver.openInputStream(selected)!!.use{input->val buffer=ByteArray(8192);while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=10*1024*1024);out.write(buffer,0,n)}};val bytes=out.toByteArray();Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));Base64.encodeToString(bytes,Base64.NO_WRAP)}}catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e;error="Выберите UTF-8 CSV до 10 MiB."}}
    val saver=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")){target->val bytes=csv.export;if(target!=null&&bytes!=null)coroutine.launch{try{withContext(Dispatchers.IO){context.contentResolver.openOutputStream(target)!!.use{it.write(bytes)}}}catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e;error="Не удалось сохранить файл."}finally{csv.clearExport()}}else csv.clearExport()}
    LaunchedEffect(csv.export){if(csv.export!=null)saver.launch("accounting.csv")}
    var account by rememberSaveable{mutableStateOf(state.accounts.firstOrNull{it.archived_at==null}?.id?:"")}
    var date by rememberSaveable{mutableStateOf("date")};var amount by rememberSaveable{mutableStateOf("amount")};var payee by rememberSaveable{mutableStateOf("payee")};var zone by rememberSaveable{mutableStateOf(java.util.TimeZone.getDefault().id)}
    var delimiter by rememberSaveable{mutableStateOf(",")};var decimal by rememberSaveable{mutableStateOf(".")};var format by rememberSaveable{mutableStateOf("YYYY-MM-DD")}
    val enabled=!csv.busy&&!state.busy&&!state.offline&&state.persisted
    Text("UTF-8 CSV, до 10 MiB и 1000 строк. Положительная сумма — доход, отрицательная — расход. Импорт работает при соединении с сервером.")
    if(error.isNotEmpty())Text(error);if(csv.message.isNotEmpty())Text(csv.message);if(csv.busy)CircularProgressIndicator()
    Button(enabled=enabled&&csv.pending==null,onClick={picker.launch(arrayOf("text/*","application/csv","application/octet-stream"))}){Text(if(content==null)"Выбрать CSV файл" else "CSV файл выбран")}
    state.accounts.filter{it.archived_at==null}.forEach{a->TextButton(enabled=enabled,onClick={account=a.id}){Text("Счет CSV: ${a.name} · ${a.currency}${if(account==a.id)" ✓" else ""}")}}
    OutlinedTextField(date,{date=it},label={Text("Колонка даты CSV")},enabled=enabled);OutlinedTextField(amount,{amount=it},label={Text("Колонка суммы CSV")},enabled=enabled);OutlinedTextField(payee,{payee=it},label={Text("Колонка получателя CSV (необязательно)")},enabled=enabled);OutlinedTextField(zone,{zone=it},label={Text("Часовой пояс CSV")},enabled=enabled)
    for(d in listOf(",",";","\t"))TextButton(enabled=enabled,onClick={delimiter=d}){Text("Разделитель CSV: ${if(d=="\t")"табуляция" else d}${if(delimiter==d)" ✓" else ""}")}
    TextButton(enabled=enabled,onClick={decimal=if(decimal==".")"," else "."}){Text("Десятичный разделитель CSV: $decimal")}
    TextButton(enabled=enabled,onClick={format=if(format=="YYYY-MM-DD")"RFC3339" else "YYYY-MM-DD"}){Text("Формат даты CSV: $format")}
    Button(enabled=enabled&&content!=null&&account.isNotEmpty()&&csv.pending==null,onClick={csv.upload(content!!,ImportMapping(account,date,amount,payee.takeIf{it.isNotBlank()},format,zone,delimiter,"UTF-8",decimal,"signed_income_positive"))}){Text("Предпросмотр CSV")}
    csv.pending?.let{p->if(p.rejected)TextButton(enabled=enabled,onClick=csv::dismiss){Text("Закрыть отклонённый запрос CSV")}else Button(enabled=enabled,onClick=csv::retry){Text("Повторить сохраненный запрос CSV")}}
    var confirm by remember{mutableStateOf(false)};var page by remember{mutableStateOf(0)}
    csv.preview?.let{p->
        Text("Результат CSV: ${when(p.state){"ready"->"Готов к подтверждению";"queued"->"Выполняется";"committed"->"Завершен";"failed"->"Нужна проверка и повтор";else->"Истек"}}")
        Text("Создано: ${p.processing.count{it.state=="applied"}}; сопоставлено: ${p.processing.count{it.state=="linked"}}; пропущено: ${p.processing.count{it.state=="skipped"}}")
        p.error_code?.let{Text(if(p.state=="ready")"Появились новые возможные совпадения. Предпросмотр обновлён; проверьте решения и подтвердите их заново." else "${csvReason(it)} Уже записанные строки сохранятся.")}
        Button(enabled=enabled,onClick={csv.refresh();model.loadOverview()}){Text("Обновить результат CSV")}
        p.rows.drop(page*50).take(50).forEach{row->
            Text("Строка ${row.row_number}: ${row.occurred_at?:"—"} · ${row.amount_minor?.let{Money.displayAggregate(it,p.currency)}?:"—"} · ${row.payee}")
            if(row.status=="invalid")Text(row.errors.joinToString{it.message})
            if(p.state=="ready"){
                val decision=csv.decisions.find{it.row_number==row.row_number};Text("Решение: ${when(decision?.action){"create"->"Создать отдельную операцию";"link"->"Сопоставить";else->"Пропустить"}}")
                TextButton(enabled=enabled&&csv.pending==null,onClick={csv.decision(row.row_number,"skip")}){Text("Пропустить строку ${row.row_number}")}
                if(row.status!="invalid"&&row.source_transaction_id==null)TextButton(enabled=enabled&&csv.pending==null,onClick={csv.decision(row.row_number,"create")}){Text("Создать отдельную операцию из строки ${row.row_number}")}
                row.candidate_transaction_ids.forEachIndexed{i,id->TextButton(enabled=enabled&&csv.pending==null,onClick={csv.link(row.row_number,id)}){Text("Проверить и сопоставить совпадение ${i+1} строки ${row.row_number}")}}
            }
        }
        if(page>0)TextButton(onClick={page--}){Text("Предыдущие строки CSV")};if((page+1)*50<p.rows.size)TextButton(onClick={page++}){Text("Следующие строки CSV")}
        csv.match?.let{t->Text("Совпадение: ${t.payee} · ${t.occurred_at} · ${t.entries.joinToString{Money.displayAggregate(it.amount_minor,it.currency)}}. Категория и примечание сохранятся.")}
        if(p.state=="ready"||p.state=="failed")Button(enabled=enabled&&csv.pending==null,onClick={confirm=true}){Text(if(p.state=="failed")"Проверить повтор CSV" else "Проверить подтверждение CSV")}
    }
    if(confirm)AlertDialog(onDismissRequest={confirm=false},title={Text("Подтвердить CSV импорт?")},text={Text("Выбранные строки будут записаны на сервер. Уже примененные операции не создаются повторно.")},confirmButton={TextButton(enabled=enabled,onClick={confirm=false;csv.confirm()}){Text("Подтвердить CSV импорт")}},dismissButton={TextButton(onClick={confirm=false}){Text("Отмена")}})
    var from by rememberSaveable{mutableStateOf("${java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)}-01-01T00:00:00Z")};var to by rememberSaveable{mutableStateOf("${java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)+1}-01-01T00:00:00Z")}
    Text("Экспорт CSV: UTC, конец периода не включается. Включает категории, теги и исходные идентификаторы. Для обратного импорта используйте RFC3339. Исходные идентификаторы сопоставляются с существующими операциями и сохраняют их изменения; создание новых сложных операций из внешних выписок требует ручного разбора.")
    OutlinedTextField(from,{from=it},label={Text("CSV экспорт от UTC")},enabled=enabled);OutlinedTextField(to,{to=it},label={Text("CSV экспорт до UTC")},enabled=enabled)
    Button(enabled=enabled&&account.isNotEmpty(),onClick={csv.download(from,to,account)}){Text("Сохранить экспорт CSV")}
}
