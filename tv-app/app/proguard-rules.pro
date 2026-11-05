# TDLib JNI calls into these classes by name
-keep class org.drinkless.tdlib.** { *; }
-keepclassmembers class org.drinkless.tdlib.** { *; }
# media3 ffmpeg extension (loaded reflectively)
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-dontwarn org.drinkless.tdlib.**
-dontwarn androidx.annotation.**
