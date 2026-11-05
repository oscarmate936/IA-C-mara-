package com.iacamara.app

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.iacamara.app.ui.AppRoot
import com.iacamara.app.ui.theme.IaCamaraTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.statusBars())
        }
        setContent {
            IaCamaraTheme {
                AppRoot()
            }
        }
    }

    /** Los botones de volumen funcionan como disparador mientras el visor está abierto. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val isVolumeKey = keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP
        if (isVolumeKey && ShutterKeys.enabled) {
            if (event.repeatCount == 0) ShutterKeys.press()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
