package acr.browser.lightning

import acr.browser.lightning.accel.DshaAccel
import acr.browser.lightning.concurrency.AppCoroutineScope
import acr.browser.lightning.database.bookmark.BookmarkExporter
import acr.browser.lightning.database.bookmark.BookmarkRepository
import acr.browser.lightning.device.BuildInfo
import acr.browser.lightning.device.BuildType
import acr.browser.lightning.di.AppComponent
import acr.browser.lightning.di.DaggerAppComponent
import acr.browser.lightning.di.injector
import acr.browser.lightning.migration.Cleanup
import acr.browser.lightning.utils.FileUtils
import acr.browser.lightning.utils.LeakCanaryUtils
import android.app.Application
import android.os.Build
import android.os.StrictMode
import android.webkit.WebView
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import kotlin.system.exitProcess

/**
 * The browser application.
 */
class BrowserApp : Application() {

    @Inject
    internal lateinit var leakCanaryUtils: LeakCanaryUtils

    @Inject
    internal lateinit var bookmarkModel: BookmarkRepository

    @Inject
    internal lateinit var buildInfo: BuildInfo

    @Inject
    internal lateinit var cleanup: Cleanup

    @Inject
    internal lateinit var appCoroutineScope: AppCoroutineScope

    @Inject
    internal lateinit var bookmarkExporter: BookmarkExporter

    lateinit var applicationComponent: AppComponent

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
        }

        val isIncognito = BrowserProcessIdentity.current(this) == BrowserProcess.INCOGNITO

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && isIncognito) {
            File(dataDir, "app_webview_incognito").deleteRecursively()
            WebView.setDataDirectorySuffix("incognito")
        }

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            if (BuildConfig.DEBUG) {
                FileUtils.writeCrashToStorage(ex)
            }

            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, ex)
            } else {
                exitProcess(2)
            }
        }

        applicationComponent = DaggerAppComponent.builder()
            .application(this)
            .buildInfo(createBuildInfo())
            .incognitoMode(isIncognito)
            .build()
        injector.inject(this)

        appCoroutineScope.launch {
            cleanup.cleanup()
        }

        appCoroutineScope.launch {
            if (bookmarkModel.count() == 0L) {
                val assetsBookmarks = bookmarkExporter.importBookmarksFromAssets()
                bookmarkModel.addBookmarkList(assetsBookmarks)
            }
        }

        if (buildInfo.buildType == BuildType.DEBUG) {
            leakCanaryUtils.setup()
        }

        if (buildInfo.buildType == BuildType.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        // 原生 Kotlin 核心由主进程持有；普通与无痕进程绑定同一服务和代理端口。
        if (isIncognito) {
            DshaAccel.attachProxyOnly(this)
        } else {
            DshaAccel.start(this)
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        appCoroutineScope.cancel()
    }

    /**
     * Create the [BuildType] from the [BuildConfig].
     */
    private fun createBuildInfo() = BuildInfo(
        packageName = packageName,
        buildType = when {
            BuildConfig.DEBUG -> BuildType.DEBUG
            else -> BuildType.RELEASE
        },
        versionCode = BuildConfig.VERSION_CODE,
        isPlus = BuildConfig.FULL_VERSION,
    )

    companion object {
        private const val TAG = "BrowserApp"
    }
}
