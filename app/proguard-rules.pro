-repackageclasses ''
-overloadaggressively

# Keep methods exposed to WebView JavaScript through addJavascriptInterface.
-keepattributes *Annotation*
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
