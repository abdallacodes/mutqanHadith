package com.mutqan.hadith.domain.narrator

/** A blank line between paragraphs, however the line ends were written. */
private val ParagraphBreak = Regex("""\r?\n\s*\r?\n""")

/** What an admin wrote about a narrator as paragraphs in reading order, none of them blank; empty when nothing was written. */
fun bioParagraphs(written: String): List<String> =
    written.split(ParagraphBreak).map { it.trim() }.filter { it.isNotEmpty() }
