package com.jmtditech.groupme

import android.app.Application
import com.jmtditech.groupme.data.ServiceLocator
import com.jmtditech.groupme.service.Notifier

class GroupMeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        Notifier.createChannels(this)
    }
}
