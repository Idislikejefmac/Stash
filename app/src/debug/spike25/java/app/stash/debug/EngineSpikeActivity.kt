package app.stash.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File

/**
 * Card 25 spike (throwaway, DEBUG ONLY). Runs in the app's process; for a cold load, run
 * `adb shell am kill app.stash` first (kills only a cached background process). Finishes when done.
 *
 *   adb shell am start -n app.stash/app.stash.debug.EngineSpikeActivity --es model parakeet110m --ei threads 2
 *     [--es tag NAME] [--ez vad true] [--es decoding modified_beam_search] [--es hotwords hotwords.txt --ef hwscore 1.5]
 *     [--es src dec/opus16] [--es ids s01,s10] [--ei repeat 10] [--ei pauseMs 15000] [--es precision fp32]
 *   adb shell am start -n app.stash/app.stash.debug.EngineSpikeActivity --es mode encode     (leg C)
 *
 * Results: files/spike25/out/<tag>/summary.tsv (and enc/report.tsv); pull with run-as.
 */
class EngineSpikeActivity : ComponentActivity() {
    private lateinit var log: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        log = TextView(this).apply { textSize = 14f; setPadding(24, 40, 24, 24); setTextIsSelectable(true) }
        setContentView(ScrollView(this).apply { addView(log) })
        val i = intent
        val say: (String) -> Unit = { s -> runOnUiThread { log.append(s + "\n") } }
        val mode = i.getStringExtra("mode") ?: "run"
        Thread {
            if (mode == "mlkit") {
                Spike25MlKit(applicationContext, i.getStringExtra("tag") ?: "mlkit", i.getIntExtra("mlmode", 1),
                    i.getBooleanExtra("download", false), say).run()
            } else if (mode == "encode") {
                say("Encoding leg C...")
                Spike25Codec(File(filesDir, "spike25"), say).run()
            } else {
                val p = Spike25Runner.fromExtras(i)
                say("Engine spike: ${p.tag}")
                Spike25Runner(applicationContext, p, say).run()
            }
            say("Done.")
            SystemClock.sleep(1500)
            runOnUiThread { finish() }
        }.apply { name = "spike25"; start() }
    }
}

/**
 * Card 25 leg D12: runs the engine from WorkManager in the app's MAIN process (as a real background job
 * would). Trigger: adb shell am broadcast -n app.stash/app.stash.debug.Spike25WorkReceiver --es model X
 *   --es tag X-work --es ids s10 [--ei delaySec 30]
 */
class Spike25WorkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val req = OneTimeWorkRequestBuilder<Spike25Worker>()
            .setInputData(workDataOf(
                "model" to intent.getStringExtra("model"), "tag" to (intent.getStringExtra("tag") ?: "work"),
                "ids" to (intent.getStringExtra("ids") ?: "s10"), "threads" to intent.getIntExtra("threads", 2),
                "vad" to intent.getBooleanExtra("vad", false),
                "enqueuedAt" to System.currentTimeMillis(),
            ))
            .setInitialDelay(intent.getIntExtra("delaySec", 0).toLong(), java.util.concurrent.TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueue(req)
    }
}

class Spike25Worker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val d = inputData
        val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val started = System.currentTimeMillis()
        val interactiveAtStart = pm.isInteractive
        val p = Spike25Runner.Params(
            model = d.getString("model") ?: return Result.failure(), tag = d.getString("tag") ?: "work",
            threads = d.getInt("threads", 2), vad = d.getBoolean("vad", false),
            ids = (d.getString("ids") ?: "s10").split(','), warmLoad = false,
        )
        Spike25Runner(applicationContext, p) { }.run()
        File(applicationContext.filesDir, "spike25/out/${p.tag}/work.txt").writeText(
            "enqueuedAt=${d.getLong("enqueuedAt", 0)} startedAt=$started endedAt=${System.currentTimeMillis()}" +
                " interactiveAtStart=$interactiveAtStart interactiveAtEnd=${pm.isInteractive} pid=${Process.myPid()}\n"
        )
        return Result.success()
    }
}
