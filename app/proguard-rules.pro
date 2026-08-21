# ProGuard Rules for Offline CDR Analyzer (Release Minification & Obfuscation)

# 1. Chaquopy JNI & Java-Python Bridge
-keep class com.chaquo.python.** { *; }
-dontwarn com.chaquo.python.**

# 2. Interfaces and Listeners invoked from Python or Background Threads
-keep interface com.example.offlinecdranalyzer.MainActivity$ProgressListener { *; }
-keepclassmembers class * implements com.example.offlinecdranalyzer.MainActivity$ProgressListener { *; }

# 3. Preserve WebView JavaScript Interfaces
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepclassmembers class * extends android.webkit.WebViewClient { *; }

# 4. Preserve ViewBinding & DataBinding Classes
-keep class com.example.offlinecdranalyzer.databinding.** { *; }
-dontwarn com.example.offlinecdranalyzer.databinding.**

# 5. Core Activities and Models
-keep class com.example.offlinecdranalyzer.MainActivity { *; }
-keep class com.example.offlinecdranalyzer.CropActivity { *; }
-keep class com.example.offlinecdranalyzer.SameLocationActivity { *; }
-keep class com.example.offlinecdranalyzer.LinkAnalysisActivity { *; }

# 6. Preserve Reflection, Annotations, and Stack Traces
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable
-dontwarn java.lang.invoke.**