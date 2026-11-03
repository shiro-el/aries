package io.github.shiroel.aries.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AttachmentPreparationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun filesAtEightMibAreAllowedAndLargerFilesAreRejected() {
        val file = File(context.cacheDir, "large.bin")
        java.io.RandomAccessFile(file, "rw").use { it.setLength(8L * 1024 * 1024) }
        assertEquals(8 * 1024 * 1024, prepareAttachment(context, Uri.fromFile(file), false).bytes.size)
        java.io.RandomAccessFile(file, "rw").use { it.setLength(8L * 1024 * 1024 + 1) }
        assertThrows(AttachmentTooLarge::class.java) { prepareAttachment(context, Uri.fromFile(file), false) }
    }

    @Test fun imageResizingDefaultsOnAndTheOptOutSurvivesRestart() {
        context.getSharedPreferences("attachments", Context.MODE_PRIVATE).edit().clear().commit()
        val settings = AttachmentSettings.from(context)
        assertTrue(settings.resizeImages)
        settings.chooseResizeImages(false)
        assertFalse(AttachmentSettings.from(context).resizeImages)
        settings.chooseResizeImages(true)
    }

    @Test fun photosBecomeJpegWithLongestSide2048AndCanKeepOriginals() {
        val file = File(context.cacheDir, "large.png")
        val bitmap = Bitmap.createBitmap(4096, 1024, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val uri = Uri.fromFile(file)
        val attachment = prepareAttachment(context, uri, resizeImages = true)
        assertEquals("large.jpg", attachment.name)
        val decoded = BitmapFactory.decodeByteArray(attachment.bytes, 0, attachment.bytes.size)
        assertEquals(2048, decoded.width)
        assertEquals(512, decoded.height)
        assertEquals(0xff.toByte(), attachment.bytes[0])
        assertEquals(0xd8.toByte(), attachment.bytes[1])
        decoded.recycle()
        val original = prepareAttachment(context, uri, resizeImages = false)
        assertEquals("large.png", original.name)
        assertArrayEquals(file.readBytes(), original.bytes)
    }
}
