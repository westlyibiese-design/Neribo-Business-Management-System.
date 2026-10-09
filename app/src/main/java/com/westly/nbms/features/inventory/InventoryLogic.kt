package com.westly.nbms.features.inventory

/** Shared, pure stock rules (Appendix A §A.6.8). The Sales phase and later order phases call [assertSufficientStock]. */
object InventoryLogic {

    /** An item is low when it is at or below its minimum. */
    fun isLow(item: InventoryItem): Boolean = item.quantity <= item.minStock

    /**
     * Throws `IllegalStateException("Insufficient stock for \"{itemName}\": only {currentQty} left.")` when
     * [currentQty] is less than [requestedQty]. Equal is allowed; zero stock with any request throws.
     * Pure, so it is safe inside a transaction.
     */
    fun assertSufficientStock(currentQty: Int, requestedQty: Int, itemName: String) {
        if (currentQty < requestedQty) {
            throw IllegalStateException("Insufficient stock for \"$itemName\": only $currentQty left.")
        }
    }
}
