-dontwarn org.jetbrains.annotations.**

# WebView JS 接口
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
