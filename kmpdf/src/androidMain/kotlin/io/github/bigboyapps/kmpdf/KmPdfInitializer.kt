package io.github.bigboyapps.kmpdf

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import co.touchlab.kermit.Logger

/**
 * ContentProvider that automatically initializes KmPdfGenerator.
 *
 * This provider is automatically registered via the AndroidManifest.xml and runs
 * before Application.onCreate(), ensuring KmPdfGenerator is ready to use without
 * manual initialization.
 *
 * Note: This is an internal implementation detail. You do not need to interact with
 * this class directly. If you prefer manual initialization, you can disable this by
 * adding the following to your AndroidManifest.xml:
 *
 * ```xml
 * <provider
 *     android:name="io.github.bigboyapps.kmpdf.KmPdfInitializer"
 *     android:authorities="${applicationId}.kmpdf-initializer"
 *     tools:node="remove" />
 * ```
 */
class KmPdfInitializer : ContentProvider() {
    private val logger = Logger.withTag("KmPdfInitializer")

    override fun onCreate(): Boolean {
        val context = context ?: return false
        logger.d { "Auto-initializing KmPdfGenerator" }
        initKmPdfGenerator(context)
        return true
    }

    // ContentProvider boilerplate - these methods are not used
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
