package com.mae.reader.epub

data class EpubBook(
    val title: String,
    val author: String,
    val chapters: List<Chapter>,
    val toc: List<TocEntry>
)

data class Chapter(
    val id: String,
    val title: String,
    val htmlContent: String
)

data class TocEntry(
    val title: String,
    val chapterIndex: Int
)
