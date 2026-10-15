package io.nekohasekai.sfa.profile

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI

/** Structural guard for Howl responses; third-party profiles retain their own tags. */
object ProfileDocument {
    const val MAX_BYTES = 2 * 1024 * 1024

    fun inspect(content: String, source: String): Boolean {
        require(content.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val parser = JSONTokener(content)
        val root = value(parser, 0) as? JSONObject ?: error("object required")
        require(parser.nextClean() == 0.toChar())
        val isHowl = runCatching {
            val uri = URI(source)
            uri.host.equals("gethowl.app", true) && uri.path == "/sub.html"
        }.getOrDefault(false)
        if (!isHowl) return false
        val all = linkedMapOf<String, JSONObject>()
        for (key in listOf("outbounds", "endpoints")) {
            val items = root.optJSONArray(key) ?: if (key == "outbounds") error("outbounds required") else continue
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val tag = item.getString("tag")
                require(tag.isNotBlank() && tag.length <= 512 && all.put(tag, item) == null)
                require(all.size <= 1024)
            }
        }
        val edges = all.mapValues { (_, node) ->
            buildList<String> {
                if (node.optString("type") in setOf("selector", "urltest")) {
                    val members = node.getJSONArray("outbounds")
                    require(members.length() > 0)
                    for (i in 0 until members.length()) add(members.getString(i))
                    if (node.has("default")) require(node.getString("default") in this)
                }
                if (node.has("detour")) add(node.getString("detour"))
            }.also { refs -> require(refs.all { it in all }) }
        }
        val seen = mutableSetOf<String>()
        val visiting = mutableSetOf<String>()
        fun visit(tag: String, depth: Int) {
            require(depth < 64)
            if (tag in seen) return
            require(visiting.add(tag))
            edges.getValue(tag).forEach { visit(it, depth + 1) }
            visiting.remove(tag)
            seen.add(tag)
        }
        all.keys.forEach { visit(it, 0) }
        require(all["Howl"]?.optString("type") == "selector")
        val reachable = mutableSetOf<String>()
        fun reach(tag: String) {
            if (reachable.add(tag)) edges.getValue(tag).forEach(::reach)
        }
        reach("Howl")
        if (reachable.any { all.getValue(it).optString("type") !in setOf("selector", "urltest", "direct", "block", "dns") }) return false
        require(all.size == 2 && edges["Howl"] == listOf("direct") &&
            all["direct"]?.optString("type") == "direct" &&
            all["Howl"]?.optString("default") == "direct" &&
            root.optJSONObject("route")?.optString("final") == "Howl" &&
            (root.optJSONArray("inbounds")?.length() ?: 0) > 0 &&
            (root.optJSONObject("dns")?.optJSONArray("servers")?.length() ?: 0) > 0)
        return true
    }

    /** Reject duplicate properties and excessive depth before native validation. */
    private fun value(parser: JSONTokener, depth: Int): Any {
        require(depth < 64)
        return when (val start = parser.nextClean()) {
            '{' -> {
                val obj = JSONObject()
                if (parser.nextClean() == '}') return obj
                parser.back()
                while (true) {
                    require(parser.nextClean() == '"')
                    val name = parser.nextString('"')
                    require(!obj.has(name) && parser.nextClean() == ':')
                    obj.put(name, value(parser, depth + 1))
                    when (parser.nextClean()) {
                        '}' -> return obj
                        ',' -> Unit
                        else -> error("object delimiter")
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                obj
            }
            '[' -> {
                val array = JSONArray()
                if (parser.nextClean() == ']') return array
                parser.back()
                while (true) {
                    array.put(value(parser, depth + 1))
                    when (parser.nextClean()) {
                        ']' -> return array
                        ',' -> Unit
                        else -> error("array delimiter")
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                array
            }
            '"', '-', in '0'..'9', 't', 'f', 'n' -> {
                parser.back()
                parser.nextValue()
            }
            else -> error("JSON token $start")
        }
    }
}
