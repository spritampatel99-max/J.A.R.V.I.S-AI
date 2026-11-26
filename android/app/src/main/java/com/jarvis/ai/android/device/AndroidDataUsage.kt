package com.jarvis.ai.android.device

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.RemoteException
import android.util.Log
import com.jarvis.ai.datausage.AppLabel
import com.jarvis.ai.datausage.DataUsageMath
import com.jarvis.ai.datausage.DataUsagePort
import com.jarvis.ai.datausage.DataUsageReport
import com.jarvis.ai.datausage.DataUsageResult
import com.jarvis.ai.datausage.UidUsage
import com.jarvis.ai.datausage.UsagePeriod
import com.jarvis.ai.datausage.UsageRange

/**
 * Reads how much mobile data the phone and each app used, from Android's own counters (NetworkStatsManager). Android allows this only after the
 * person turns on "Usage access" for the app in Settings. Only mobile data is read, never Wi-Fi, and only byte counts: not what was sent, not
 * which sites, and not how long any app was on screen.
 *
 * Not tried on a real phone yet. Android keeps these counters in time slices, so the edges of "today" can be off by a short slice, and on a phone
 * with two SIMs the figure is for all of them together.
 */
class AndroidDataUsage(private val context: Context) : DataUsagePort {

    override fun hasAccess(): Boolean = AccessChecks.state(context, AccessKey.USAGE_ACCESS) == AccessState.ALLOWED

    // NetworkStatsManager still takes the old ConnectivityManager.TYPE_MOBILE constant as its network type; Android offers no other way to ask for mobile data.
    @Suppress("DEPRECATION")
    override fun read(period: UsagePeriod, range: UsageRange, appLimit: Int): DataUsageResult {
        if (!hasAccess()) return DataUsageResult.NeedsAccess
        val manager = context.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager
            ?: return DataUsageResult.Unavailable("This phone has no data-usage service.")
        return try {
            // A null subscriber id means "all mobile data on this phone".
            val total = manager.querySummaryForDevice(ConnectivityManager.TYPE_MOBILE, null, range.startMs, range.endMs)
            val rows = ArrayList<UidUsage>()
            manager.querySummary(ConnectivityManager.TYPE_MOBILE, null, range.startMs, range.endMs).use { stats ->
                val bucket = NetworkStats.Bucket()
                while (stats.hasNextBucket()) {
                    stats.getNextBucket(bucket)
                    rows += UidUsage(bucket.uid, bucket.rxBytes, bucket.txBytes)
                }
            }
            val names = HashMap<Int, AppLabel>()
            val apps = DataUsageMath.topApps(rows, { uid -> names.getOrPut(uid) { label(uid) } }, appLimit)
            DataUsageResult.Report(DataUsageReport(period, range, total.rxBytes, total.txBytes, apps))
        } catch (e: SecurityException) {
            // Access was taken away while reading, or this phone does not allow it.
            DataUsageResult.NeedsAccess
        } catch (e: RemoteException) {
            Log.w(TAG, "Network stats service failed: ${e.javaClass.simpleName}")
            DataUsageResult.Unavailable("Android's data-usage service did not answer. Try again in a moment.")
        } catch (e: RuntimeException) {
            Log.w(TAG, "Reading data usage failed: ${e.javaClass.simpleName}")
            DataUsageResult.Unavailable("Android would not give the data-usage numbers (${e.javaClass.simpleName}).")
        }
    }

    /** A readable name for a uid. Apps this app is not allowed to see by name are shown honestly as "Other app", never guessed. */
    private fun label(uid: Int): AppLabel {
        when (uid) {
            NetworkStats.Bucket.UID_REMOVED -> return AppLabel("removed", "Removed apps")
            NetworkStats.Bucket.UID_TETHERING -> return AppLabel("tethering", "Hotspot sharing")
            ANDROID_SYSTEM_UID -> return AppLabel("system", "Android system")
        }
        val pm = context.packageManager
        val packages = pm.getPackagesForUid(uid)
        val first = packages?.firstOrNull() ?: return AppLabel("uid$uid", "Other app")
        return try {
            AppLabel(first, pm.getApplicationLabel(pm.getApplicationInfo(first, PackageManager.ApplicationInfoFlags.of(0))).toString())
        } catch (e: PackageManager.NameNotFoundException) {
            AppLabel(first, first)
        }
    }

    private companion object {
        const val TAG = "AndroidDataUsage"
        const val ANDROID_SYSTEM_UID = 1000
    }
}
