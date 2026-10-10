package com.westly.nbms.features.gym

import java.security.SecureRandom

/** Pure list rules used by [GymContentViewModel] and by the tab forms. Nothing here touches the database. */
object GymContentRules {

    private const val ID_LENGTH = 8
    private const val ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
    private val random = SecureRandom()

    /** A new 8-character random id (lower-case letters and digits). */
    fun newItemId(): String = buildString(ID_LENGTH) {
        repeat(ID_LENGTH) { append(ID_ALPHABET[random.nextInt(ID_ALPHABET.length)]) }
    }

    fun canAddEquipment(count: Int): Boolean = count < GymContentLimits.EQUIPMENT
    fun canAddPackage(count: Int): Boolean = count < GymContentLimits.PACKAGES
    fun canAddProgram(count: Int): Boolean = count < GymContentLimits.PROGRAMS
    fun canAddGalleryImage(count: Int): Boolean = count < GymContentLimits.GALLERY

    fun isValidEquipment(name: String, description: String): Boolean = name.isNotBlank() && description.isNotBlank()
    fun isValidPackage(name: String): Boolean = name.isNotBlank()
    fun isValidProgram(name: String, description: String): Boolean = name.isNotBlank() && description.isNotBlank()

    /** Moves the item at [index] one place up (-1) or down (+1). At either end, or for a bad index, the list is returned unchanged. */
    fun <T> moveItem(list: List<T>, index: Int, direction: Int): List<T> {
        if (direction != -1 && direction != 1) return list
        val target = index + direction
        if (index !in list.indices || target !in list.indices) return list
        val copy = list.toMutableList()
        val item = copy.removeAt(index)
        copy.add(target, item)
        return copy
    }

    /** The list without the item at [index]; a bad index leaves it unchanged. */
    fun <T> removeAt(list: List<T>, index: Int): List<T> =
        if (index in list.indices) list.filterIndexed { i, _ -> i != index } else list

    /** Replaces the item with the same id (keeping its place), or appends [item] when there is none. */
    fun <T> upsertById(list: List<T>, item: T, idOf: (T) -> String): List<T> {
        val id = idOf(item)
        val at = list.indexOfFirst { idOf(it) == id }
        return if (at < 0) list + item else list.mapIndexed { i, old -> if (i == at) item else old }
    }
}
