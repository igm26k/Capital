package com.capital.accounting.finance

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.capital.accounting.api.*
import com.capital.accounting.auth.AuthViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PrivacyPanel(model: AuthViewModel) {
    val state=model.state;val auth=state.auth?:return
    if(LocalFinancialSection.current.value!="more")return
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var expanded by remember{mutableStateOf(false)};var password by remember{mutableStateOf("")};var confirmation by remember{mutableStateOf("")};var deleting by remember{mutableStateOf(false)};var busy by remember{mutableStateOf(false)};var message by remember{mutableStateOf("")}
    val saver=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->
        if(uri!=null)scope.launch{busy=true;try{
            val saved=auth.credential(state.origin)
            val response=ApiClient(saved.origin).request("/me/export",session=saved,maxResponseBytes=16*1024*1024)
            withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri)!!.use{it.write(response.toByteArray())}}
            message="Экспорт сохранен. Пароли и токены не включены."
        }catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e;if(e is ApiFailure&&e.status==401)model.sessionUnauthorized(auth.session.id);message="Не удалось сохранить экспорт."}finally{busy=false}}
    }
    TextButton(onClick={expanded=!expanded}){Text("Экспорт и удаление профиля")}
    if(!expanded)return
    val enabled=!state.busy&&!state.offline&&state.persisted&&!busy
    if(message.isNotEmpty())Text(message)
    Text("JSON экспорт содержит доступные финансовые пространства, удаленные записи и настройки. Файл содержит личные данные; пароли и токены в него не входят.")
    Button(enabled=enabled,onClick={saver.launch("accounting-user-data.json")}){Text("Сохранить все финансовые данные JSON")}
    Text("Удаление профиля удаляет личные пространства, локальные данные текущего пространства и отзывает устройства. Несинхронизированные изменения будут потеряны. Резервные копии удаляются по политике хранения.")
    Button(enabled=enabled,onClick={deleting=true}){Text("Удалить профиль и личные данные")}
    if(deleting){
        OutlinedTextField(password,{password=it},label={Text("Пароль для удаления профиля")},visualTransformation=PasswordVisualTransformation(),enabled=enabled)
        OutlinedTextField(confirmation,{confirmation=it},label={Text("Введите DELETE для подтверждения")},enabled=enabled)
        Button(enabled=enabled&&confirmation=="DELETE"&&password.isNotEmpty(),onClick={val secret=password;password="";confirmation="";deleting=false;model.deleteUserData(secret,"DELETE")}){Text("Подтвердить удаление профиля")}
        TextButton(onClick={deleting=false;password="";confirmation=""}){Text("Отмена удаления профиля")}
    }
}
