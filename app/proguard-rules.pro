# Add project specific ProGuard rules here.
-keepattributes *Annotation*
-keep class com.squareup.okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**