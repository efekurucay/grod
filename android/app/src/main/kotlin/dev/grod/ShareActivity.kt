package dev.grod

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * Share target for YouTube links: YouTube → Share → grod blocks the video's channel. It shows no UI;
 * it finishes at once and reports with a Toast once the channel is resolved and saved.
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.takeIf { it.action == Intent.ACTION_SEND }?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        finish()
        val app = applicationContext
        val url = YouTubeLinks.extractUrl(text) ?: return toast(app, "grod: paylaşılan metinde YouTube bağlantısı yok")
        val main = Handler(Looper.getMainLooper())
        thread(name = "grod-share", isDaemon = true) {
            val channel = try {
                ChannelResolver.resolve(url)
            } catch (e: Exception) {
                main.post { toast(app, "grod: kanal bulunamadı (${e.message ?: e.javaClass.simpleName})") }
                return@thread
            }
            main.post {
                RuleStore.get(app).addLocalChannel(
                    channel,
                    done = { added -> toast(app, "grod: ${channel.name} " + if (added) "engellendi" else "zaten engelli") },
                    failed = { toast(app, "grod: ${channel.name} kaydedilemedi ($it)") },
                )
            }
        }
    }

    private fun toast(context: Context, message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}
