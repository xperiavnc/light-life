package com.inonvation.lightlife.data

import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.Response
import java.security.MessageDigest

class HeaderInterceptor(
    private val tokenProvider: () -> String?,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val timestamp = System.currentTimeMillis().toString()
        val path = request.url.encodedPath
        val isLoginApi = path.startsWith("/common/") || path.startsWith("/user/reg")
        val channel = if (isLoginApi) ApiConfig.LOGIN_CHANNEL else ApiConfig.API_CHANNEL
        val token = tokenProvider()?.takeIf { it.isNotBlank() }.orEmpty()

        val builder = request.newBuilder()
            .header("Version", ApiConfig.VERSION)
            .header("channel", channel)
            .header("phoneBrand", ApiConfig.PHONE_BRAND)
            .header("User-Agent", ApiConfig.USER_AGENT)
            .header("Content-Type", ApiConfig.CONTENT_TYPE)
            .header("timestamp", timestamp)
            .header("Host", "userapi.qiekj.com")
            .header("Connection", "Keep-Alive")
            .header("Accept-Encoding", "gzip")
            .header("Authorization", token)
            .header("sign", sign(timestamp, path, token, channel))

        if (token.isNotEmpty()) {
            builder.header("token", token)
        }

        // The official client appends the cached token to form requests.
        // Avoid duplicate insertion when the caller already supplied it.
        if (token.isNotEmpty() && request.body is FormBody) {
            val body = request.body as FormBody
            if ((0 until body.size).none { body.name(it) == "token" }) {
                val form = FormBody.Builder()
                for (i in 0 until body.size) {
                    form.add(body.name(i), body.value(i))
                }
                form.add("token", token)
                builder.method(request.method, form.build())
            }
        }

        return chain.proceed(builder.build())
    }

    private fun sign(timestamp: String, path: String, token: String, channel: String): String {
        val raw =
            "appSecret=${ApiConfig.ANDROID_SECRET}&channel=$channel&timestamp=$timestamp&token=$token&version=${ApiConfig.VERSION}$path"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
