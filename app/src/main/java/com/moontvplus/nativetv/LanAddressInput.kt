package com.moontvplus.nativetv

import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Collections
import kotlin.concurrent.thread

/** A short lived, token gated LAN page for entering the server URL on a phone. */
class LanAddressInput(private val onAddress: (String) -> Unit) {
    private var server: ServerSocket? = null
    private val main = Handler(Looper.getMainLooper())
    private val token = ByteArray(20).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
    val url: String? get() {
        val ip = localIp() ?: return null
        val port = server?.localPort ?: return null
        return "http://$ip:$port/?token=$token"
    }

    fun start() {
        if (server != null) return
        server = ServerSocket(0)
        thread(name = "moon-address-input", isDaemon = true) {
            while (server?.isClosed == false) {
                try { server?.accept()?.let { handle(it) } } catch (_: Exception) { break }
            }
        }
    }

    fun stop() { server?.close(); server = null }

    private fun handle(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 5000
            val input = client.getInputStream().bufferedReader()
            val request = input.readLine() ?: return
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = input.readLine() ?: return
                if (line.isBlank()) break
                val separator = line.indexOf(':')
                if (separator > 0) headers[line.substring(0, separator).lowercase()] = line.substring(separator + 1).trim()
            }
            val valid = request.contains("token=$token")
            if (!valid) return respond(client, 403, "<h1>配对已失效</h1>")
            if (request.startsWith("POST ")) {
                val length = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 2048) ?: 0
                val chars = CharArray(length)
                var read = 0
                while (read < length) { val n = input.read(chars, read, length - read); if (n < 0) break; read += n }
                val form = String(chars, 0, read)
                val encoded = form.split('&').firstOrNull { it.startsWith("url=") }?.substringAfter('=') ?: ""
                val address = URLDecoder.decode(encoded, "UTF-8").trim()
                if (!address.startsWith("https://") && !address.startsWith("http://")) return respond(client, 400, "<h1>请输入 http:// 或 https:// 地址</h1>")
                main.post { onAddress(address) }
                respond(client, 200, "<h1>已发送到电视</h1><p>请在电视上确认连接。</p>")
                return
            }
            respond(client, 200, """<!doctype html><html lang="zh-CN"><meta name="viewport" content="width=device-width, initial-scale=1"><meta charset="utf-8"><title>设置电视服务地址</title><body style="font:18px system-ui;background:#101522;color:white;padding:24px"><h1>设置电视服务地址</h1><p>在同一局域网内输入你的 MoonTVPlus 地址。</p><form method="post" action="/?token=$token"><input name="url" type="url" required placeholder="https://example.com" style="font-size:18px;padding:12px;width:90%"><p><button style="font-size:18px;padding:12px">发送到电视</button></p></form></body></html>""")
        }
    }

    private fun respond(socket: Socket, status: Int, html: String) {
        val body = html.toByteArray(StandardCharsets.UTF_8)
        val header = "HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().write(header.toByteArray(StandardCharsets.UTF_8) + body)
    }

    private fun localIp(): String? = try {
        Collections.list(NetworkInterface.getNetworkInterfaces()).asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { Collections.list(it.inetAddresses).asSequence() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    } catch (_: Exception) { null }
}
