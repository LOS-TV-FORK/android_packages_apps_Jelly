/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.LinkedHashMap

/**
 * Content blocking for web pages.
 *
 * Downloads public filter lists (EasyList, EasyPrivacy, AdGuard RU),
 * parses their URL filter syntax and blocks matching subresource requests
 * in [android.webkit.WebViewClient.shouldInterceptRequest]. Element hiding
 * rules (`##`) are applied as a style injected early during page load.
 *
 * Supported subset of the Adblock Plus syntax, which covers the bulk of the
 * shipped lists:
 *  - network rules anchored with `||hostname` with optional literal path parts,
 *  - `@@` exception rules, `$important` allow rules,
 *  - resource type options (image, script, stylesheet, subdocument, ...) and
 *    `third-party` / `~third-party` party options,
 *  - generic and `||hostname^` scoped `##selector` cosmetic rules.
 * Ignored: `$domain=`, regex/adg-style patterns, `$badfilter`, scriptlets,
 * `$$` html rules. First-party detection uses a two-label eTLD+1 heuristic,
 * so multi-label public suffixes (e.g. co.uk) may be misclassified.
 */
object AdBlock {
    private const val TAG = "AdBlock"
    private const val UPDATE_INTERVAL_MS = 1000L * 60 * 60 * 24 * 3
    private const val CONNECT_TIMEOUT_MS = 15000
    private const val READ_TIMEOUT_MS = 30000
    private const val MAX_GENERIC_COSMETIC = 3000
    private const val CACHE_SIZE = 4096

    private const val T_SCRIPT = 1 shl 0
    private const val T_STYLESHEET = 1 shl 1
    private const val T_IMAGE = 1 shl 2
    private const val T_XHR = 1 shl 3
    private const val T_SUBDOC = 1 shl 4
    private const val T_OBJECT = 1 shl 5
    private const val T_FONT = 1 shl 6
    private const val T_MEDIA = 1 shl 7
    private const val T_PING = 1 shl 8
    private const val T_DOC = 1 shl 9
    private const val T_OTHER = 1 shl 10
    private const val T_ALL = (1 shl 11) - 1

    private const val PARTY_ANY = 0
    private const val PARTY_THIRD = 1
    private const val PARTY_FIRST = 2

    private val LISTS = arrayOf(
        "easylist" to "https://easylist.to/easylist/easylist.txt",
        "easyprivacy" to "https://easylist.to/easylist/easyprivacy.txt",
        "adguard_russian" to
            "https://raw.githubusercontent.com/AdguardTeam/FiltersRegistry/master/" +
                "filters/filter_1_Russian/filter.txt",
    )

    private class Rule(
        val exception: Boolean,
        val important: Boolean,
        val host: String,
        val literal: String?,
        val tail: String?,
        val types: Int,
        val party: Int,
    )

    @Volatile
    private var hostRules: Map<String, List<Rule>> = emptyMap()

    @Volatile
    private var cosmeticRules: Map<String, List<String>> = emptyMap()

    @Volatile
    private var genericCosmetic: List<String> = emptyList()

    @Volatile
    var ruleCount = 0
        private set

