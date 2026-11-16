import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.ArrayDeque


private enum class Category {
    OTHER, EMOJI, TEXT, DEFAULT, MODBASE_TEXT, MODBASE_DEFAULT, MODIFIER, RI, KEYCAP,
    ENCLOSING_KEYCAP, CIRCLE_BACKSLASH, ZWJ, VS15, VS16, TAG_BASE, TAG, CANCEL_TAG,
}

private sealed interface Node
private data class Atom(val categories: Set<Int>) : Node
private data class Sequence(val nodes: List<Node>) : Node
private data class Alternative(val nodes: List<Node>) : Node
private data class Repeated(val node: Node) : Node

private fun atom(vararg categories: Category): Node = Atom(categories.map { it.ordinal }.toSet())
private fun seq(vararg nodes: Node): Node = Sequence(nodes.toList())
private fun alt(vararg nodes: Node): Node = Alternative(nodes.toList())
private fun plus(node: Node): Node = seq(node, Repeated(node))

private fun grammar(): List<Pair<Node, Int>> {
    val anyEmoji = atom(Category.EMOJI, Category.TEXT, Category.DEFAULT, Category.KEYCAP,
        Category.MODBASE_TEXT, Category.MODBASE_DEFAULT, Category.TAG_BASE)
    val presentation = seq(anyEmoji, atom(Category.VS16))
    val modifier = seq(atom(Category.MODBASE_TEXT, Category.MODBASE_DEFAULT), atom(Category.MODIFIER))
    val flag = seq(atom(Category.RI), atom(Category.RI))
    val tags = seq(atom(Category.TAG_BASE), plus(atom(Category.TAG)), atom(Category.CANCEL_TAG))
    val keycap = seq(atom(Category.KEYCAP), alt(seq(), atom(Category.VS16)), atom(Category.ENCLOSING_KEYCAP))
    val element = alt(presentation, modifier, anyEmoji)
    val joined = seq(element, plus(seq(atom(Category.ZWJ), element)))
    val enclosed = seq(anyEmoji, atom(Category.CIRCLE_BACKSLASH))
    val emoji = alt(atom(Category.DEFAULT, Category.TAG_BASE, Category.MODBASE_DEFAULT),
        presentation, modifier, flag, tags, keycap, joined, enclosed)
    val textVs = alt(seq(anyEmoji, atom(Category.VS15)),
        seq(atom(Category.KEYCAP), atom(Category.VS15), atom(Category.ENCLOSING_KEYCAP)))
    // Highest priority first for equal-length matches; the scanner prefers longest matches.
    return listOf(textVs to 0, emoji to 1, Atom(Category.entries.indices.toSet()) to 0)
}

private data class Acceptance(val priority: Int, val result: Int)
private data class Dfa(val transitions: List<List<Int>>, val accept: List<Int>)

private class Nfa {
    val edges = mutableListOf<MutableMap<Int, MutableSet<Int>>>()
    val epsilon = mutableListOf<MutableSet<Int>>()
    val accepts = mutableMapOf<Int, Acceptance>()

    fun newState(): Int {
        edges.add(linkedMapOf())
        epsilon.add(linkedSetOf())
        return edges.lastIndex
    }

    fun compile(node: Node): Pair<Int, Int> {
        val start = newState()
        val end = newState()
        when (node) {
            is Atom -> node.categories.forEach { category ->
                edges[start].getOrPut(category) { linkedSetOf() }.add(end)
            }
            is Sequence -> {
                var tail = start
                for (child in node.nodes) {
                    val (a, b) = compile(child)
                    epsilon[tail].add(a)
                    tail = b
                }
                epsilon[tail].add(end)
            }
            is Alternative -> for (child in node.nodes) {
                val (a, b) = compile(child)
                epsilon[start].add(a)
                epsilon[b].add(end)
            }
            is Repeated -> {
                val (a, b) = compile(node.node)
                epsilon[start].addAll(listOf(end, a))
                epsilon[b].addAll(listOf(a, end))
            }
        }
        return start to end
    }

    fun closure(states: Iterable<Int>): Set<Int> {
        val found = states.toMutableSet()
        val pending = ArrayDeque(found)
        while (pending.isNotEmpty()) {
            for (next in epsilon[pending.removeLast()]) {
                if (found.add(next)) pending.addLast(next)
            }
        }
        return found.toSet()
    }
}

