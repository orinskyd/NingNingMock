# 依依模拟 ProGuard/R8 Rules

# ==================== osmdroid 地图库 ====================
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# ==================== 反射调用：Location 反检测 ====================
# 保留 Location.mIsFromMockProvider 字段（反射修改为false）
-keep class android.location.Location {
    private boolean mIsFromMockProvider;
    public void setIsFromMockProvider(boolean);
    public boolean isMock();
    public void setIsMock(boolean);
}

# ==================== 反射调用：ProviderProperties ====================
# Android 12+ 通过反射创建 ProviderProperties.Builder
-keep class android.location.provider.ProviderProperties { *; }
-keep class android.location.provider.ProviderProperties$Builder { *; }

# ==================== 反射调用：LocationManager 缓存 ====================
-keep class android.location.LocationManager {
    private java.util.HashMap mLastKnownLocation;
}

# ==================== Kotlin 元数据 ====================
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses

# ==================== Service 和 Activity 入口 ====================
-keep class com.ningning.mock.MockLocationService { *; }
-keep class com.ningning.mock.MockLocationService$* { *; }
-keep class com.ningning.mock.LocationHooks { *; }
-keep class com.ningning.mock.WifiController { *; }

# ==================== 数据类 ====================
-keep class com.ningning.mock.MockLocationService$MockStatus { *; }
-keep class com.ningning.mock.MainActivity$SearchResult { *; }
-keep class com.ningning.mock.MainActivity$HistoryItem { *; }
-keep class com.ningning.mock.MainActivity$FavoriteItem { *; }
