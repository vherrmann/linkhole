package com.vherrmann.linkhole

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.vherrmann.linkhole.data.UrlExtractor
import kotlinx.coroutines.launch

/** Receives shared text, queues the first URL in it and finishes without UI. */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = UrlExtractor.firstUrl(intent?.getStringExtra(Intent.EXTRA_TEXT))
        if (url == null) {
            Toast.makeText(this, "No link found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        lifecycleScope.launch {
            val added = (application as App).repo.addUrl(url)
            val msg = if (added) "Added to Linkhole" else "Already in Linkhole"
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
