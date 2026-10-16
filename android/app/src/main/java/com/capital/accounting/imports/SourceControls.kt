package com.capital.accounting.imports

import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.capital.accounting.api.SourceEvent
import com.capital.accounting.api.Transaction
import com.capital.accounting.auth.AuthViewModel
import com.capital.accounting.finance.Money

private data class SourceChoice(val event:SourceEvent,val decision:String,val transaction:Transaction?)
@Composable
fun SourceControls(source:SourcesModel,model:AuthViewModel) {
    val state=model.state
    var choice by remember {mutableStateOf<SourceChoice?>(null)}
    val enabled=!state.busy&&!source.busy&&source.pending==null&&!state.offline&&state.persisted&&!state.financeBlocked
    Text("Предложения на сервере",style=MaterialTheme.typography.titleMedium)
    Text("После отправки предложение можно связать с существующей операцией или явно создать предварительную. Подтверждение не учитывает сумму второй раз; отмена удаляет предварительную запись.")
    if(source.message.isNotEmpty())Text(source.message)
    if(source.busy)CircularProgressIndicator()
    Button(enabled=!source.busy&&!state.offline,onClick=source::load){Text("Обновить серверные предложения")}
    source.pending?.let {pending->
        Text("Сохранен запрос предложения. Новый ключ не создается до выяснения результата.")
        if(pending.rejected)TextButton(enabled=!source.busy,onClick=source::dismissRejected){Text("Закрыть отклоненный запрос предложения")}
        else Button(enabled=!source.busy&&!state.offline,onClick=source::retry){Text("Повторить сохраненный запрос предложения")}
    }
    if(source.hasMore)Text("Показаны 100 последних серверных предложений. Ранее отправленные события сохраняются на сервере.")
    source.items.filter {it.state!="dismissed"}.forEach {event->
        Text("${event.merchant.ifEmpty {"Без продавца"}} · ${Money.display(event.amount_minor,event.currency)}")
        Text("${state.accounts.find {it.id==event.account_id}?.name?:"Счет недоступен"} · ${if(event.kind=="expense")"Расход" else "Доход"} · ${event.observed_at}")
        val unknown=event.error_code in setOf("action_result_expired","idempotency_conflict","sync_generation_conflict")
        if(event.state=="queued")Text("Ожидает обработки")
        if(event.state=="failed")Text(sourceReason(event.error_code))
        if(event.state=="review"||(event.state=="failed"&&event.transaction_id==null&&!unknown)) {
            Button(enabled=enabled,onClick={choice=SourceChoice(event,"create_pending",null)}){Text("Создать предварительную операцию")}
            TextButton(enabled=enabled,onClick={source.matches(event)}){Text("Выбрать существующую операцию")}
            TextButton(enabled=enabled,onClick={choice=SourceChoice(event,"dismiss",null)}){Text("Исключить серверное предложение")}
        }
        if(event.transaction_id!=null) {
            TextButton(enabled=!source.busy&&!state.offline,onClick={source.target(event)}){Text("Проверить связанную операцию")}
            if(source.targets.containsKey(event.id)) {
                val transaction=source.targets[event.id]
                if(transaction==null)Text("Связанная операция удалена или недоступна. Новая автоматически не создается.")
                else {
                    Text("${if(transaction.status=="pending")"Предварительная" else "Подтвержденная"}: ${transaction.payee} · ${transaction.note}")
                    if(transaction.status=="pending"&&(event.state=="applied"||event.state=="failed"&&!unknown)) {
                        Button(enabled=enabled,onClick={choice=SourceChoice(event,"confirm_posted",transaction)}){Text("Подтвердить предварительную операцию")}
                        TextButton(enabled=enabled,onClick={choice=SourceChoice(event,"cancel_pending",transaction)}){Text("Отменить предварительную операцию")}
                    }
                }
            }
        }
    }
    source.linking?.let {event->
        Text("Связь для ${event.merchant}: выберите операцию того же счета, валюты и суммы. Сама операция не изменится.")
        source.candidates.forEach {transaction->TextButton(enabled=enabled,onClick={choice=SourceChoice(event,"link",transaction);source.closeMatches()}){Text("${transaction.occurred_at} · ${transaction.payee.ifEmpty {"Без получателя"}} · ${Money.display(event.amount_minor,event.currency)}")}}
        if(source.candidates.isEmpty())Text("Среди загруженных операций подходящих нет.")
        if(source.candidateCursor!=null)TextButton(enabled=!source.busy,onClick={source.matches(event,true)}){Text("Загрузить следующие операции для связи")}
        TextButton(enabled=!source.busy,onClick=source::closeMatches){Text("Закрыть выбор операции")}
    }
    choice?.let {value->AlertDialog(onDismissRequest={choice=null},title={Text(when(value.decision){"create_pending"->"Создать предварительную операцию?";"link"->"Связать с выбранной операцией?";"confirm_posted"->"Подтвердить предварительную операцию?";"cancel_pending"->"Отменить предварительную операцию?";else->"Исключить предложение?"})},text={Text("${Money.display(value.event.amount_minor,value.event.currency)} · ${value.event.merchant}. Проверьте счет и историю. Пользовательские категории, теги и заметки не перезаписываются.")},confirmButton={TextButton(enabled=enabled,onClick={choice=null;source.decide(value.event,value.decision,value.transaction)}){Text("Подтвердить действие предложения")}},dismissButton={TextButton(onClick={choice=null}){Text("Отмена действия предложения")}})}
}