    private val cache = Collections.synchronizedMap(
        object : LinkedHashMap<String, Boolean>(256, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, Boolean>?
            ): Boolean = size > CACHE_SIZE
        }
    )

    fun enabled(context: Context): Boolean =
        SharedPreferencesExt(context).adBlockEnabled

    fun init(context: Context) {
        CoroutineScope(Dispatchers.IO).launch { loadFromDisk(context) }
    }

    fun ensureFresh(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            val dir = File(context.filesDir, "adblock")
            val stale = LISTS.any { (name, _) ->
                val file = File(dir, "$name.txt")
                !file.exists() ||
                    System.currentTimeMillis() - file.lastModified() > UPDATE_INTERVAL_MS
            }
            if (!stale) return@launch
            var updated = false
            for ((name, url) in LISTS) {
                if (download(dir, name, url)) updated = true
            }
            if (updated) loadFromDisk(context)
        }
    }

    /**
     * Returns true when the subresource [url] must not reach the network.
     * Must not block main frame navigations; callers only pass subresources.
     */
    fun shouldBlock(url: String, pageUrl: String?): Boolean {
        val rules = hostRules
        if (rules.isEmpty()) return false
        if (!url.startsWith("http", true)) return false
        val pageHost = pageUrl?.let { hostOf(it) }
        val key = url + '|' + (pageHost ?: "")
        val cached = synchronized(cache) { cache[key] }
        if (cached != null) return cached
        val result = compute(url.lowercase(), hostOf(url), pageHost, inferType(url), rules)
        synchronized(cache) { cache[key] = result }
        return result
    }

    /**
     * Builds a JS snippet that appends all matching cosmetic rules for the
     * page as a single style element, as early as the DOM allows.
     */
    fun cosmeticScript(pageUrl: String?): String? {
        val host = pageUrl?.let { hostOf(it) } ?: return null
        val rules = cosmeticRules
        val generics = genericCosmetic
        if (rules.isEmpty() && generics.isEmpty()) return null
        val selectors = ArrayList<String>(generics.size + 32)
        var suffix: String? = host
        while (suffix != null) {
            rules[suffix]?.let { selectors.addAll(it) }
            val dot = suffix.indexOf('.')
            suffix = if (dot < 0) null else suffix.substring(dot + 1)
        }
        if (selectors.isEmpty() && generics.isEmpty()) return null
        val css = StringBuilder(8192)
        for (sel in generics) css.append(sel).append("{display:none!important}")
        for (sel in selectors) css.append(sel).append("{display:none!important}")
        if (css.isEmpty()) return null
        val quoted = JSONObject.quote(css.toString())
        return "(function(){var d=document;" +
            "function a(){var s=d.createElement('style');s.textContent=$quoted;" +
            "d.documentElement.appendChild(s);}" +
            "if(d.documentElement){a();}else{" +
            "var o=new MutationObserver(function(){if(d.documentElement){" +
            "o.disconnect();a();}});o.observe(d,{childList:true});}})();"
    }

    private fun compute(
        lowerUrl: String,
        host: String?,
        pageHost: String?,
        type: Int,
        rulesMap: Map<String, List<Rule>>,
    ): Boolean {
        if (host == null || host.isEmpty()) return false
        val third = pageHost == null || eTldPlusOne(host) != eTldPlusOne(pageHost)
        var exceptionMatched = false
        var suffix: String? = host
        while (suffix != null) {
            val rules = rulesMap[suffix]
            if (rules != null) {
                for (rule in rules) {
                    if (rule.host != suffix) continue
                    if (!matches(rule, lowerUrl, type, third)) continue
                    if (rule.exception) exceptionMatched = true
                    else if (rule.important) return true
                }
            }
            val dot = suffix.indexOf('.')
            suffix = if (dot < 0) null else suffix.substring(dot + 1)
        }
        if (exceptionMatched) return false
        suffix = host
        while (suffix != null) {
            val rules = rulesMap[suffix]
            if (rules != null) {
                for (rule in rules) {
                    if (rule.host != suffix) continue
                    if (!rule.exception && matches(rule, lowerUrl, type, third)) {
                        return true
                    }
                }
            }
            val dot = suffix.indexOf('.')
            suffix = if (dot < 0) null else suffix.substring(dot + 1)
        }
        return false
    }

    private fun matches(rule: Rule, lowerUrl: String, type: Int, third: Boolean): Boolean {
        if (rule.types and type == 0) return false
        if (rule.party == PARTY_THIRD && !third) return false
        if (rule.party == PARTY_FIRST && third) return false
        if (rule.literal != null && !lowerUrl.contains(rule.literal)) return false
        if (rule.tail != null && !lowerUrl.contains(rule.tail)) return false
        return true
    }

    private fun loadFromDisk(context: Context) {
        val dir = File(context.filesDir, "adblock")
        val hosts = HashMap<String, MutableList<Rule>>()
        val cosmetic = HashMap<String, MutableList<String>>()
        val generics = ArrayList<String>()
        var count = 0
        for ((name, _) in LISTS) {
            val file = File(dir, "$name.txt")
            if (!file.exists()) continue
            try {
                file.bufferedReader().use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        count += parseLine(line, hosts, cosmetic, generics)
                        line = reader.readLine()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "failed to read $name", e)
            }
        }
        if (count > 0) {
            hostRules = hosts
            cosmeticRules = cosmetic
            genericCosmetic = generics
            ruleCount = count
            Log.i(TAG, "loaded $count rules")
        }
    }

    private fun parseLine(
        raw: String,
        hosts: MutableMap<String, MutableList<Rule>>,
        cosmetic: MutableMap<String, MutableList<String>>,
        generics: MutableList<String>,
    ): Int {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("!") || line.startsWith("[")) return 0
        if (line.contains("$$")) return 0
        val hash = line.indexOf("##")
        if (hash >= 0) {
            if (line.contains("#@#") || line.contains("#@\$#")) return 0
            return parseCosmetic(line, hash, cosmetic, generics)
        }
        var exception = false
        var rest = line
        if (rest.startsWith("@@")) {
            exception = true
            rest = rest.substring(2)
        }
        var pattern = rest
        var options: String? = null
        val dollar = rest.indexOf('$')
        if (dollar > 0 && rest[dollar - 1] != '\\') {
            pattern = rest.substring(0, dollar)
            options = rest.substring(dollar + 1)
        }
        if (pattern.isEmpty()) return 0
        val opts = parseOptions(options) ?: return 0
        if (!pattern.startsWith("||")) return 0
        var end = 2
        while (end < pattern.length && isHostChar(pattern[end])) end++
        if (end <= 2) return 0
        var host = pattern.substring(2, end)
        if (host.endsWith(".")) host = host.dropLast(1)
        if (!host.contains('.')) return 0
        val literalParts = literalParts(pattern.substring(end))
        val rule = Rule(
            exception = exception,
            important = opts.important,
            host = host,
            literal = literalParts.first,
            tail = literalParts.second,
            types = opts.types,
            party = opts.party,
        )
        hosts.getOrPut(host) { ArrayList(2) }.add(rule)
        return 1
    }

    private fun parseCosmetic(
        line: String,
        hash: Int,
        cosmetic: MutableMap<String, MutableList<String>>,
        generics: MutableList<String>,
    ): Int {
        var sel = line.substring(0, hash)
        var css = line.substring(hash + 2)
        val dollar = css.indexOf('$')
        if (dollar >= 0) css = css.substring(0, dollar)
        if (!isSafeSelector(css)) return 0
        if (sel.isEmpty()) {
            if (generics.size < MAX_GENERIC_COSMETIC) {
                generics.add(css)
                return 1
            }
            return 0
        }
        if (sel.contains('~') || sel.contains('*') || sel.contains('!')) return 0
        if (sel.startsWith("||")) sel = sel.substring(2)
        if (sel.startsWith("|")) sel = sel.substring(1)
        var end = 0
        while (end < sel.length && isHostChar(sel[end])) end++
        if (end <= 0) return 0
        var host = sel.substring(0, end)
        if (host.endsWith(".")) host = host.dropLast(1)
        if (!host.contains('.')) return 0
        cosmetic.getOrPut(host) { ArrayList(2) }.add(css)
        return 1
    }

    private fun isSafeSelector(css: String): Boolean {
        if (css.length < 2 || css.length > 256) return false
        if (css.startsWith("+")) return false
        for (c in css) {
            if (c == '{' || c == '}' || c == '<' || c == '>' ||
                c == '"' || c == '\'' || c == '\\' || c == '(' || c == ')'
            ) {
                return false
            }
        }
        return true
    }

    private class Options(val types: Int, val party: Int, val important: Boolean)

    private fun parseOptions(options: String?): Options? {
        if (options.isNullOrEmpty()) return Options(T_ALL, PARTY_ANY, false)
        var positives = 0
        var negatives = 0
        var party = PARTY_ANY
        var important = false
        for (tokenRaw in options.split(',')) {
            val token = tokenRaw.trim()
            if (token.isEmpty()) continue
            val negated = token.startsWith("~")
            val name = if (negated) token.substring(1) else token
            if (name.startsWith("domain")) return null
            if (name == "badfilter") return null
            when (name) {
                "third-party" -> if (!negated) party = PARTY_THIRD else party = PARTY_FIRST
                "first-party" -> if (!negated) party = PARTY_FIRST else party = PARTY_THIRD
                "important" -> important = true
                "elemhide", "generichide", "specifichide", "exthidestyle",
                "match-case", "cookie", "strict1p1p", "strict3p1p", "all",
                -> {}
                else -> {
                    val bit = typeBit(name) ?: continue
                    if (negated) negatives = negatives or bit else positives = positives or bit
                }
            }
        }
        val types = if (positives != 0) positives else T_ALL and negatives.inv()
        if (types == 0) return null
        return Options(types, party, important)
    }

    private fun typeBit(name: String): Int? = when (name) {
        "script" -> T_SCRIPT
        "stylesheet" -> T_STYLESHEET
        "image" -> T_IMAGE
        "xmlhttprequest" -> T_XHR
        "subdocument", "popup" -> T_SUBDOC
        "object", "object-subrequest" -> T_OBJECT
        "font" -> T_FONT
        "media" -> T_MEDIA
        "ping", "beacon" -> T_PING
        "document" -> T_DOC
        "other", "websocket" -> T_OTHER
        else -> null
    }

    private fun literalParts(rest: String): Pair<String?, String?> {
        if (rest.isEmpty()) return null to null
        var best = ""
        var second = ""
        var start = -1
        for (i in rest.indices) {
            val c = rest[i]
            val literal = c != '*' && c != '^' && c != '|' && c != '$'
            if (literal) {
                if (start < 0) start = i
            } else if (start >= 0) {
                val seg = rest.substring(start, i).lowercase()
                if (seg.length >= 4) {
                    if (seg.length > best.length) {
                        second = best
                        best = seg
                    } else if (seg.length > second.length) {
                        second = seg
                    }
                }
                start = -1
            }
        }
        if (start >= 0) {
            val seg = rest.substring(start).lowercase()
            if (seg.length >= 4) {
                if (seg.length > best.length) {
                    second = best
                    best = seg
                } else if (seg.length > second.length) {
                    second = seg
                }
            }
        }
        return best.ifEmpty { null } to second.ifEmpty { null }
    }

    private fun download(dir: File, name: String, url: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            dir.mkdirs()
            val tmp = File(dir, "$name.tmp")
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return false
            conn.inputStream.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            val dst = File(dir, "$name.txt")
            if (dst.exists()) dst.delete()
            tmp.renameTo(dst)
        } catch (e: Exception) {
            Log.w(TAG, "failed to download $name", e)
            false
        } finally {
            conn?.disconnect()
        }
    }

    private fun hostOf(url: String): String? = try {
        Uri.parse(url).host?.lowercase()
    } catch (e: Exception) {
        null
    }

    private fun eTldPlusOne(host: String): String {
        val first = host.indexOf('.')
        if (first < 0) return host
        val second = host.indexOf('.', first + 1)
        return if (second < 0) host else host.substring(second + 1)
    }

    private fun inferType(url: String): Int {
        var path = url
        val schemeEnd = path.indexOf("//")
        if (schemeEnd >= 0) path = path.substring(schemeEnd + 2)
        val q = path.indexOf('?')
        if (q >= 0) path = path.substring(0, q)
        val f = path.indexOf('#')
        if (f >= 0) path = path.substring(0, f)
        val slash = path.lastIndexOf('/')
        val file = if (slash >= 0) path.substring(slash + 1) else path
        val dot = file.lastIndexOf('.')
        if (dot < 0) return T_OTHER
        return when (file.substring(dot + 1)) {
            "js", "mjs" -> T_SCRIPT
            "css" -> T_STYLESHEET
            "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "avif", "bmp" -> T_IMAGE
            "woff", "woff2", "ttf", "otf", "eot" -> T_FONT
            "mp4", "webm", "m3u8", "mp3", "ogg", "m4a", "aac" -> T_MEDIA
            else -> T_OTHER
        }
    }

    private fun isHostChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '.'
}
