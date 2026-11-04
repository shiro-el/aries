# UniFFI calls native symbols by Java method name through JNA reflection.
-keep class com.sun.jna.** { *; }
-keep interface * extends com.sun.jna.Library { *; }
-keep class * implements com.sun.jna.Callback { *; }
-keep class * extends com.sun.jna.Structure { *; }

# UniFFI also resolves callback interfaces/implementations through JNA proxies.
-keep interface * extends com.sun.jna.Callback { *; }

# Android's optimized defaults already keep @JavascriptInterface methods;
# spell out the bridge contract for any future WebView JS bridge as well.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Firebase and WorkManager ship consumer rules. JSON here uses org.json, not
# reflective kotlinx.serialization; no blanket keeps of application/UI code.

# JNA also ships desktop-only AWT helpers; Android never calls these APIs.
-dontwarn java.awt.Component
-dontwarn java.awt.GraphicsEnvironment
-dontwarn java.awt.HeadlessException
-dontwarn java.awt.Window
