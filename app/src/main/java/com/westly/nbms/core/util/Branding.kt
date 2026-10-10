package com.westly.nbms.core.util

/** The one place for the app's display name and copyright line, so no screen or export spells them differently. */
object Branding {

    /** The name users see everywhere (launcher label, app bar, login, notifications, PDF fallback). */
    const val APP_NAME = "NeriboBMS"

    /** Printed on receipts, PDFs, CSV files and under the opening animation. */
    const val COPYRIGHT = "\u00A9 Neribo Group"

    /** [csv] with the copyright line added as the last row. The CSV builders themselves stay unchanged. */
    fun csvWithCopyright(csv: String): String = csv.trimEnd('\r', '\n') + "\n" + COPYRIGHT + "\n"
}
