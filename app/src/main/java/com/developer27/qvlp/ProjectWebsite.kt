package com.developer27.qvlp

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast

internal fun Activity.openProjectWebsite() {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.zhangxiao.me/")))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, R.string.project_website_unavailable, Toast.LENGTH_LONG).show()
    }
}
