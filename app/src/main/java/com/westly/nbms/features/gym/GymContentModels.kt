package com.westly.nbms.features.gym

/** The most items each list may hold. */
object GymContentLimits {
    const val EQUIPMENT = 20
    const val PACKAGES = 12
    const val PROGRAMS = 20
    const val GALLERY = 24
}

/** The icons an equipment item can use. The first one is the default. */
object GymIcons {
    val ALL: List<String> = listOf("Dumbbell", "Sparkles", "Waves", "Heart", "Users", "Trophy", "Timer", "Flame", "Zap")
    const val DEFAULT = "Dumbbell"
}

data class EquipmentItem(
    val id: String,
    val name: String,
    val image: String = "",
    val description: String,
    val icon: String = "Dumbbell"
)

/** The shape Phase 29 reads (id, name, price, duration). */
data class PackageItem(
    val id: String,
    val name: String,
    val price: Double = 0.0,
    val duration: String = "Monthly",
    val features: List<String> = emptyList(),
    val popular: Boolean = false
)

data class ProgramItem(
    val id: String,
    val name: String,
    val description: String,
    val image: String = ""
)

/** One opening-hours line. [open] and [close] are "HH:mm". */
data class HoursRow(
    val day: String,
    val open: String = "06:00",
    val close: String = "22:00",
    val closed: Boolean = false
)

/** The gym content document `cms_content/gym` (its `data` map). */
data class GymContent(
    val about: String = "",
    val equipment: List<EquipmentItem> = emptyList(),
    val hours: List<HoursRow> = DEFAULT_HOURS,
    val packages: List<PackageItem> = emptyList(),
    val programs: List<ProgramItem> = emptyList(),
    val gallery: List<String> = emptyList()
) {
    companion object {
        val DEFAULT_DAYS: List<String> = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

        /** Monday to Sunday, 06:00–22:00, open. */
        val DEFAULT_HOURS: List<HoursRow> = DEFAULT_DAYS.map { HoursRow(day = it) }

        /**
         * Reads the document's `data` map tolerantly: a missing field or one that is not a list becomes empty, a list entry
         * that is not a map is skipped, and `hours` must be exactly seven rows (otherwise the defaults are used).
         */
        fun parse(raw: Map<String, Any?>?): GymContent {
            if (raw == null) return GymContent()
            return GymContent(
                about = (raw["about"] as? String).orEmpty(),
                equipment = parseEquipment(raw["equipment"]),
                hours = parseHours(raw["hours"]),
                packages = parsePackages(raw["packages"]),
                programs = parsePrograms(raw["programs"]),
                gallery = parseGallery(raw["gallery"])
            )
        }
    }
}

enum class GymSection(val key: String, val successToast: String) {
    ABOUT("about", "About Section Updated"),
    EQUIPMENT("equipment", "Equipment & Services Updated"),
    HOURS("hours", "Operating Hours Updated"),
    PACKAGES("packages", "Membership Packages Updated"),
    PROGRAMS("programs", "Programs Updated"),
    GALLERY("gallery", "Gym Gallery Updated")
}

// ── tolerant reading ──

private val HOURS_TIME = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")

private fun mapsOf(raw: Any?): List<Map<*, *>>? = (raw as? List<*>)?.mapNotNull { it as? Map<*, *> }

private fun text(raw: Any?): String = (raw as? String)?.trim().orEmpty()

/** An id kept as stored; a missing id gets a stable one from its position so the item can still be edited or removed. */
private fun idOf(raw: Any?, prefix: String, index: Int): String {
    val value = when (raw) {
        is String -> raw.trim()
        is Number -> raw.toLong().toString()
        else -> ""
    }
    return value.ifEmpty { "$prefix-$index" }
}

internal fun parseEquipment(raw: Any?): List<EquipmentItem> =
    mapsOf(raw)?.mapIndexed { i, m ->
        val icon = text(m["icon"])
        EquipmentItem(
            id = idOf(m["id"], "equipment", i),
            name = text(m["name"]),
            image = text(m["image"]),
            description = text(m["description"]),
            icon = if (icon in GymIcons.ALL) icon else GymIcons.DEFAULT
        )
    } ?: emptyList()

internal fun parsePackages(raw: Any?): List<PackageItem> =
    mapsOf(raw)?.mapIndexed { i, m ->
        val price = when (val p = m["price"]) {
            is Number -> p.toDouble()
            is String -> p.trim().toDoubleOrNull() ?: 0.0
            else -> 0.0
        }
        PackageItem(
            id = idOf(m["id"], "package", i),
            name = text(m["name"]),
            price = if (price.isNaN() || price.isInfinite() || price < 0.0) 0.0 else price,
            duration = text(m["duration"]).ifEmpty { "Monthly" },
            features = (m["features"] as? List<*>)?.mapNotNull { (it as? String)?.trim()?.ifEmpty { null } } ?: emptyList(),
            popular = m["popular"] == true
        )
    } ?: emptyList()

internal fun parsePrograms(raw: Any?): List<ProgramItem> =
    mapsOf(raw)?.mapIndexed { i, m ->
        ProgramItem(id = idOf(m["id"], "program", i), name = text(m["name"]), description = text(m["description"]), image = text(m["image"]))
    } ?: emptyList()

internal fun parseGallery(raw: Any?): List<String> =
    (raw as? List<*>)?.mapNotNull { (it as? String)?.trim()?.ifEmpty { null } } ?: emptyList()

/** Exactly seven map rows, else the defaults. A bad day name or time falls back to that day's default. */
internal fun parseHours(raw: Any?): List<HoursRow> {
    val list = raw as? List<*> ?: return GymContent.DEFAULT_HOURS
    if (list.size != 7 || list.any { it !is Map<*, *> }) return GymContent.DEFAULT_HOURS
    return list.mapIndexed { i, any ->
        val m = any as Map<*, *>
        val open = text(m["open"])
        val close = text(m["close"])
        HoursRow(
            day = text(m["day"]).ifEmpty { GymContent.DEFAULT_DAYS[i] },
            open = if (HOURS_TIME.matches(open)) open else "06:00",
            close = if (HOURS_TIME.matches(close)) close else "22:00",
            closed = m["closed"] == true
        )
    }
}

// ── writing: the exact field names stored in Firestore ──

internal fun EquipmentItem.toStored(): Map<String, Any?> =
    mapOf("id" to id, "name" to name, "image" to image, "description" to description, "icon" to icon)

internal fun PackageItem.toStored(): Map<String, Any?> =
    mapOf("id" to id, "name" to name, "price" to price, "duration" to duration, "features" to features, "popular" to popular)

internal fun ProgramItem.toStored(): Map<String, Any?> =
    mapOf("id" to id, "name" to name, "description" to description, "image" to image)

internal fun HoursRow.toStored(): Map<String, Any?> =
    mapOf("day" to day, "open" to open, "close" to close, "closed" to closed)

/** The value written under `data.<section key>` for [section], taken from [content]. */
internal fun gymSectionValue(section: GymSection, content: GymContent): Any = when (section) {
    GymSection.ABOUT -> content.about
    GymSection.EQUIPMENT -> content.equipment.map { it.toStored() }
    GymSection.HOURS -> content.hours.map { it.toStored() }
    GymSection.PACKAGES -> content.packages.map { it.toStored() }
    GymSection.PROGRAMS -> content.programs.map { it.toStored() }
    GymSection.GALLERY -> content.gallery
}
