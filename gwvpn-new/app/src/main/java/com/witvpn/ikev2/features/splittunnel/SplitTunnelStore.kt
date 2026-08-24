package com.witvpn.ikev2.features.splittunnel

import android.content.Context
import android.content.pm.PackageManager
import java.util.SortedSet
import java.util.TreeSet

/**
 * Split Tunneling operating mode.
 *
 * [OFF] — the feature is disabled, every application is routed through the
 * VPN tunnel. This is the default for fresh installs.
 * [ONLY_SELECTED] — only the applications the user ticked are routed through
 * the tunnel, everything else connects directly.
 * [EXCEPT_SELECTED] — every application is routed through the tunnel except
 * the ones the user ticked.
 */
enum class SplitTunnelMode(val prefValue: String) {
    OFF("off"),
    ONLY_SELECTED("only_selected"),
    EXCEPT_SELECTED("except_selected");

    companion object {
        @JvmStatic
        fun fromPrefValue(value: String?): SplitTunnelMode? =
            values().firstOrNull { it.prefValue == value }
    }
}

/**
 * Single source of truth for the Split Tunneling feature.
 *
 * Model: the user picks one of three [SplitTunnelMode]s. Each selection mode
 * keeps its own independent app list, so switching between "Only selected"
 * and "All except selected" never silently reinterprets the user's ticks:
 *  - [SplitTunnelMode.EXCEPT_SELECTED] uses the legacy `bypass_packages` set
 *    (apps that go around the tunnel);
 *  - [SplitTunnelMode.ONLY_SELECTED] uses the `only_packages` set (the only
 *    apps that go through the tunnel).
 *
 * Backends must not read the raw sets directly — they use the effective
 * routing API ([getEffectiveMode], [getDisallowedPackagesSorted],
 * [getAllowedPackagesSorted] and the installed-filtered variants), which
 * collapses degenerate states (an empty selection behaves exactly like
 * [SplitTunnelMode.OFF], so an empty "Only selected" list can never
 * accidentally capture the whole device, and the VPN app itself is never part
 * of an allow list).
 *
 * The state is persisted in its own SharedPreferences file. All VPN backends
 * (IKEv2/strongSwan, VLESS/Xray and AmneziaWG) read it at connect time, so
 * changes made while a tunnel is up take effect after the next reconnect
 * (the UI informs the user about that).
 *
 * Migration: builds that shipped before the mode selector only stored
 * `bypass_packages`. A non-empty legacy set was a deliberate user choice, so
 * it migrates to [SplitTunnelMode.EXCEPT_SELECTED]; otherwise the mode
 * defaults to [SplitTunnelMode.OFF].
 */
object SplitTunnelStore {

    private const val PREFS_NAME = "split_tunnel_prefs"
    private const val KEY_MODE = "mode"

    /** Apps that bypass the tunnel in [SplitTunnelMode.EXCEPT_SELECTED] (legacy key). */
    private const val KEY_BYPASS_PACKAGES = "bypass_packages"

    /** Apps routed through the tunnel in [SplitTunnelMode.ONLY_SELECTED]. */
    private const val KEY_ONLY_PACKAGES = "only_packages"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------------
    // Mode
    // ---------------------------------------------------------------------

    /** The mode the user picked on the Split Tunneling screen. */
    @JvmStatic
    fun getMode(context: Context): SplitTunnelMode {
        val p = prefs(context)
        val stored = SplitTunnelMode.fromPrefValue(p.getString(KEY_MODE, null))
        if (stored != null) return stored

        // One-time migration from builds without the mode selector: a
        // non-empty bypass set means the user deliberately excluded apps.
        val migrated = if (readSet(context, KEY_BYPASS_PACKAGES).isNotEmpty()) {
            SplitTunnelMode.EXCEPT_SELECTED
        } else {
            SplitTunnelMode.OFF
        }
        p.edit().putString(KEY_MODE, migrated.prefValue).apply()
        return migrated
    }

    @JvmStatic
    fun setMode(context: Context, mode: SplitTunnelMode) {
        prefs(context).edit().putString(KEY_MODE, mode.prefValue).apply()
    }

    // ---------------------------------------------------------------------
    // Selection lists (what the UI edits)
    // ---------------------------------------------------------------------

    /** The selection list backing the given mode; empty for [SplitTunnelMode.OFF]. */
    @JvmStatic
    fun getSelectedPackages(context: Context, mode: SplitTunnelMode): Set<String> =
        when (mode) {
            SplitTunnelMode.OFF -> emptySet()
            SplitTunnelMode.ONLY_SELECTED -> readSet(context, KEY_ONLY_PACKAGES)
            SplitTunnelMode.EXCEPT_SELECTED -> readSet(context, KEY_BYPASS_PACKAGES)
        }