/** Thompson NFA -> deterministic automaton -> partition refinement minimization. */
private fun buildDfa(): Dfa {
    val nfa = Nfa()
    val root = nfa.newState()
    grammar().forEachIndexed { priority, (pattern, result) ->
        val (start, end) = nfa.compile(pattern)
        nfa.epsilon[root].add(start)
        nfa.accepts[end] = Acceptance(priority, result)
    }
    val initial = nfa.closure(listOf(root))
    val indexes = linkedMapOf(initial to 0)
    val pending = ArrayDeque<Set<Int>>()
    pending.add(initial)
    val rows = mutableListOf<List<Int>>()
    val outputs = mutableListOf<Int>()
    while (pending.isNotEmpty()) {
        val subset = pending.removeFirst()
        outputs.add(subset.mapNotNull { nfa.accepts[it] }.minByOrNull { it.priority }?.result ?: -1)
        rows.add(Category.entries.indices.map { category ->
            val seeds = subset.flatMap { nfa.edges[it][category].orEmpty() }
            val target = nfa.closure(seeds)
            if (target.isEmpty()) -1 else indexes.getOrPut(target) {
                pending.addLast(target)
                indexes.size
            }
        })
    }

    var groups = outputs.map { it + 1 }
    while (true) {
        val signatures = linkedMapOf<Pair<Int, List<Int>>, Int>()
        val nextGroups = rows.indices.map { state ->
            val signature = outputs[state] to rows[state].map { if (it < 0) -1 else groups[it] }
            signatures.getOrPut(signature) { signatures.size }
        }
        if (nextGroups == groups) break
        groups = nextGroups
    }
    val representatives = linkedMapOf<Int, Int>()
    groups.forEachIndexed { state, group -> representatives.putIfAbsent(group, state) }
    check(groups[0] == 0)
    return Dfa(
        representatives.values.map { state -> rows[state].map { if (it < 0) -1 else groups[it] } },
        representatives.values.map { outputs[it] },
    )
}

private data class CategoryRange(val first: Int, val last: Int, val category: Category)

private fun buildCategories(text: String): List<CategoryRange> {
    val properties = listOf("Emoji", "Emoji_Presentation", "Emoji_Modifier_Base", "Emoji_Modifier")
        .associateWith { mutableSetOf<Int>() }
    for ((index, line) in text.lineSequence().withIndex()) {
        val record = line.substringBefore('#').trim()
        if (record.isEmpty()) continue
        val fields = record.split(';', limit = 2)
        require(fields.size == 2) { "Malformed Unicode record at line ${index + 1}" }
        val property = properties[fields[1].trim()] ?: continue
        val bounds = fields[0].trim().split("..")
        require(bounds.size in 1..2) { "Malformed Unicode range at line ${index + 1}" }
        val first = bounds.first().toInt(16)
        val last = bounds.last().toInt(16)
        require(first >= 0 && first <= last && last <= 0x10FFFF) {
            "Invalid Unicode range at line ${index + 1}"
        }
        property.addAll(first..last)
    }
    require(properties.values.all { it.isNotEmpty() }) { "Required Unicode properties missing" }
    val presentation = properties.getValue("Emoji_Presentation")
    val bases = properties.getValue("Emoji_Modifier_Base")
    val modifiers = properties.getValue("Emoji_Modifier")
    val special = mutableMapOf(
        0x200D to Category.ZWJ, 0xFE0E to Category.VS15, 0xFE0F to Category.VS16,
        0x20E3 to Category.ENCLOSING_KEYCAP, 0x20E0 to Category.CIRCLE_BACKSLASH,
        0x1F3F4 to Category.TAG_BASE, 0xE007F to Category.CANCEL_TAG,
    )
    for (cp in (0xE0030..0xE0039) + (0xE0061..0xE007A)) special[cp] = Category.TAG
    val points = properties.values.flatten().toMutableSet().apply { addAll(special.keys) }.sorted()
    val ranges = mutableListOf<CategoryRange>()
    for (cp in points) {
        val category = special[cp] ?: when (cp) {
            in bases -> if (cp in presentation) Category.MODBASE_DEFAULT else Category.MODBASE_TEXT
            in modifiers -> Category.MODIFIER
            in 0x1F1E6..0x1F1FF -> Category.RI
            0x23, 0x2A, in 0x30..0x39 -> Category.KEYCAP
            in presentation -> Category.DEFAULT
            else -> Category.TEXT
        }
        val previous = ranges.lastOrNull()
        if (previous != null && previous.last + 1 == cp && previous.category == category) {
            ranges[ranges.lastIndex] = previous.copy(last = cp)
        } else ranges.add(CategoryRange(cp, cp, category))
    }
    return ranges
}

