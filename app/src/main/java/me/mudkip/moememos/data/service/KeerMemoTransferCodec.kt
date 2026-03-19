package me.mudkip.moememos.data.service

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val keerMemoTransferFormatV2 = "keer.memo.transfer.v2"

@Serializable
internal data class KeerMemoTransferDocument(
    val format: String = keerMemoTransferFormatV2,
    val exportedAt: String,
    val source: KeerMemoTransferSource? = null,
    val memos: List<KeerMemoTransferMemo> = emptyList(),
)

@Serializable
internal data class KeerMemoTransferSource(
    val host: String? = null,
    val userId: String? = null,
    val username: String? = null,
)

@Serializable
internal data class KeerMemoTransferAttachment(
    val path: String,
    val filename: String,
    val mimeType: String? = null,
)

@Serializable
internal data class KeerMemoTransferMemo(
    val importId: String? = null,
    val content: String,
    val createdAt: String? = null,
    val visibility: String? = null,
    val tags: List<String> = emptyList(),
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val attachments: List<KeerMemoTransferAttachment> = emptyList(),
)

internal object KeerMemoTransferCodec {
    private val json = Json {
        prettyPrint = true
        explicitNulls = false
    }

    fun encode(document: KeerMemoTransferDocument): String {
        return json.encodeToString(KeerMemoTransferDocument.serializer(), document)
    }
}
