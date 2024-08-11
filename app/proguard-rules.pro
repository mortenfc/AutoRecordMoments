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

# Keep necessary application classes and components
-keep class com.mfc.recentaudiobuffer.MainActivity
-keep class com.mfc.recentaudiobuffer.SettingsActivity
-keep class com.mfc.recentaudiobuffer.MyBufferService

# General Proguard rules for optimization and obfuscation
# (You can add more rules here based on your project's requirements)

# Conditional logging based on build types
-assumenosideeffects class android.util.Log {
    # Verbose logging for 'devVerbose' build type
    public static *** v(...);

    # Debug logging for 'dev' build type
    public static *** d(...);

    # No logging for 'release' build type (except errors)
    public static *** i(...);
    public static *** w(...);
    public static *** wtf(...);
}
