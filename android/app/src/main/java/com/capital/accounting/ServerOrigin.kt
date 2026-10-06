package com.capital.accounting

import java.net.URI

fun normalizedOrigin(value: String): String {
    val uri = URI(value.trim())
    require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
        uri.query == null && uri.fragment == null && uri.path in listOf("", "/") &&
        (uri.port == -1 || uri.port in 1..65535)) { "Введите HTTPS-адрес сервера без пути и пароля." }
    return uri.toString().trimEnd('/')
}

