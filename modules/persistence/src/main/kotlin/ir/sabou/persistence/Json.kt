package ir.sabou.persistence

/**
 * Minimal, dependency-free JSON for persisted documents. Supports objects, arrays, strings,
 * integers (Long), booleans and null — exactly what the domain needs (no floating point).
 */
object Json {
    fun encode(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    @Suppress("UNCHECKED_CAST")
    private fun write(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> string(out, value)
            is Long, is Int -> out.append(value.toString())
            is Boolean -> out.append(value.toString())
            is Map<*, *> -> {
                out.append('{')
                (value as Map<String, Any?>).entries.forEachIndexed { i, (k, v) ->
                    if (i > 0) out.append(','); string(out, k); out.append(':'); write(out, v)
                }
                out.append('}')
            }
            is List<*> -> {
                out.append('['); value.forEachIndexed { i, v -> if (i > 0) out.append(','); write(out, v) }; out.append(']')
            }
            else -> error("json_unsupported:${value::class.simpleName}")
        }
    }

    private fun string(out: StringBuilder, s: String) {
        out.append('"')
        for (c in s) when {
            c == '"' -> out.append("\\\"")
            c == '\\' -> out.append("\\\\")
            c == '\n' -> out.append("\\n")
            c == '\r' -> out.append("\\r")
            c == '\t' -> out.append("\\t")
            c < ' ' -> out.append("\\u%04x".format(c.code))
            else -> out.append(c)
        }
        out.append('"')
    }

    fun decode(text: String): Any? = Parser(text).run { val v = value(); skip(); require(pos == text.length) { "json_trailing" }; v }

    private class Parser(val s: String) {
        var pos = 0
        fun skip() { while (pos < s.length && s[pos].isWhitespace()) pos++ }
        fun value(): Any? {
            skip()
            require(pos < s.length) { "json_eof" }
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) num() else error("json_unexpected:$c")
            }
        }
        fun literal(word: String, v: Any?): Any? { require(s.startsWith(word, pos)) { "json_literal" }; pos += word.length; return v }
        fun num(): Long { val start = pos; if (s[pos] == '-') pos++; while (pos < s.length && s[pos].isDigit()) pos++; return s.substring(start, pos).toLong() }
        fun str(): String {
            pos++
            val b = StringBuilder()
            while (true) {
                require(pos < s.length) { "json_unterminated" }
                val c = s[pos++]
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> when (val e = s[pos++]) {
                        '"' -> b.append('"'); '\\' -> b.append('\\'); '/' -> b.append('/')
                        'n' -> b.append('\n'); 'r' -> b.append('\r'); 't' -> b.append('\t')
                        'b' -> b.append('\b'); 'f' -> b.append('\u000C')
                        'u' -> { b.append(s.substring(pos, pos + 4).toInt(16).toChar()); pos += 4 }
                        else -> error("json_escape:$e")
                    }
                    else -> b.append(c)
                }
            }
        }
        fun obj(): Map<String, Any?> {
            pos++; val m = LinkedHashMap<String, Any?>(); skip()
            if (s[pos] == '}') { pos++; return m }
            while (true) {
                skip(); val k = str(); skip(); require(s[pos++] == ':') { "json_colon" }
                m[k] = value(); skip()
                when (s[pos++]) { ',' -> continue; '}' -> return m; else -> error("json_object") }
            }
        }
        fun arr(): List<Any?> {
            pos++; val l = ArrayList<Any?>(); skip()
            if (s[pos] == ']') { pos++; return l }
            while (true) {
                l += value(); skip()
                when (s[pos++]) { ',' -> continue; ']' -> return l; else -> error("json_array") }
            }
        }
    }
}

/** Typed read helpers for decoded JSON objects. */
@Suppress("UNCHECKED_CAST")
class Doc(private val m: Map<String, Any?>) {
    fun str(k: String): String = m[k] as String
    fun strOrNull(k: String): String? = m[k] as String?
    fun long(k: String): Long = m[k] as Long
    fun longOrNull(k: String): Long? = m[k] as Long?
    fun int(k: String): Int = (m[k] as Long).toInt()
    fun bool(k: String): Boolean = m[k] as Boolean
    fun docs(k: String): List<Doc> = (m[k] as List<Map<String, Any?>>).map(::Doc)
    fun doc(k: String): Doc = Doc(m[k] as Map<String, Any?>)
    fun docOrNull(k: String): Doc? = (m[k] as Map<String, Any?>?)?.let(::Doc)
    fun strs(k: String): List<String> = m[k] as List<String>
    fun strMap(k: String): Map<String, String> = m[k] as Map<String, String>

    companion object {
        fun parse(json: String): Doc = Doc(Json.decode(json) as Map<String, Any?>)
    }
}
