package com.intercom.video.twoway.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.intercom.video.twoway.ui.theme.TwoWayTheme

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(LocaleHelper.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TwoWayTheme {
                AppNavHost()
            }
        }
    }
}
