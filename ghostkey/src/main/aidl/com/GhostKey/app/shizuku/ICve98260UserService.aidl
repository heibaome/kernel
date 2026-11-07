// ICve98260UserService.aidl
package com.GhostKey.app.shizuku;

import com.GhostKey.app.shizuku.ICve98260Callback;

interface ICve98260UserService {
    /**
     * managerPackage: 宿主首页选择的根管理器包名（可空）。shell 侧解析 ksud 时
     * 优先尝试该包的 libksud.so，使 GK 与 GL 一样尊重用户选择；null 走既有扫描链。
     */
    void executeExploit(in byte[] profileBlob, @nullable String managerPackage, in ICve98260Callback callback);
    String getKernelRelease();
    boolean isShizukuReady();
    void destroy();
}
