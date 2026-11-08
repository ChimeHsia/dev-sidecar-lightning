package acr.browser.lightning.download

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DownloadAttemptTest {
    private val pending = PendingDownload("https://example.test/file", null, null, null, 0)
    private fun throwing(failure: Exception) = object : FileDownloader {
        override suspend fun download(pendingDownload: PendingDownload) { throw failure }
    }

    @Test fun networkStorageAndMalformedInputFailuresAreReportedWithoutEscaping() = runTest {
        for (failure in listOf(IOException("offline"), SecurityException("storage denied"),
            NumberFormatException("invalid metadata"), IllegalArgumentException("unsupported URL"))) {
            var reported: Exception? = null
            attemptDownload(throwing(failure), pending) { reported = it }
            assertSame(failure, reported)
        }
    }

    @Test fun cancellationStillCancelsTheDownload() = runTest {
        val cancelled = CancellationException("screen closed")
        try {
            attemptDownload(throwing(cancelled), pending) { fail("Cancellation must not be reported as failure") }
            fail("Expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancelled, actual)
        }
    }
}
