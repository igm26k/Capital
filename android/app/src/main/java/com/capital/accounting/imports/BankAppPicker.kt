package com.capital.accounting.imports

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class LaunchableApp(val packageName:String,val label:String)
@Composable
fun BankAppPicker(packageName:String,enabled:Boolean,selected:(String)->Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var open by remember {mutableStateOf(false)};var busy by remember {mutableStateOf(false)}
    var apps by remember {mutableStateOf<List<LaunchableApp>>(emptyList())};var query by remember {mutableStateOf("")};var message by remember {mutableStateOf("")}
    var manual by remember {mutableStateOf(false)}
    TextButton(enabled=enabled&&!busy,onClick={open=true;busy=true;scope.launch {
        try {
            val loaded=withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                val visible=context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0)
                visible.map {LaunchableApp(it.activityInfo.packageName,it.loadLabel(context.packageManager).toString().take(100))}.distinctBy {it.packageName}.filter {it.packageName!=context.packageName}.sortedBy {it.label.lowercase()}
            };apps=loaded
        }catch(e:Exception){if(e is CancellationException)throw e;message="Не удалось показать приложения. Можно указать пакет вручную."}finally{busy=false}
    }}){Text("Выбрать банковское приложение")}
    if(packageName.isNotEmpty())Text("Выбрано: ${apps.find {it.packageName==packageName}?.label?:packageName}")
    if(message.isNotEmpty())Text(message)
    TextButton(enabled=enabled,onClick={manual=!manual}){Text("Указать пакет приложения вручную")}
    if(manual)OutlinedTextField(packageName,{selected(it.take(200))},enabled=enabled,label={Text("Пакет банковского приложения")})
    if(open)AlertDialog(onDismissRequest={open=false},title={Text("Приложение для уведомлений")},text={Column(Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState())){
        OutlinedTextField(query,{query=it},label={Text("Найти установленное приложение")})
        if(busy)CircularProgressIndicator()
        apps.filter {it.label.contains(query,true)||it.packageName.contains(query,true)}.take(100).forEach {app->TextButton(enabled=enabled,onClick={selected(app.packageName);open=false}){Column {Text(app.label);Text(app.packageName,style=MaterialTheme.typography.bodySmall)}}}
        if(!busy&&apps.isEmpty())Text("Нет видимых приложений. При необходимости укажите пакет вручную.")
    }},confirmButton={TextButton(onClick={open=false}){Text("Закрыть список приложений")}})
}
