package com.capital.accounting

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.capital.accounting.data.ConnectionSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = (application as CapitalApplication).database.settings()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                var origin by remember { mutableStateOf("") }
                var message by remember { mutableStateOf("") }
                var ready by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                LaunchedEffect(Unit) { origin = settings.get()?.origin ?: ""; ready = true }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Capital", style = MaterialTheme.typography.headlineLarge)
                        Text("Ручной учет финансов")
                        OutlinedTextField(origin, { origin = it; message = "" }, label = { Text("Адрес сервера") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = ready)
                        Button(enabled = ready, onClick = {
                            scope.launch {
                                ready = false
                                try {
                                    val normalized = normalizedOrigin(origin)
                                    settings.save(ConnectionSettings(origin = normalized))
                                    origin = normalized
                                    message = "Адрес сохранен"
                                } catch (e: CancellationException) { throw e }
                                catch (_: Exception) { message = "Не удалось сохранить адрес. Проверьте HTTPS-адрес сервера." }
                                finally { ready = true }
                            }
                        }) { Text("Сохранить адрес") }
                        if (message.isNotEmpty()) Text(message)
                    }
                }
            }
        }
    }
}
