package acr.browser.lightning.download

import kotlinx.coroutines.CancellationException

/** Download transport/storage errors are reported without escaping the application coroutine. */
internal suspend fun attemptDownload(
    downloader: FileDownloader,
    pending: PendingDownload,
    onFailure: (Exception) -> Unit,
) {
    try {
        downloader.download(pending)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        onFailure(failure)
    }
}
