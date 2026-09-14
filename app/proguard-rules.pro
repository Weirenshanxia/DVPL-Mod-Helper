# DVPL Mod Helper ProGuard Rules

# Keep native methods and their classes
-keepclasseswithmembernames class * {
    native <methods>;
}

# 保留编解码器 JNI 接口（包名是 codec 不是 core！）
-keep class com.dvpl.modhelper.codec.DvplCodec { *; }
-keep class com.dvpl.modhelper.codec.DvplCodec$* { *; }
-keep class com.dvpl.modhelper.codec.PvrConverter { *; }
-keep class com.dvpl.modhelper.codec.DdsConverter { *; }
-keep class com.dvpl.modhelper.codec.PvrConverter$* { *; }

# 移除日志（Release 构建）
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}

# 优化
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-optimizationpasses 5
-allowaccessmodification
-dontpreverify