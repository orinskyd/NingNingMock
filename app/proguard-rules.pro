# 宁宁模拟 ProGuard Rules
# 默认规则：保留所有反射相关的类和方法

# osmdroid
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# 保留反射调用的字段
-keep class android.location.Location {
    private boolean mIsFromMockProvider;
}

# 保留Kotlin协程相关
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