    /** Adds or removes a single package from the given mode's selection list. */
    @JvmStatic
    fun setSelected(context: Context, mode: SplitTunnelMode, packageName: String, selected: Boolean) {
        val key = when (mode) {
            SplitTunnelMode.OFF -> return // no list to edit while the feature is off
            SplitTunnelMode.ONLY_SELECTED -> KEY_ONLY_PACKAGES
            SplitTunnelMode.EXCEPT_SELECTED -> KEY_BYPASS_PACKAGES
        }
        val current = readSet(context, key).toMutableSet()
        val changed = if (selected) current.add(packageName) else current.remove(packageName)
        if (changed) {
            writeSet(context, key, current)
        }
    }

    /**
     * Drops packages that are no longer installed from both selection lists.
     * Called when the app list is (re)loaded so the stored sets do not
     * accumulate stale entries after uninstalls.
     */
    @JvmStatic
    fun prune(context: Context, installedPackages: Collection<String>) {
        for (key in arrayOf(KEY_BYPASS_PACKAGES, KEY_ONLY_PACKAGES)) {
            val current = readSet(context, key)
            if (current.isEmpty()) continue
            val pruned = current.filterTo(HashSet()) { installedPackages.contains(it) }
            if (pruned.size != current.size) {
                writeSet(context, key, pruned)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Effective routing API (what the VPN backends consume)
    // ---------------------------------------------------------------------

    /**
     * The mode that must actually be applied to the tunnel. Degenerate states
     * collapse to [SplitTunnelMode.OFF]:
     *  - an empty "Only selected" list would otherwise make Android route the
     *    whole device through the tunnel (an empty allow list means "all"),
     *    which is the opposite of what the user asked for;
     *  - an empty "All except selected" list is simply equivalent to OFF.
     */
    @JvmStatic
    fun getEffectiveMode(context: Context): SplitTunnelMode {
        val mode = getMode(context)
        return when (mode) {
            SplitTunnelMode.OFF -> SplitTunnelMode.OFF
            SplitTunnelMode.ONLY_SELECTED ->
                if (allowedPackages(context).isEmpty()) SplitTunnelMode.OFF else mode
            SplitTunnelMode.EXCEPT_SELECTED ->
                if (readSet(context, KEY_BYPASS_PACKAGES).isEmpty()) SplitTunnelMode.OFF else mode
        }
    }

    /**
     * Packages that must go around the tunnel
     * (VpnService.Builder.addDisallowedApplication). Non-empty only when the
     * effective mode is [SplitTunnelMode.EXCEPT_SELECTED].
     */
    @JvmStatic
    fun getDisallowedPackagesSorted(context: Context): SortedSet<String> =
        if (getEffectiveMode(context) == SplitTunnelMode.EXCEPT_SELECTED) {
            TreeSet(readSet(context, KEY_BYPASS_PACKAGES))
        } else {
            TreeSet()
        }

    /**
     * The only packages that must go through the tunnel
     * (VpnService.Builder.addAllowedApplication). Non-empty only when the
     * effective mode is [SplitTunnelMode.ONLY_SELECTED]. Never contains the
     * VPN app itself: our own traffic (config fetchers, the Xray/WireGuard
     * userspace engines) must stay outside the tunnel it creates.
     */
    @JvmStatic
    fun getAllowedPackagesSorted(context: Context): SortedSet<String> =
        if (getEffectiveMode(context) == SplitTunnelMode.ONLY_SELECTED) {
            TreeSet(allowedPackages(context))
        } else {
            TreeSet()
        }

    /**
     * Same as [getDisallowedPackagesSorted] but filtered to packages actually
     * resolvable right now. Backends that don't tolerate unknown package names
     * in addDisallowedApplication (AmneziaWG's GoBackend fails the whole
     * tunnel on NameNotFoundException) must use this variant.
     */
    @JvmStatic
    fun getInstalledDisallowedPackages(context: Context): Set<String> =
        filterInstalled(context, getDisallowedPackagesSorted(context))

    /** Same as [getAllowedPackagesSorted] but filtered to installed packages. */
    @JvmStatic
    fun getInstalledAllowedPackages(context: Context): Set<String> =
        filterInstalled(context, getAllowedPackagesSorted(context))

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private fun allowedPackages(context: Context): Set<String> {
        val ownPackage = context.applicationContext.packageName
        return readSet(context, KEY_ONLY_PACKAGES).filterTo(LinkedHashSet()) { it != ownPackage }
    }

    private fun readSet(context: Context, key: String): Set<String> =
        prefs(context).getStringSet(key, emptySet())?.toSet() ?: emptySet()

    private fun writeSet(context: Context, key: String, packages: Set<String>) {
        // Always write a fresh copy: mutating the instance returned by
        // getStringSet() is not allowed and may be silently ignored.
        prefs(context).edit()
            .putStringSet(key, HashSet(packages))
            .apply()
    }

    private fun filterInstalled(context: Context, packages: Collection<String>): Set<String> {
        if (packages.isEmpty()) return emptySet()
        val pm = context.packageManager
        return packages.filterTo(LinkedHashSet()) { pkg ->
            try {
                pm.getApplicationInfo(pkg, 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            } catch (e: Exception) {
                // Be conservative on unexpected PM failures — skipping an entry
                // only weakens split tunneling for that app, while passing an
                // unresolvable name could break tunnel establishment.
                false
            }
        }
    }
}
