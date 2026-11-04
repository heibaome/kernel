# Shizuku binds GhostlockUserService by component name and talks over the
# generated AIDL stubs; R8 must keep both sides in release builds.
-keep class com.ghostlock.app.shizuku.GhostlockUserService { *; }
-keep interface com.ghostlock.app.shizuku.IGhostlockUserService { *; }
-keep interface com.ghostlock.app.shizuku.IGhostlockCallback { *; }
-keep class com.ghostlock.app.shizuku.IGhostlockUserService$Stub { *; }
-keep class com.ghostlock.app.shizuku.IGhostlockCallback$Stub { *; }

# ===== 全局 R8 选项（由 :ghostkey 库规则迁移至此）=====
# 库模块的 consumerProguardFiles 不接受全局选项，AGP 在
# :ghostkey:exportReleaseConsumerProguardFiles 校验时报：
#   "Global keep option -allowaccessmodification was specified as a
#    consumerProguardFile ... It should not be used in a consumer
#    configuration file."
# 这些选项的语义原本就作用于最终 APK 的 R8，移到宿主模块后行为不变。
# 混淆所有类名/方法名，并统一重打包到根包，缩短标识符
-repackageclasses ''
-allowaccessmodification

# 移除 verbose / debug 日志调用（release 瘦身 + 降噪；i/w/e 保留便于排障）
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
