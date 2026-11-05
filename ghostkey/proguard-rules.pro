# ============================================================
# GhostKey R8 keep 规则（方案：kernel-adaptation-plan-final.md 第九/十三章）
# 本文件经 consumerProguardFiles 随 AAR 下发给宿主 App 的 R8。
# 注意：consumer 规则里不允许出现全局选项（-allowaccessmodification /
# -repackageclasses / -assumenosideeffects 等），AGP 会在
# :ghostkey:exportReleaseConsumerProguardFiles 校验时直接失败
# （"Global keep option ... should not be used in a consumer configuration
# file"）。这些全局优化项统一由宿主 App 的 app/proguard-rules.pro 持有。
# ============================================================

# ===== 保留规则 =====

# Shizuku UserService：以字符串类名传给 ComponentName，由 Shizuku 反射实例化，
# 类名一旦混淆直接 ClassNotFoundException
-keep class com.GhostKey.app.shizuku.Cve98260UserService { *; }

# AIDL 接口与 Stub：跨进程 Binder 序列化，DESCRIPTOR/CREATOR 反射访问
-keep class com.GhostKey.app.shizuku.ICve98260UserService { *; }
-keep class com.GhostKey.app.shizuku.ICve98260UserService$* { *; }
-keep class com.GhostKey.app.shizuku.ICve98260Callback { *; }
-keep class com.GhostKey.app.shizuku.ICve98260Callback$* { *; }

# 反射/泛型安全所需的属性（Kotlin 反射、协程、Compose 均依赖）
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
