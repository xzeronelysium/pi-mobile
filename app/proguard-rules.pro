# R8 rules for the release build.
#
# The app is a Compose screen over a hidden WebView that runs the Pi Durable runtime. Almost all
# data crosses that boundary as JSON strings parsed by name, so the only names R8 could break are
# the ones JavaScript resolves itself through the injected bridge object.

# The bridge: `addJavascriptInterface(Bridge(), "AndroidNative")` in NativeRuntimeHost.kt exposes
# `postMessage` to the page. JavaScript calls it by name, so R8 must not rename or remove it.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# The bridge holder itself is referenced from Kotlin, but keep it whole so a future @JavascriptInterface
# member cannot be stripped by an inlining pass.
-keep class dev.pi.mobile.backend.NativeRuntimeHost$Bridge { *; }

# NativeShell probes these two methods reflectively, because they only exist from API 26 while the
# app supports API 24. Reflection by string is invisible to R8, so pin them.
-keepclassmembers class java.lang.Process {
    public int pid();
    public boolean destroyForcibly();
}
