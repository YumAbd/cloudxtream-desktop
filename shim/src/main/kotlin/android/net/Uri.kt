package android.net

import java.net.URLDecoder
import java.net.URLEncoder

/** The parts of android.net.Uri that extensions use; parsing is as lenient as Android's. */
class Uri private constructor(private val text: String) {
    private val fragmentStart = text.indexOf('#')
    private val beforeFragment = if (fragmentStart >= 0) text.substring(0, fragmentStart) else text
    private val queryStart = beforeFragment.indexOf('?')
    private val beforeQuery = if (queryStart >= 0) beforeFragment.substring(0, queryStart) else beforeFragment
    private val schemeEnd = beforeQuery.indexOf(':').takeIf { it > 0 && beforeQuery.substring(0, it).none { c -> c == '/' } } ?: -1
    private val rest = beforeQuery.substring(schemeEnd + 1)
    private val authority: String? = if (rest.startsWith("//")) rest.substring(2).substringBefore('/') else null
    private val encodedPath: String = if (authority != null) rest.substring(2 + authority.length) else rest

    fun getScheme(): String? = if (schemeEnd > 0) text.substring(0, schemeEnd) else null
    fun getEncodedAuthority(): String? = authority
    fun getAuthority(): String? = authority?.let { decodePart(it) }
    fun getHost(): String? = authority?.substringAfterLast('@')?.let { if (it.startsWith("[")) it.substringBefore(']') + "]" else it.substringBefore(':') }
    fun getPort(): Int = authority?.substringAfterLast('@')?.substringAfterLast(']')?.substringAfter(':', "")?.toIntOrNull() ?: -1
    fun getEncodedPath(): String = encodedPath
    fun getPath(): String = decodePart(encodedPath)
    fun getPathSegments(): List<String> = encodedPath.split('/').filter { it.isNotEmpty() }.map { decodePart(it) }
    fun getLastPathSegment(): String? = getPathSegments().lastOrNull()
    fun getEncodedQuery(): String? = if (queryStart >= 0) beforeFragment.substring(queryStart + 1) else null
    fun getQuery(): String? = getEncodedQuery()?.let { decodePart(it) }
    fun getFragment(): String? = if (fragmentStart >= 0) decodePart(text.substring(fragmentStart + 1)) else null
    fun isAbsolute(): Boolean = schemeEnd > 0
    fun isRelative(): Boolean = !isAbsolute()

    private fun pairs(): List<Pair<String, String>> = getEncodedQuery().orEmpty().split('&').filter { it.isNotEmpty() }.map {
        decodeQuery(it.substringBefore('=')) to decodeQuery(it.substringAfter('=', ""))
    }
    fun getQueryParameterNames(): Set<String> = pairs().mapTo(LinkedHashSet()) { it.first }
    fun getQueryParameters(key: String): List<String> = pairs().filter { it.first == key }.map { it.second }
    fun getQueryParameter(key: String): String? = pairs().firstOrNull { it.first == key }?.second
    fun getBooleanQueryParameter(key: String, defaultValue: Boolean): Boolean =
        getQueryParameter(key)?.lowercase()?.let { it != "false" && it != "0" } ?: defaultValue

    override fun toString(): String = text
    override fun equals(other: Any?): Boolean = other is Uri && other.text == text
    override fun hashCode(): Int = text.hashCode()

    companion object {
        @JvmField val EMPTY = Uri("")
        @JvmStatic fun parse(uriString: String): Uri = Uri(uriString)
        @JvmStatic fun encode(s: String?): String? = encode(s, null)
        @JvmStatic fun encode(s: String?, allow: String?): String? = s?.let {
            buildString {
                for (ch in it) {
                    if (ch.isLetterOrDigit() && ch.code < 128 || ch in "_-!.~'()*" || (allow != null && ch in allow)) append(ch)
                    else append(URLEncoder.encode(ch.toString(), Charsets.UTF_8).replace("+", "%20"))
                }
            }
        }
        @JvmStatic fun decode(s: String?): String? = s?.let { decodeRaw(it, false) }
        private fun decodePart(s: String): String = decodeRaw(s, false)
        private fun decodeQuery(s: String): String = decodeRaw(s, true)
        private fun decodeRaw(s: String, plusIsSpace: Boolean): String = try {
            URLDecoder.decode(if (plusIsSpace) s else s.replace("+", "%2B"), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            s
        }
    }
}
