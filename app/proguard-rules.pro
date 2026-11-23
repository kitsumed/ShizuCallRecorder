# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Prevent code obfuscation to keep the original class, method, and field names.
-dontobfuscate

# Keep the original class and method names for debugging on release builds.
-keepattributes LineNumberTable
-keepattributes SourceFile
-keepattributes Signature, InnerClasses, EnclosingMethod

# Strip all Log.v calls
-assumenosideeffects class android.util.Log {
  v(...);
}

# Also strip your our custom AppLogger verbose calls
-assumenosideeffects class com.kitsumed.shizucallrecorder.utils.AppLogger {
  v(...);
  java.lang.String getCallerTag(); # Needed because this is used as a parameter the v() method above
}
# ---- SFTP (JSch): algorithms are loaded by class name via reflection, keep them all. ----
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.slf4j.**
-dontwarn org.bouncycastle.**
-dontwarn org.newsclub.net.unix.**
-dontwarn com.sun.jna.**
-dontwarn org.ietf.jgss.**

# ---- FTP/FTPS (Apache Commons Net) ----
-dontwarn org.apache.commons.net.**

# ---- EncryptedSharedPreferences (Tink): compile-only annotations referenced by Tink ----
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.api.client.http.**
-dontwarn org.joda.time.**
