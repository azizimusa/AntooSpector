package gg.padu.httpmonitor

/**
 * Case-insensitive, multi-value header map. Mutable so [HttpFilter]s can strip or
 * rewrite sensitive values before a transaction is stored.
 */
class Headers() : Iterable<Pair<String, String>> {

    private val values = LinkedHashMap<String, MutableList<String>>()
    private val names = LinkedHashMap<String, String>()

    /** Accepts nullable keys so `HttpURLConnection.getHeaderFields()` (status line key) fits. */
    constructor(source: Map<String?, List<String>?>) : this() {
        source.forEach { (name, list) ->
            if (name.isNullOrEmpty()) return@forEach
            list?.forEach { value -> add(name, value) }
        }
    }

    fun add(name: String, value: String) = apply {
        val key = name.lowercase()
        names.getOrPut(key) { name }
        values.getOrPut(key) { mutableListOf() }.add(value)
    }

    operator fun set(name: String, value: String) = apply {
        val key = name.lowercase()
        // Keep whatever casing the header was first seen with: set() is also how a
        // filter rewrites a value, and redacting "Authorization" should not leave
        // the name restyled in the viewer and on the wire.
        names.getOrPut(key) { name }
        values[key] = mutableListOf(value)
    }

    operator fun get(name: String): String? = values[name.lowercase()]?.firstOrNull()

    fun all(name: String): List<String> = values[name.lowercase()].orEmpty()

    fun contains(name: String): Boolean = values.containsKey(name.lowercase())

    fun remove(name: String) = apply {
        val key = name.lowercase()
        values.remove(key)
        names.remove(key)
    }

    fun names(): List<String> = names.values.toList()

    val size: Int get() = values.values.sumOf { it.size }

    fun copy(): Headers = Headers().also { copy -> forEach { (n, v) -> copy.add(n, v) } }

    override fun iterator(): Iterator<Pair<String, String>> =
        values.entries.asSequence()
            .flatMap { (key, list) -> list.asSequence().map { (names[key] ?: key) to it } }
            .iterator()

    override fun toString(): String = joinToString("\n") { (n, v) -> "$n: $v" }
}
