package app.stash.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.stash.StashApp
import app.stash.data.TranscriptionStatus
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * Debug builds only (2026-11-16, the owner asked for it). Writes out again a recorded note that came out with NO words,
 * through the app's own path (`Container.writeOutAgain`, the call card 26h's "Write it out again" button will make),
 * then uses the new words (`TranscriptionRepository.use`, as the owner's "Use these words" would). Refused for a note
 * that has any words, so nothing the owner said is ever replaced by this tool.
 *
 *   adb shell am broadcast -n app.stash/app.stash.debug.WriteOutAgainReceiver --es captureId <id>
 *
 * Output: `files/debug/write-out-again.json` (ids, states and lengths only, no words).
 */
class WriteOutAgainReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        thread(name = "write-out-again") {
            val out = JSONObject().put("at", System.currentTimeMillis())
            try {
                run(context, intent.getStringExtra("captureId").orEmpty(), out)
            } catch (e: Throwable) {
                out.put("error", e.javaClass.simpleName)
            } finally {
                File(context.filesDir, "debug/write-out-again.json").apply { parentFile?.mkdirs() }.writeText(out.toString(2))
                pending.finish()
            }
        }
    }

    private fun run(context: Context, captureId: String, out: JSONObject) = runBlocking {
        val c = (context.applicationContext as StashApp).container
        out.put("captureId", captureId)
        val before = c.repo.captures.get(captureId) ?: return@runBlocking out.put("result", "NO_SUCH_NOTE")
        if (before.text.isNotBlank()) return@runBlocking out.put("result", "REFUSED_HAS_WORDS")
        val existing = c.repo.transcriptions.history(captureId).map { it.id }.toSet()
        out.put("askAgain", c.writeOutAgain(captureId).name)
        var offered: String? = null
        for (i in 0 until 120) { // up to a minute for the pass
            offered = c.repo.transcriptions.history(captureId)
                .firstOrNull { it.id !in existing && it.status == TranscriptionStatus.OFFERED }?.id
            val replaced = c.repo.transcriptions.history(captureId).firstOrNull { it.id !in existing && it.status == TranscriptionStatus.REPLACED }
            if (offered != null) break
            if (replaced != null) return@runBlocking out.put("result", "NO_WORDS_AGAIN").put("chars", replaced.text.length)
            Thread.sleep(500)
        }
        if (offered == null) return@runBlocking out.put("result", "TIMED_OUT")
        out.put("used", c.repo.transcriptions.use(offered))
        val after = c.repo.captures.get(captureId)
        out.put("result", "DONE").put("chars", after?.text?.length ?: -1).put("extractState", after?.extractState?.name ?: "")
    }
}
