package app.televault.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.random.Random

data class ApiKeys(val id: Int, val hash: String)

/**
 * Signs in to https://my.telegram.org with the user's own phone number + the code Telegram sends,
 * then reads (or creates) the account's API application and returns its api_id / api_hash.
 * Those are what TDLib needs before it can log in. No extra libraries: plain HttpURLConnection.
 *
 * my.telegram.org is a website, not a documented API, so its HTML can change; the manual-entry
 * fallback in the setup screen stays available for that case.
 */
class MyTelegramSession(private val phone: String) {
    private val base = "https://my.telegram.org"
    private val cookies = LinkedHashMap<String, String>()
    private var randomHash = ""

    /** Step 1: Telegram sends a login code to the account (in the Telegram app, or by SMS). */
    suspend fun sendCode() = withContext(Dispatchers.IO) {
        val (_, body) = request("POST", "/auth/send_password", mapOf("phone" to phone))
        randomHash = try {
            JSONObject(body).getString("random_hash")
        } catch (e: Exception) {
            throw Exception(cleanError(body, "Telegram didn't send a code. Check the number (with country code) and try again."))
        }
    }

    /** Step 2: enter that code; returns the API keys (creating the API app if the account has none yet). */
    suspend fun login(code: String): ApiKeys = withContext(Dispatchers.IO) {
        if (randomHash.isEmpty()) throw Exception("Request a code first")
        val (_, body) = request(
            "POST", "/auth/login",
            mapOf("phone" to phone, "random_hash" to randomHash, "password" to code.trim()),
        )
        if (!body.trim().equals("true", ignoreCase = true) || !cookies.containsKey("stel_token")) {
            throw Exception(cleanError(body, "That code wasn't accepted. Check it and try again."))
        }

        var html = request("GET", "/apps", null).second
        parseKeys(html)?.let { return@withContext it }

        // No API app yet: create one.
        val formHash = Regex("""name="hash"[^>]*value="([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: Regex("""value="([^"]+)"[^>]*name="hash"""").find(html)?.groupValues?.get(1)
            ?: throw Exception("Couldn't read the my.telegram.org page. Use “Enter keys manually” instead.")
        val create = request(
            "POST", "/apps/create",
            mapOf(
                "hash" to formHash,
                "app_title" to "TeleVault",
                "app_shortname" to "televault" + Random.nextInt(10000, 99999),
                "app_url" to "",
                "app_platform" to "android",
                "app_desc" to "",
            ),
        ).second
        if (create.isNotBlank() && !create.trim().equals("true", ignoreCase = true) && !create.trimStart().startsWith("<")) {
            throw Exception(cleanError(create, "Telegram refused to create the API app."))
        }
        html = request("GET", "/apps", null).second
        parseKeys(html) ?: throw Exception("Couldn't find the API keys on my.telegram.org. Use “Enter keys manually” instead.")
    }

    private fun parseKeys(html: String): ApiKeys? {
        val opts = setOf(RegexOption.IGNORE_CASE)
        val idText = Regex("""api_id:\s*</label>\s*<div[^>]*>\s*<span[^>]*>\s*<strong>\s*(\d+)\s*</strong>""", opts).find(html)?.groupValues?.get(1)
            ?: Regex("""<strong>\s*(\d{5,})\s*</strong>""").find(html)?.groupValues?.get(1)
        val hash = Regex("""api_hash:\s*</label>\s*<div[^>]*>\s*<span[^>]*>\s*([0-9a-f]{32})\s*</span>""", opts).find(html)?.groupValues?.get(1)
            ?: Regex(""">\s*([0-9a-f]{32})\s*<""", opts).find(html)?.groupValues?.get(1)
        val id = idText?.toIntOrNull() ?: return null
        if (hash == null || id <= 0) return null
        return ApiKeys(id, hash)
    }

    private fun cleanError(body: String, fallback: String): String {
        var t = body.trim()
        if (t.startsWith("{")) {
            t = try { JSONObject(t).optString("text", "") } catch (e: Exception) { t }
        }
        if (t.isEmpty() || t.startsWith("<") || t.length > 200) return fallback
        return t
    }

    private fun request(method: String, path: String, form: Map<String, String>?): Pair<Int, String> {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20000
            conn.readTimeout = 30000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            conn.setRequestProperty("Origin", base)
            conn.setRequestProperty("Referer", "$base/auth")
            conn.setRequestProperty("X-Requested-With", "XMLHttpRequest")
            if (cookies.isNotEmpty()) conn.setRequestProperty("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            if (form != null) {
                val data = form.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }.toByteArray()
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                conn.outputStream.use { it.write(data) }
            }
            val code = conn.responseCode
            conn.headerFields["Set-Cookie"]?.forEach { line ->
                val kv = line.substringBefore(';')
                val name = kv.substringBefore('=').trim()
                val value = kv.substringAfter('=', "").trim()
                if (name.isNotEmpty()) cookies[name] = value
            }
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return code to text
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
