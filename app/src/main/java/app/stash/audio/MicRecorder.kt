package app.stash.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import app.stash.domain.AudioFormatConstants
import app.stash.domain.Pcm
import app.stash.domain.PcmBuffer

/**
 * The phone's own microphone through AudioRecord: 16 kHz, mono, 16-bit, source MIC (the source that worked in the
 * card 24 and 25 spikes; the card suggested VOICE_RECOGNITION, which was not tried). Reads 20 ms chunks on its own
 * thread. Holds the sound in a [PcmBuffer] only: nothing is written anywhere. stop and cancel always release.
 */
class MicRecorder(private val context: Context) : Recorder {
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var buffer = PcmBuffer()
    @Volatile private var running = false

    @Synchronized
    override fun start(onChunk: (ShortArray, Int) -> Unit, onError: (MicFailure) -> Unit): MicFailure? {
        if (record != null) return MicFailure(MicFailure.Kind.START_REFUSED, 0)
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return MicFailure(MicFailure.Kind.NO_PERMISSION)
        }
        val rec = try {
            val minBuf = AudioRecord.getMinBufferSize(
                AudioFormatConstants.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(AudioFormatConstants.SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build(),
                )
                .setBufferSizeInBytes(maxOf(minBuf, AudioFormatConstants.CHUNK_SAMPLES * 2) * 4)
                .build()
        } catch (e: Exception) {
            return MicFailure(MicFailure.Kind.THREW)
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            val st = rec.state
            rec.release()
            return MicFailure(MicFailure.Kind.NOT_INITIALIZED, st)
        }
        try {
            rec.startRecording()
        } catch (e: Exception) {
            rec.release()
            return MicFailure(MicFailure.Kind.THREW)
        }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            val st = rec.recordingState
            rec.release()
            return MicFailure(MicFailure.Kind.START_REFUSED, st)
        }
        buffer = PcmBuffer()
        record = rec
        running = true
        thread = Thread({ readLoop(rec, onChunk, onError) }, "stash-mic").also { it.start() }
        return null
    }

    private fun readLoop(rec: AudioRecord, onChunk: (ShortArray, Int) -> Unit, onError: (MicFailure) -> Unit) {
        val chunk = ShortArray(AudioFormatConstants.CHUNK_SAMPLES)
        var zeroReads = 0
        while (running) {
            val n = try { rec.read(chunk, 0, chunk.size) } catch (e: Exception) { onError(MicFailure(MicFailure.Kind.THREW)); return }
            if (n < 0) { onError(MicFailure(MicFailure.Kind.READ_ERROR, n)); return }
            if (n == 0) { if (++zeroReads > 50) { onError(MicFailure(MicFailure.Kind.READ_ERROR, 0)); return }; continue }
            zeroReads = 0
            buffer.append(chunk, n)
            onChunk(chunk, n)
        }
    }

    @Synchronized
    override fun stop(): Pcm {
        halt()
        val pcm = buffer.toPcm()
        buffer.release()
        return pcm
    }

    @Synchronized
    override fun cancel() { halt(); buffer.release() }

    private fun halt() {
        running = false
        val t = thread
        if (t != null && t !== Thread.currentThread()) try { t.join(1_000) } catch (_: InterruptedException) {}
        thread = null
        record?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        record = null
    }
}
