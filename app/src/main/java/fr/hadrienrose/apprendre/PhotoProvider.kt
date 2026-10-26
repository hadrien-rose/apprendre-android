package fr.hadrienrose.apprendre

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Les photos prises pour la page (« 📷 Photographier une page »), sans AndroidX : un FileProvider réduit à un seul
 * dossier (le cache `photos/`) et à un seul motif de nom (Rules.photoFile). Non exporté : l'appareil photo n'y écrit que
 * par la permission accordée pour CETTE photo, la page la lit, et rien d'autre de l'app n'est joignable.
 */
class PhotoProvider : ContentProvider() {
    companion object {
        fun authority(ctx: Context) = ctx.packageName + ".photos"
        fun dir(ctx: Context): File = File(ctx.cacheDir, "photos").apply { mkdirs() }
        fun uriFor(ctx: Context, f: File): Uri = Uri.Builder().scheme("content").authority(authority(ctx)).appendPath(f.name).build()

        /** Un fichier neuf, vide, pour l'appareil photo. */
        fun newPhoto(ctx: Context): File {
            val d = dir(ctx); val now = System.currentTimeMillis()
            var k = 0
            while (true) {
                val f = Rules.photoFile(d, Rules.photoName(now, k)) ?: throw IllegalStateException("nom de photo")
                if (!f.exists()) { f.createNewFile(); return f }
                k++
            }
        }

        /** Les photos de plus d'un jour sont effacées (la page les a envoyées depuis longtemps). */
        fun purge(ctx: Context) {
            val limit = System.currentTimeMillis() - Rules.DAY_MS
            dir(ctx).listFiles()?.forEach { if (it.lastModified() < limit) it.delete() }
        }
    }

    private fun fileOf(uri: Uri): File {
        val ctx = context ?: throw FileNotFoundException()
        if (uri.authority != authority(ctx) || uri.pathSegments.size != 1) throw FileNotFoundException()
        return Rules.photoFile(dir(ctx), uri.lastPathSegment) ?: throw FileNotFoundException()
    }

    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val f = fileOf(uri)
        val cols = projection?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }?.toTypedArray()
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols)
        c.addRow(cols.map { if (it == OpenableColumns.DISPLAY_NAME) f.name else f.length() }.toTypedArray())
        return c
    }

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val f = fileOf(uri)
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode))
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = 0
}
