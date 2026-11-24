package com.vherrmann.linkhole.data

object UrlExtractor {
    private val regex = Regex("""https?://[^\s<>"\[\]]+""", RegexOption.IGNORE_CASE)

    /** The first http(s) URL in [text], without trailing punctuation. */
    fun firstUrl(text: String?): String? {
        val m = regex.find(text ?: return null) ?: return null
        var url = m.value
        while (url.isNotEmpty() && (url.last() in ".,;:!?'\"" ||
                (url.last() == ')' && url.count { it == ')' } > url.count { it == '(' }))
        ) {
            url = url.dropLast(1)
        }
        return url.takeIf { Regex("""^https?://[^/]+""", RegexOption.IGNORE_CASE).containsMatchIn(it) }
    }
}
