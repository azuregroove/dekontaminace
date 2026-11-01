package cz.radek.dekontaminace.data

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.storage.StorageManager

data class AppEntry(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val appBytes: Long,
    /** Uživatelská data bez cache. */
    val dataBytes: Long,
    val cacheBytes: Long,
) {
    val totalBytes: Long get() = appBytes + dataBytes + cacheBytes
}

data class StorageSummary(
    val totalBytes: Long,
    val freeBytes: Long,
    val appsBytes: Long,
    val mediaBytes: Long,
    val externalAppBytes: Long,
) {
    val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0)
    val otherBytes: Long get() = (usedBytes - appsBytes - mediaBytes).coerceAtLeast(0)
}

class StorageRepository(private val context: Context) {

    private val statsManager = context.getSystemService(StorageStatsManager::class.java)
    private val packageManager = context.packageManager

    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            context.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }

    fun freeBytes(): Long = statsManager.getFreeBytes(StorageManager.UUID_DEFAULT)

    fun summary(apps: List<AppEntry>, usageAccess: Boolean): StorageSummary {
        val external = if (usageAccess) {
            runCatching {
                statsManager.queryExternalStatsForUser(StorageManager.UUID_DEFAULT, Process.myUserHandle())
            }.getOrNull()
        } else {
            null
        }
        return StorageSummary(
            totalBytes = statsManager.getTotalBytes(StorageManager.UUID_DEFAULT),
            freeBytes = freeBytes(),
            appsBytes = apps.sumOf { it.totalBytes },
            mediaBytes = external?.let { it.audioBytes + it.videoBytes + it.imageBytes } ?: 0L,
            externalAppBytes = external?.appBytes ?: 0L,
        )
    }

    /** Pomalé (sekundy), volat jen mimo hlavní vlákno. */
    fun installedApps(): List<AppEntry> {
        val user = Process.myUserHandle()
        return installedApplicationInfos().mapNotNull { info ->
            runCatching {
                val stats = statsManager.queryStatsForPackage(info.storageUuid, info.packageName, user)
                AppEntry(
                    packageName = info.packageName,
                    label = info.loadLabel(packageManager).toString(),
                    isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    appBytes = stats.appBytes,
                    // getDataBytes() už cache obsahuje, tak ji odečítáme, ať se nepočítá dvakrát.
                    dataBytes = (stats.dataBytes - stats.cacheBytes).coerceAtLeast(0),
                    cacheBytes = stats.cacheBytes,
                )
            }.getOrNull()
        }
    }

    fun cacheBytesFor(packages: Collection<String>): Map<String, Long> {
        val user = Process.myUserHandle()
        return packages.associateWith { pkg ->
            runCatching {
                val info = packageManager.getApplicationInfo(pkg, 0)
                statsManager.queryStatsForPackage(info.storageUuid, pkg, user).cacheBytes
            }.getOrDefault(0L)
        }
    }

    private fun installedApplicationInfos(): List<ApplicationInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledApplications(0)
        }
}
