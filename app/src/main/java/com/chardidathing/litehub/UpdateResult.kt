package com.chardidathing.litehub

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

// android's answer to an update handed to its installer. most of the time the first answer is
// "the person has to agree", so its prompt is put on screen
class UpdateResult : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val prompt = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            context.startActivity(prompt.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        (context.applicationContext as LitehubApp).updater.onResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
