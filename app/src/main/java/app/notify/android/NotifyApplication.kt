package app.notify.android

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import app.notify.android.controller.ControllerViewModel

class NotifyApplication : Application(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
    val controller: ControllerViewModel by lazy {
        ViewModelProvider(viewModelStore, ViewModelProvider.AndroidViewModelFactory.getInstance(this))[ControllerViewModel::class.java]
    }
    private var startedActivities = 0

    fun activityStarted() {
        if (startedActivities++ == 0) controller.foreground(true)
    }
    fun activityStopped() {
        check(startedActivities > 0)
        if (--startedActivities == 0) controller.foreground(false)
    }
}
