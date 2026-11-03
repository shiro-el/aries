package io.github.shiroel.aries.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import androidx.test.core.app.ApplicationProvider
import io.github.shiroel.aries.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AttachmentIntentsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun malformedStreamsAndPrivateFilePathsAreIgnoredWhileSharedTextIsRetained() {
        val uri = Uri.parse("content://gallery/photo")
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM,
                arrayListOf<Parcelable>(Bundle(), Uri.parse("file:///data/private"), uri))
            .putExtra(Intent.EXTRA_TEXT, "look at this")
        intent.clipData = ClipData.newRawUri("photo", uri)
        assertEquals(SharedContent(listOf(uri), "look at this"), SharedContent.from(intent))
    }

    @Test fun textOnlyAndSingleFileSharesAreAcceptedAndOrdinaryLaunchesAreIgnored() {
        val uri = Uri.parse("content://documents/file")
        assertEquals(SharedContent(text = "hello"), SharedContent.from(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "hello")))
        assertEquals(SharedContent(listOf(uri)), SharedContent.from(
            Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)))
        assertNull(SharedContent.from(Intent(Intent.ACTION_MAIN)))
        assertNull(SharedContent.from(Intent(Intent.ACTION_SEND)))
    }

    @Test fun androidRoutesImageFileAndTextSharesToMainActivity() {
        for (action in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) {
            for (mime in listOf("image/jpeg", "application/pdf", "text/plain")) {
                val intent = Intent(action).setType(mime).addCategory(Intent.CATEGORY_DEFAULT).setPackage(context.packageName)
                val activities = context.packageManager.queryIntentActivities(intent, 0)
                assertTrue("$action $mime", activities.any { it.activityInfo.name == MainActivity::class.java.name })
            }
        }
    }
}
