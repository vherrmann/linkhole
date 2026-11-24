package com.vherrmann.linkhole.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.vherrmann.linkhole.data.Item
import java.net.URI

fun Item.displayTitle() = title?.takeIf { it.isNotBlank() } ?: url

fun Item.host(): String = try {
    URI(url).host?.removePrefix("www.") ?: url
} catch (e: Exception) {
    url
}

fun age(thenMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val s = (nowMs - thenMs).coerceAtLeast(0) / 1000
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86400 -> "${s / 3600} h ago"
        else -> "${s / 86400} d ago"
    }
}

fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Toast.makeText(context, "Nothing can open this link", Toast.LENGTH_SHORT).show()
    }
}
