package me.mudkip.moememos.data.service

private val pureTagLinePattern = Regex("^\\s*(#[^\\s#]+(?:\\s+#[^\\s#]+)*)\\s*$")
private val tagTokenPattern = Regex("#([^\\s#]+)")

internal data class KeerExportMemoContent(
    val content: String,
    val tags: List<String>,
)

internal fun transformMemoForKeerExport(
    content: String,
    originalTags: List<String>,
): KeerExportMemoContent {
    val firstLineBreak = content.indexOf('\n')
    val firstLineRaw = if (firstLineBreak >= 0) {
        content.substring(0, firstLineBreak)
    } else {
        content
    }
    val firstLine = firstLineRaw.removeSuffix("\r")
    if (!pureTagLinePattern.matches(firstLine)) {
        return KeerExportMemoContent(
            content = content,
            tags = originalTags,
        )
    }

    val extractedTags = tagTokenPattern.findAll(firstLine)
        .map { result -> result.groupValues[1].trim() }
        .filter { tag -> tag.isNotEmpty() }
        .toList()
    if (extractedTags.isEmpty()) {
        return KeerExportMemoContent(
            content = content,
            tags = originalTags,
        )
    }

    val mergedTags = LinkedHashSet<String>()
    originalTags.forEach { tag ->
        val normalized = tag.trim()
        if (normalized.isNotEmpty()) {
            mergedTags += normalized
        }
    }
    extractedTags.forEach { tag ->
        mergedTags += tag
    }

    val remainingContent = if (firstLineBreak >= 0) {
        content.substring(firstLineBreak + 1)
    } else {
        ""
    }
    return KeerExportMemoContent(
        content = remainingContent,
        tags = mergedTags.toList(),
    )
}
