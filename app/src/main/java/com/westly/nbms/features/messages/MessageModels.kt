package com.westly.nbms.features.messages

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.time.OffsetDateTime

/** Reply tracking of one message. Wire values are `none`, `pending`, `replied`. */
enum class ReplyStatus(val wire: String, val label: String) {
    NONE("none", "Not replied"),
    PENDING("pending", "Reply pending"),
    REPLIED("replied", "Replied");

    companion object {
        /** A null or unknown value becomes [NONE]. */
        fun fromWire(value: String?): ReplyStatus =
            entries.firstOrNull { it.wire == value?.trim()?.lowercase() } ?: NONE
    }
}

/** The three filter buttons on the inbox. */
enum class MessageFilter(val label: String) {
    ALL("All"),
    UNREAD("Unread"),
    UNREPLIED("Unreplied")
}

/** One website enquiry (a row of `public.messages`). */
data class InboxMessage(
    val id: String,
    val businessId: String = "",
    val name: String = "",
    val email: String = "",
    val phone: String? = null,
    val subject: String? = null,
    val message: String = "",
    /** "new" = unread, "read" = opened by staff. */
    val status: String = STATUS_READ,
    val replyStatus: ReplyStatus = ReplyStatus.NONE,
    val createdAt: Instant? = null,
    val readAt: Instant? = null,
    val repliedAt: Instant? = null
) {
    val isNew: Boolean get() = status == STATUS_NEW

    companion object {
        const val STATUS_NEW = "new"
        const val STATUS_READ = "read"
    }
}

/** Raw JSON row, exactly the snake_case columns of `public.messages`. Unknown keys are ignored. */
@Serializable
private data class MessageRowDto(
    val id: String,
    @SerialName("business_id") val businessId: String = "",
    val name: String = "",
    val email: String = "",
    val phone: String? = null,
    val subject: String? = null,
    val message: String = "",
    val status: String = InboxMessage.STATUS_READ,
    @SerialName("reply_status") val replyStatus: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("read_at") val readAt: String? = null,
    @SerialName("replied_at") val repliedAt: String? = null,
    @SerialName("is_deleted") val isDeleted: Boolean = false
)

private val messagesJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
}

internal const val MSG_LOAD_GENERIC = "We couldn't load messages."
internal const val MSG_UPDATE_GENERIC = "Something went wrong. Please try again."

private val SHORT_OFFSET = Regex("[+-]\\d\\d$")

/**
 * Parses an ISO-8601 timestamp. Tolerates a `+00:00` offset, a trailing `Z`, fractional seconds,
 * a space instead of `T` and a short `+00` offset. Anything unparseable gives null.
 */
fun parseMessageInstant(raw: String?): Instant? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    var s = text
    if (s.length > 10 && s[10] == ' ') s = s.substring(0, 10) + "T" + s.substring(11)
    if (SHORT_OFFSET.containsMatchIn(s) && s.contains('T')) s += ":00"
    try {
        val odt = OffsetDateTime.parse(s)
        return Instant.fromEpochSeconds(odt.toEpochSecond(), odt.nano)
    } catch (e: Exception) {
        // fall through
    }
    return try {
        Instant.parse(s)
    } catch (e: Exception) {
        null
    }
}

private fun MessageRowDto.toModel() = InboxMessage(
    id = id,
    businessId = businessId,
    name = name,
    email = email,
    phone = phone?.takeIf { it.isNotBlank() },
    subject = subject?.takeIf { it.isNotBlank() },
    message = message,
    status = status,
    replyStatus = ReplyStatus.fromWire(replyStatus),
    createdAt = parseMessageInstant(createdAt),
    readAt = parseMessageInstant(readAt),
    repliedAt = parseMessageInstant(repliedAt)
)

/** Parses one row object. Fails only when the row has no `id`. */
fun parseMessageRow(text: String): Result<InboxMessage> = try {
    Result.success(messagesJson.decodeFromString(MessageRowDto.serializer(), text).toModel())
} catch (e: Exception) {
    Result.failure(Exception(MSG_LOAD_GENERIC))
}

