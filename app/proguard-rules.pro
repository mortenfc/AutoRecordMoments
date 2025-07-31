# Keep necessary application classes and components that are referenced by the OS.
-keep class com.mfc.recentaudiobuffer.AutoRecordMomentsApp { *; }
-keep class com.mfc.recentaudiobuffer.MainActivity { *; }
-keep class com.mfc.recentaudiobuffer.SettingsActivity { *; }
-keep class com.mfc.recentaudiobuffer.DonationActivity { *; }
-keep class com.mfc.recentaudiobuffer.MyBufferService { *; }
-keep class com.mfc.recentaudiobuffer.NotificationActionReceiver { *; }
-keep class com.mfc.recentaudiobuffer.FileSavingService { *; }

# Google pay button rendering
-keep class com.google.android.gms.wallet.** { *; }
-dontwarn com.google.android.gms.wallet.**

# Keep all classes in the ONNX Runtime package.
# This prevents ProGuard/R8 from removing them and causing JNI crashes.
-keep class ai.onnxruntime.** { *; }
-keep public class * extends ai.onnxruntime.OnnxValue

# Keep all classes in the TarsosDSP package as a precaution.
-keep class be.tarsos.dsp.** { *; }

# Suppress warnings for classes that may not exist on all API levels.
-dontwarn android.media.LoudnessCodecController**
-dontwarn ai.onnxruntime.**
-dontwarn be.tarsos.dsp.**
