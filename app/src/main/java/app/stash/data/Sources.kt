package app.stash.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import app.stash.model.Extractor

/** Where the phone was when a note was made. Never leaves the phone. */
data class LocationFix(val lat: Double, val lon: Double, val accuracyM: Double?)

/** The location ability behind its interface: the real one, or one that never knows (docs/cards/13 C item 10). */
interface LocationSource {
    fun lastKnown(): LocationFix?
}

object NoLocation : LocationSource {
    override fun lastKnown(): LocationFix? = null
}

class AndroidLocationSource(private val context: Context) : LocationSource {
    @SuppressLint("MissingPermission")
    override fun lastKnown(): LocationFix? {
        val ok = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!ok) return null
        return try {
            val lm = context.getSystemService(LocationManager::class.java)
            lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
                ?.let { LocationFix(it.latitude, it.longitude, if (it.hasAccuracy()) it.accuracy.toDouble() else null) }
        } catch (e: Exception) {
            null
        }
    }
}

/** The hosted model ability behind its interface. Card 04 supplies the real one; until then there is none. */
fun interface HostedExtractorProvider {
    /** The hosted extractor, or null if none is registered. Whether the capability is on is decided by the pipeline. */
    fun extractor(): Extractor?
}

object NoHostedExtractor : HostedExtractorProvider {
    override fun extractor(): Extractor? = null
}