private val runtime = """
/** offset/length are UTF-16 units; utf8Offset/utf8Length are bytes. */
data class EmojiTextPart(
    val offset: Int,
    val length: Int,
    val utf8Offset: Int,
    val utf8Length: Int,
    val isEmoji: Boolean,
) {
    fun textIn(source: String): String = source.substring(offset, offset + length)
}

/** Stateless presentation scanner. Recognizes emoji syntax. */
object EmojiTextSplitter {
    const val unicodeVersion: String = "@VERSION@"

    fun split(text: String): List<EmojiTextPart> {
        val parts = mutableListOf<EmojiTextPart>()
        var offset = 0
        var utf8Offset = 0
        while (offset < text.length) {
            var state = 0
            var cursor = offset
            var utf8Cursor = utf8Offset
            var end = offset
            var utf8End = utf8Offset
            var kind = -1
            while (cursor < text.length) {
                val cp = codePointAt(text, cursor)
                val next = TRANSITIONS[state * @CLASS_COUNT@ + category(cp)]
                if (next < 0) break
                state = next
                cursor += if (cp >= 0x10000) 2 else 1
                utf8Cursor += when {
                    cp < 0x80 -> 1
                    cp < 0x800 -> 2
                    cp < 0x10000 -> 3
                    else -> 4
                }
                if (ACCEPT[state] >= 0) {
                    end = cursor
                    utf8End = utf8Cursor
                    kind = ACCEPT[state]
                }
            }
            check(end > offset) { "Invalid generated emoji scanner" }
            val emoji = kind == 1
            val previous = parts.lastOrNull()
            if (previous != null && previous.isEmoji == emoji) {
                parts[parts.lastIndex] = previous.copy(
                    length = end - previous.offset,
                    utf8Length = utf8End - previous.utf8Offset,
                )
            } else {
                parts.add(EmojiTextPart(offset, end - offset, utf8Offset, utf8End - utf8Offset, emoji))
            }
            offset = end
            utf8Offset = utf8End
        }
        return parts
    }

    private fun codePointAt(text: String, index: Int): Int {
        val first = text[index].code
        if (first in 0xD800..0xDBFF) {
            require(index + 1 < text.length && text[index + 1].code in 0xDC00..0xDFFF) {
                "Unpaired UTF-16 surrogate at index ${'$'}index"
            }
            return 0x10000 + ((first - 0xD800) shl 10) + (text[index + 1].code - 0xDC00)
        }
        require(first !in 0xDC00..0xDFFF) { "Unpaired UTF-16 surrogate at index ${'$'}index" }
        return first
    }

    private fun category(codePoint: Int): Int {
        var low = 0
        var high = CATEGORIES.size / 3 - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val index = middle * 3
            when {
                codePoint < CATEGORIES[index] -> high = middle - 1
                codePoint > CATEGORIES[index + 1] -> low = middle + 1
                else -> return CATEGORIES[index + 2]
            }
        }
        return 0 // OTHER
    }

@TABLES@
}
"""

private fun generate(emojiData: String, packageName: String): String {
    val version = Regex("^# Version:\\s*(\\S+)", RegexOption.MULTILINE).find(emojiData)?.groupValues?.get(1)
        ?: error("Unicode version not found in emoji-data.txt")
    val dfa = buildDfa()
    val categories = buildCategories(emojiData)
    val tables = buildString {
        appendLine("    private val ACCEPT = intArrayOf(${dfa.accept.joinToString()})")
        appendLine("    private val TRANSITIONS = intArrayOf(")
        for (row in dfa.transitions) appendLine("        ${row.joinToString()},")
        appendLine("    )")
        appendLine("    // Inclusive first/last code point followed by its scanner category.")
        appendLine("    private val CATEGORIES = intArrayOf(")
        for (range in categories) {
            val first = range.first.toString(16).uppercase()
            val last = range.last.toString(16).uppercase()
            appendLine("        0x$first, 0x$last, ${range.category.ordinal},")
        }
        append("    )")
    }
    return buildString {
        appendLine("// Generated by EmojiSplitterGenerator.kt; do not edit manually.")
        appendLine("// Unicode terms: https://www.unicode.org/license.txt")
        appendLine("// Presentation syntax: https://www.unicode.org/reports/tr51/")
        appendLine("// ${dfa.transitions.size} DFA states; ${categories.size} category ranges.")
        if (packageName.isNotEmpty()) {
            appendLine()
            appendLine("package $packageName")
        }
        append(runtime.replace("@VERSION@", version)
            .replace("@CLASS_COUNT@", Category.entries.size.toString())
            .replace("@TABLES@", tables))
    }
}

private fun downloadUnicode(url: String): String {
    val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
    val request = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(60))
        .header("User-Agent", "kotlin-emoji-splitter-generator/1.0")
        .GET().build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
    check(response.statusCode() == 200) { "Unicode download failed: HTTP ${response.statusCode()}" }
    return response.body().removePrefix("\uFEFF")
}

fun generateEmojiSplitter(
    target: Path,
    packageName: String,
    url: String = "https://www.unicode.org/Public/UCD/latest/ucd/emoji/emoji-data.txt"
) {
    val emojiData = downloadUnicode(url)
    val kotlin = generate(emojiData, packageName)
    Files.writeString(target, kotlin, StandardCharsets.UTF_8)
}