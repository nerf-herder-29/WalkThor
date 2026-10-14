package com.nerfherder.walkthor.data

import org.json.JSONObject

enum class BookType { PDF, TXT }

enum class PageLayout { SPREAD, SINGLE }

/**
 * A guide in the library.
 *
 * @param lastPage   last visible page (PDF) — for TXT this is only a hint, [lastOffset] is authoritative
 *                   because TXT pagination changes with font size and screen.
 * @param layout     null = automatic (two-page spread for PDF, single wide page for TXT).
 */
data class Book(
    val id: String,
    val title: String,
    val type: BookType,
    val fileName: String,
    val addedAt: Long,
    val lastPage: Int = 0,
    val lastOffset: Int = 0,
    val pageCount: Int = 0,
    val layout: PageLayout? = null,
) {
    val effectiveLayout: PageLayout
        get() = layout ?: if (type == BookType.PDF) PageLayout.SPREAD else PageLayout.SINGLE

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("type", type.name)
        put("fileName", fileName)
        put("addedAt", addedAt)
        put("lastPage", lastPage)
        put("lastOffset", lastOffset)
        put("pageCount", pageCount)
        layout?.let { put("layout", it.name) }
    }

    companion object {
        fun fromJson(o: JSONObject) = Book(
            id = o.getString("id"),
            title = o.getString("title"),
            type = BookType.valueOf(o.getString("type")),
            fileName = o.getString("fileName"),
            addedAt = o.optLong("addedAt"),
            lastPage = o.optInt("lastPage"),
            lastOffset = o.optInt("lastOffset"),
            pageCount = o.optInt("pageCount"),
            layout = o.optString("layout").takeIf { it.isNotEmpty() }?.let { PageLayout.valueOf(it) },
        )
    }
}
