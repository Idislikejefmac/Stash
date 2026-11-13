package app.stash.data

import android.content.Context
import android.content.pm.PackageManager
import app.stash.domain.Capability
import app.stash.domain.CapabilityRules
import app.stash.domain.Dbg
import app.stash.domain.DbgCode
import app.stash.domain.DbgComponent
import app.stash.domain.Outbound
import app.stash.domain.OutboundGate
import app.stash.domain.OutboundRefused
import app.stash.domain.OutboundStats
import java.time.Instant
import java.time.ZoneId

/** The owner's switch for each capability. */
interface SwitchStore {
    fun isSwitchOn(c: Capability): Boolean
    fun setSwitch(c: Capability, on: Boolean)
}

fun interface PermissionCheck {
    fun granted(permission: String): Boolean
}

class AndroidPermissionCheck(private val context: Context) : PermissionCheck {
    override fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}

/** What this build allows. All on today; a lighter product tier would remove some here. */
object BuildCapabilities {
    val allowed: Set<Capability> = Capability.entries.toSet()
}

/**
 * The one answer to "may the app use this?": the owner's switch AND the permission AND the build. All feature code
 * asks [isOn]; nothing else checks a permission or a setting (docs/cards/13 C).
 */
class Capabilities(
    private val switches: SwitchStore,
    private val permissions: PermissionCheck,
    private val build: Set<Capability> = BuildCapabilities.allowed,
) {
    fun permissionGranted(c: Capability): Boolean = c.permissions.isEmpty() || c.permissions.any { permissions.granted(it) }

    /** The owner's switch and the build, ignoring the permission (used to decide whether to ask for it). */
    fun enabled(c: Capability): Boolean = switches.isSwitchOn(c) && c in build

    fun isOn(c: Capability): Boolean = CapabilityRules.isOn(switches.isSwitchOn(c), permissionGranted(c), c in build)

    fun setSwitch(c: Capability, on: Boolean) = switches.setSwitch(c, on)
    fun switchOn(c: Capability): Boolean = switches.isSwitchOn(c)
}

/** Counts every outbound request by kind (docs/cards/13 C3). Never holds a word. */
interface OutboundCounter {
    fun record(kind: Outbound, nowMs: Long)
    fun stats(kind: Outbound): OutboundStats?
}

/** Kept in a small preferences file, `outbound_count`: per kind, the month, the count this month, and when last. */
class PrefsOutboundCounter(context: Context, private val zone: () -> ZoneId = { ZoneId.systemDefault() }) : OutboundCounter {
    private val prefs = context.getSharedPreferences("outbound_count", Context.MODE_PRIVATE)

    @Synchronized
    override fun record(kind: Outbound, nowMs: Long) {
        val month = monthOf(nowMs, zone())
        val sameMonth = prefs.getString("${kind.name}.month", null) == month
        val n = if (sameMonth) prefs.getInt("${kind.name}.n", 0) + 1 else 1
        prefs.edit().putString("${kind.name}.month", month).putInt("${kind.name}.n", n).putLong("${kind.name}.last", nowMs).apply()
    }

    @Synchronized
    override fun stats(kind: Outbound): OutboundStats? {
        val month = prefs.getString("${kind.name}.month", null) ?: return null
        val last = if (prefs.contains("${kind.name}.last")) prefs.getLong("${kind.name}.last", 0) else null
        return OutboundStats(month, prefs.getInt("${kind.name}.n", 0), last)
    }

    companion object {
        fun monthOf(ms: Long, zone: ZoneId): String =
            Instant.ofEpochMilli(ms).atZone(zone).let { "%04d-%02d".format(it.year, it.monthValue) }
    }
}

/** A counter that lives only in memory (tests). */
class MemoryOutboundCounter(private val zone: ZoneId = ZoneId.of("UTC")) : OutboundCounter {
    private val map = mutableMapOf<Outbound, OutboundStats>()

    @Synchronized
    override fun record(kind: Outbound, nowMs: Long) {
        val month = PrefsOutboundCounter.monthOf(nowMs, zone)
        val old = map[kind]
        map[kind] = OutboundStats(month, if (old?.month == month) old.countThisMonth + 1 else 1, nowMs)
    }

    @Synchronized
    override fun stats(kind: Outbound): OutboundStats? = map[kind]
}

/** Refuses a request whose capability is off, before anything is opened; counts one that is allowed. */
class CapabilityGate(
    private val capabilities: Capabilities,
    private val counter: OutboundCounter,
    private val clock: Clock = Clock.System,
) : OutboundGate {
    override fun permit(kind: Outbound) {
        if (!capabilities.isOn(kind.capability)) {
            Dbg.event(DbgComponent.OUTBOUND, DbgCode.REFUSED, n1 = kind.ordinal)
            throw OutboundRefused(kind)
        }
        counter.record(kind, clock.now())
    }
}