private fun failureText(obj: JsonObject, fallback: String): String =
    obj["error"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: fallback

/**
 * Reads a `messages-list` reply: `{"ok":true,"messages":[…]}`.
 * `{"ok":false,"error":"…"}` becomes a failure carrying the server's text.
 * Rows without an `id`, and rows flagged deleted, are skipped.
 */
fun parseMessagesReply(text: String): Result<List<InboxMessage>> = try {
    val obj = messagesJson.parseToJsonElement(text).jsonObject
    if (obj["ok"]?.jsonPrimitive?.booleanOrNull != true) {
        Result.failure(Exception(failureText(obj, MSG_LOAD_GENERIC)))
    } else {
        val rows = (obj["messages"] as? JsonArray)?.toList().orEmpty()
        val parsed = rows.mapNotNull { row ->
            try {
                messagesJson.decodeFromJsonElement(MessageRowDto.serializer(), row)
            } catch (e: Exception) {
                null
            }
        }
        Result.success(parsed.filter { !it.isDeleted }.map { it.toModel() })
    }
} catch (e: Exception) {
    Result.failure(Exception(MSG_LOAD_GENERIC))
}

/** Reads a `messages-update` reply: `{"ok":true}` or `{"ok":false,"error":"…"}`. */
fun parseUpdateReply(text: String): Result<Unit> = try {
    val obj = messagesJson.parseToJsonElement(text).jsonObject
    if (obj["ok"]?.jsonPrimitive?.booleanOrNull == true) Result.success(Unit)
    else Result.failure(Exception(failureText(obj, MSG_UPDATE_GENERIC)))
} catch (e: Exception) {
    Result.failure(Exception(MSG_UPDATE_GENERIC))
}

// ---------------------------------------------------------------- filters, search, counts

/** Number of unread messages. */
fun unreadOf(messages: List<InboxMessage>): Int = messages.count { it.isNew }

/** Does [m] pass the filter button? */
fun matchesFilter(m: InboxMessage, filter: MessageFilter): Boolean = when (filter) {
    MessageFilter.ALL -> true
    MessageFilter.UNREAD -> m.isNew
    MessageFilter.UNREPLIED -> m.replyStatus != ReplyStatus.REPLIED
}

/** Case-insensitive match on name, email, subject and body. A blank query matches everything. */
fun matchesQuery(m: InboxMessage, query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    return m.name.lowercase().contains(q) ||
        m.email.lowercase().contains(q) ||
        (m.subject?.lowercase()?.contains(q) == true) ||
        m.message.lowercase().contains(q)
}

/** Search and filter together. The order of [messages] is kept. */
fun filterMessages(messages: List<InboxMessage>, query: String, filter: MessageFilter): List<InboxMessage> =
    messages.filter { matchesFilter(it, filter) && matchesQuery(it, query) }

/**
 * "{n} messages · {u} unread" (unread part left out when 0). Before the first successful load
 * ([loaded] = false) it is the website-form description.
 */
fun headerSubtitle(total: Int, unread: Int, loaded: Boolean): String {
    if (!loaded) return "Enquiries submitted through the public website contact form"
    return if (unread > 0) "$total messages · $unread unread" else "$total messages"
}

/** First non-blank line of the body, trimmed. */
fun firstLine(message: String): String =
    message.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()

// ---------------------------------------------------------------- local changes (keep the unread count right)

/** Marks [id] as read. Already-read or unknown ids leave the list unchanged. */
fun markReadLocal(list: List<InboxMessage>, id: String, now: Instant? = null): List<InboxMessage> =
    list.map { if (it.id == id && it.isNew) it.copy(status = InboxMessage.STATUS_READ, readAt = it.readAt ?: now) else it }

/** Removes [id]. */
fun removeLocal(list: List<InboxMessage>, id: String): List<InboxMessage> = list.filterNot { it.id == id }

/** Sets the reply status of [id]. */
fun setReplyLocal(list: List<InboxMessage>, id: String, status: ReplyStatus, now: Instant? = null): List<InboxMessage> =
    list.map {
        if (it.id != id) it
        else it.copy(
            replyStatus = status,
            repliedAt = if (status == ReplyStatus.REPLIED) (it.repliedAt ?: now) else null
        )
    }

// ---------------------------------------------------------------- reply by email

/** Subject of the reply e-mail: "Re: {subject}" or "Re: Your enquiry to {business name}". */
fun replySubject(subject: String?, businessName: String): String =
    "Re: " + (subject?.trim()?.takeIf { it.isNotEmpty() } ?: "Your enquiry to $businessName")

/** Builds a `mailto:` link. [subject] is percent-encoded; it is left off when null. */
fun mailtoLink(email: String, subject: String? = null): String {
    val base = "mailto:" + email.trim()
    if (subject == null) return base
    return base + "?subject=" + URLEncoder.encode(subject, "UTF-8").replace("+", "%20")
}

/** `tel:` link with spaces removed. */
fun telLink(phone: String): String = "tel:" + phone.filter { !it.isWhitespace() }
