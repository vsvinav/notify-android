package app.notify.android

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import app.notify.android.controller.ControllerViewModel
import app.notify.android.ui.NotifyApp
import app.notify.android.notifications.NotificationSettings

class MainActivity : ComponentActivity() {
    private val controller by lazy { ViewModelProvider(this)[ControllerViewModel::class.java] }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action == NotificationSettings.OPEN_ATTENTION) controller.openAttention()
        enableEdgeToEdge()
        setContent { NotifyApp(controller, this) }
    }
    override fun onStart() { super.onStart(); controller.foreground(true) }
    override fun onStop() { controller.foreground(false); super.onStop() }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == NotificationSettings.OPEN_ATTENTION) controller.openAttention()
    }
}
