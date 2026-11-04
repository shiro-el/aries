package io.github.shiroel.aries.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

/** A real descriptor-backed provider for attachment tests, rather than shadow-only InputStreams. */
fun registerAttachmentInput(context: Context, uri: Uri, bytes: ByteArray) {
    val file = File.createTempFile("shared-", ".bin", context.cacheDir).apply { writeBytes(bytes) }
    AttachmentTestProvider.files[uri.toString()] = file
    val authority = requireNotNull(uri.authority)
    val provider = AttachmentTestProvider().apply { attachInfo(context, ProviderInfo().apply { this.authority = authority }) }
    ShadowContentResolver.registerProviderInternal(authority, provider)
}

class AttachmentTestProvider : ContentProvider() {
    companion object { val files = java.util.concurrent.ConcurrentHashMap<String, File>() }
    override fun onCreate() = true
    override fun getType(uri: Uri): String? = null
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor? = null
    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor =
        AssetFileDescriptor(ParcelFileDescriptor.open(files.getValue(uri.toString()), ParcelFileDescriptor.MODE_READ_ONLY), 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
}
