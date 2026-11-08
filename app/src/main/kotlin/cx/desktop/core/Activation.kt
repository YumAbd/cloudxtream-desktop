package cx.desktop.core

import com.sun.jna.platform.win32.Crypt32Util
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import java.util.Base64
import javax.net.ssl.SSLException

/**
 * CloudXtream activation, the same protocol as the Android app (Cloudflare Worker, protocol v1):
 * this PC has its own RSA key; the PIN registers its public key on the license, and each start
 * proves possession of the private key. On Windows the private key is encrypted with DPAPI, so the
 * file is useless on another PC or user account.
 */
object Activation {
    const val ORIGIN = "https://cloudxpanel.infopc.xyz"
    private const val SCHEME = "rsa-pkcs1-sha256"
    private const val MIN_LEASE_S = 30L
    private const val MAX_LEASE_S = 600L
    private val keyFile = File(AppDirs.root, "device.key")
    private val publicFile = File(AppDirs.root, "device.pub")
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER).build()
    private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")

    /** A device in the "replace a device" list when the license is full. */
    data class Device(val id: String, val label: String, val lastSeenMs: Long)

    sealed interface Result {
        data class Authorized(val displayName: String, val licenseExpiresMs: Long?, val leaseMs: Long) : Result
        /** [code] is the server's error code (pin_expired, device_limit_reached...), or a connection problem. */
        data class Refused(val code: String, val message: String, val devices: List<Device> = emptyList()) : Result
    }

    val isActivated: Boolean get() = Settings.string("device_id") != null

    fun forgetDevice() {
        Settings.set("device_id", null)
    }

    suspend fun activate(pin: String, replaceDeviceId: String? = null): Result = withContext(Dispatchers.IO) {
        try {
            val challenge = post("/api/device/challenge", JSONObject().put("purpose", "activate"))
            val challengeId = challenge.getString("challenge_id")
            val nonce = challenge.getString("nonce")
            val keys = keys()
            val body = JSONObject()
                .put("challenge_id", challengeId)
                .put("public_key_spki", b64url(keys.public.encoded))
                .put("signature", sign(keys.private, "CX-ACTIVATE\n$challengeId\n$nonce"))
                .put("signature_scheme", SCHEME)
                .put("device_label", deviceLabel())
                .put("pin", pin.trim().uppercase())
            if (replaceDeviceId != null) body.put("replace_device_id", replaceDeviceId)
            val response = post("/api/device/activate", body)
            Settings.set("device_id", response.getString("device_id"))
            verify()
        } catch (e: Exception) {
            refused(e)
        }
    }

    /** Called at start and before the lease ends; anything but a signed "authorized" answer fails closed. */
    suspend fun verify(): Result = withContext(Dispatchers.IO) {
        try {
            val deviceId = Settings.string("device_id") ?: return@withContext Result.Refused("not_activated", "")
            val (challengeId, signature) = signedChallenge(deviceId)
            val valid = post("/api/device/verify", JSONObject()
                .put("device_id", deviceId).put("challenge_id", challengeId)
                .put("signature", signature).put("signature_scheme", SCHEME))
            if (!valid.optBoolean("authorized", false) || valid.optString("device_id") != deviceId) {
                return@withContext Result.Refused("not_authorized", message("not_authorized"))
            }
            val expires = if (valid.isNull("license_expires_ms")) null else valid.optLong("license_expires_ms").takeIf { it > 0 }
            Settings.set("license_expires_ms", expires)
            val lease = valid.optLong("revalidate_in", 60L).coerceIn(MIN_LEASE_S, MAX_LEASE_S) * 1000L - 5_000L
            Result.Authorized(valid.optString("display_name", "").trim().take(40), expires, lease)
        } catch (e: Exception) {
            refused(e)
        }
    }

    /** "Report a problem" from the player, signed like an online check. */
    suspend fun report(title: String, source: String, detail: String, appVersion: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val deviceId = Settings.string("device_id") ?: return@withContext false
            val (challengeId, signature) = signedChallenge(deviceId)
            post("/api/device/report", JSONObject()
                .put("device_id", deviceId).put("challenge_id", challengeId)
                .put("signature", signature).put("signature_scheme", SCHEME)
                .put("title", title.take(200)).put("source", source.take(200))
                .put("detail", detail.take(500)).put("app_version", appVersion.take(20)))
                .optBoolean("ok", false)
        } catch (e: Exception) {
            false
        }
    }

    private fun signedChallenge(deviceId: String): Pair<String, String> {
        val challenge = post("/api/device/challenge", JSONObject().put("purpose", "verify").put("device_id", deviceId))
        val challengeId = challenge.getString("challenge_id")
        val nonce = challenge.getString("nonce")
        return challengeId to sign(keys().private, "CX-VERIFY\n$deviceId\n$challengeId\n$nonce")
    }

    private class ApiProblem(val status: Int, val code: String, val body: JSONObject) : IOException(code)

    private fun post(path: String, data: JSONObject): JSONObject {
        val request = HttpRequest.newBuilder(URI.create(ORIGIN + path))
            .timeout(Duration.ofSeconds(15))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("User-Agent", "CloudXtream-Desktop/${AppInfo.version}")
            .POST(HttpRequest.BodyPublishers.ofString(data.toString()))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
        val bytes = response.body()
        if (bytes.size > 16_384) throw IOException("response_too_large")
        val body = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrElse { JSONObject() }
        if (response.statusCode() !in 200..299) {
            val code = body.optString("error", "unknown").take(60).takeIf { Regex("^[a-z0-9_]+$").matches(it) } ?: "unknown"
            throw ApiProblem(response.statusCode(), code, body)
        }
        return body
    }

    private fun refused(e: Exception): Result.Refused {
        val code = when (e) {
            is ApiProblem -> e.code
            is UnknownHostException -> "DNS_UNREACHABLE"
            is HttpConnectTimeoutException, is HttpTimeoutException -> "NETWORK_TIMEOUT"
            is ConnectException -> "CONNECTION_FAILED"
            is SSLException -> "TLS_ERROR"
            else -> if (e.cause is UnknownHostException) "DNS_UNREACHABLE" else e.javaClass.simpleName
        }
        val devices = (e as? ApiProblem)?.body?.optJSONArray("devices")?.let { list ->
            (0 until list.length()).mapNotNull { i ->
                val d = list.optJSONObject(i) ?: return@mapNotNull null
                Device(d.optString("id"), d.optString("label").ifBlank { "Peranti tanpa nama" }, d.optLong("last_seen_ms"))
            }
        }.orEmpty()
        return Result.Refused(code, message(code), devices)
    }

    fun message(code: String): String = when (code) {
        "pin_not_found", "invalid_pin" -> "PIN tidak sah. Semak semula 6 aksara PIN, atau minta PIN daripada admin CloudXtream."
        "pin_expired" -> "PIN ini telah TAMAT TEMPOH. Sila hubungi admin CloudXtream untuk mendapatkan PIN baharu."
        "pin_replaced" -> "PIN ini telah digantikan dengan PIN yang lebih baharu. Sila guna PIN terkini daripada admin CloudXtream."
        "license_inactive" -> "Lesen anda sedang DIGANTUNG atau DIBATALKAN. Sila hubungi admin CloudXtream."
        "license_expired" -> "Lesen anda telah TAMAT TEMPOH. Hubungi admin CloudXtream untuk menyambungnya."
        "device_revoked" -> "PC ini telah DIKELUARKAN daripada lesen (mungkin diganti dengan peranti lain). Masukkan PIN baharu daripada admin CloudXtream."
        "device_limit_reached" -> "Had peranti lesen ini sudah PENUH. Pilih peranti lama untuk diganti dengan PC ini, atau minta admin CloudXtream menambah had."
        "device_not_found" -> "Peranti yang dipilih sudah tiada dalam lesen. Cuba aktifkan semula."
        "too_many_attempts" -> "Terlalu banyak percubaan. Tunggu 15 minit dan cuba lagi."
        "not_authorized" -> "PC ini tidak lagi dibenarkan. Masukkan PIN baharu daripada admin CloudXtream."
        "DNS_UNREACHABLE" -> "Server CloudXtream tidak dapat dicari. Semak sambungan Internet, matikan VPN/DNS peribadi, atau cuba rangkaian lain."
        "NETWORK_TIMEOUT", "CONNECTION_FAILED" -> "Tiada sambungan ke server CloudXtream. Semak Internet dan cuba lagi."
        "TLS_ERROR" -> "Sambungan selamat ke server CloudXtream GAGAL. Semak tarikh & masa PC, kemudian cuba lagi."
        else -> "Pengaktifan gagal ($code). Cuba lagi, atau hubungi admin CloudXtream."
    }

    /** Errors that mean this PC must be activated again (the others are temporary). */
    fun needsNewPin(code: String) = code in setOf("device_revoked", "not_authorized", "device_not_found", "not_activated")

    private fun deviceLabel(): String {
        val host = System.getenv("COMPUTERNAME") ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()
        return ("Windows PC" + (host?.let { " ($it)" } ?: "")).take(60)
    }

    @Synchronized
    private fun keys(): KeyPair {
        if (keyFile.exists() && publicFile.exists()) {
            runCatching {
                val factory = KeyFactory.getInstance("RSA")
                val private = factory.generatePrivate(PKCS8EncodedKeySpec(unprotect(keyFile.readBytes())))
                val public = factory.generatePublic(X509EncodedKeySpec(publicFile.readBytes()))
                return KeyPair(public, private)
            }
        }
        // A new key means a new device: the old registration (if any) can no longer be proven.
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        keyFile.writeAtomically(protect(pair.private.encoded))
        publicFile.writeAtomically(pair.public.encoded)
        return pair
    }

    private fun protect(bytes: ByteArray): ByteArray = if (windows) Crypt32Util.cryptProtectData(bytes) else bytes
    private fun unprotect(bytes: ByteArray): ByteArray = if (windows) Crypt32Util.cryptUnprotectData(bytes) else bytes

    private fun sign(key: PrivateKey, message: String): String =
        b64url(Signature.getInstance("SHA256withRSA").apply { initSign(key); update(message.toByteArray()) }.sign())

    private fun b64url(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
