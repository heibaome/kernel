package com.GhostKey.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.GhostKey.app.ui.theme.CVE98260Theme

/**
 * GhostKey 模块入口（并入 GhostLock 外壳后由首页卡片拉起，非 LAUNCHER）。
 * 类名从 MainActivity 改为 GhostKeyActivity，避免与外壳入口重名冲突。
 */
class GhostKeyActivity : ComponentActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CVE98260Theme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
}
