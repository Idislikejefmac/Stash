package app.stash.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.stash.StashApp
import app.stash.speech.ImportResult
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * Debug builds only (card 26d item 10, the PC route). Imports a speech model pack zip that was copied into the
 * app's files with adb, through the same [app.stash.speech.ModelImporter] as Settings' file picker:
 *
 *   adb exec-in run-as app.stash sh -c 'mkdir -p files/model-import && cat > files/model-import/pack.zip' < pack.zip
 *   adb shell am broadcast -n app.stash/app.stash.debug.ModelImportReceiver
 *
 * (`python tools/make_model_pack.py <archive> --push` does both.) Writes `files/debug/model-import.json`: the result,
 * the pack id, bytes and milliseconds, and what `files/models/` holds afterwards (names and sizes). The zip is deleted
 * afterwards either way: the PC keeps the original. Nothing here opens a network connection.
 */
class ModelImportReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        thread(name = "model-import") {
            val out = JSONObject()
            try {
                val container = (context.applicationContext as StashApp).container
                val zip = File(context.filesDir, intent.getStringExtra("file") ?: DEFAULT_ZIP)
                out.put("zip", zip.name).put("zipBytes", zip.length())
                if (!zip.isFile) {
                    out.put("result", "NO_FILE")
                } else {
                    container.transcriber.release()
                    val t0 = System.nanoTime()
                    val r = zip.inputStream().use { container.modelImporter.import(it) }
                    out.put("wallMs", (System.nanoTime() - t0) / 1_000_000)
                    when (r) {
                        is ImportResult.Installed -> out.put("result", "INSTALLED").put("pack", r.pack.id).put("bytes", r.bytes).put("ms", r.ms)
                        is ImportResult.Failed -> out.put("result", r.reason.name).put("file", r.file ?: "")
                    }
                    zip.delete()
                }
                out.put("installed", container.modelLocator.find()?.pack?.id ?: "")
                out.put("availability", container.transcriber.availability().toString())
                out.put("modelsDir", listing(container.modelPacks.root))
            } catch (e: Throwable) {
                out.put("result", "THREW").put("error", e.javaClass.simpleName)
            } finally {
                File(context.filesDir, "debug/model-import.json").apply { parentFile?.mkdirs() }.writeText(out.toString(2))
                pending.finish()
            }
        }
    }

    private fun listing(root: File): String =
        root.walkTopDown().filter { it != root }.joinToString("; ") { f ->
            f.relativeTo(root).invariantSeparatorsPath + if (f.isFile) " ${f.length()}" else "/"
        }

    companion object {
        const val DEFAULT_ZIP = "model-import/pack.zip"
    }
}
