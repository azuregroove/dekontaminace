package cz.radek.dekontaminace.files

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build

enum class OldKind(val label: String) {
    SCREENSHOTS("Screenshoty"),
    RECORDINGS("Nahrávky obrazovky"),
    DOWNLOADS("Stažené"),
}

/** Zařazení podle cesty a jména, protože Android tyhle druhy souborů nijak neoznačuje. */
fun FileEntry.oldKind(root: String): OldKind? {
    val lower = path.lowercase()
    val lowerName = name.lowercase()
    return when {
        "/screenshots/" in lower -> OldKind.SCREENSHOTS
        "/screen recordings/" in lower || "/screenrecord" in lower ||
            ("/movies/" in lower && lowerName.startsWith("screen")) -> OldKind.RECORDINGS
        path.startsWith("$root/Download/") -> OldKind.DOWNLOADS
        else -> null
    }
}

enum class ApkStatus(val label: String) {
    INSTALLED_SAME_OR_NEWER("už nainstalováno, instalačka je zbytečná"),
    INSTALLED_OLDER("nainstalovaná je starší verze"),
    NOT_INSTALLED("není nainstalováno"),
    UNREADABLE("nečitelný nebo poškozený balíček"),
}

data class ApkEntry(val file: FileEntry, val label: String?, val versionName: String?, val status: ApkStatus)

fun inspectApk(pm: PackageManager, file: FileEntry): ApkEntry {
    val archive: PackageInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(file.path, 0)
        }
    }.getOrNull()
    if (archive == null) return ApkEntry(file, null, null, ApkStatus.UNREADABLE)

    val label = archive.applicationInfo?.let { info ->
        // Bez cesty ke zdroji neumí PackageManager načíst název z nenainstalovaného APK.
        info.sourceDir = file.path
        info.publicSourceDir = file.path
        runCatching { info.loadLabel(pm).toString() }.getOrNull()
    }
    val installed: PackageInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(archive.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(archive.packageName, 0)
        }
    }.getOrNull()
    val status = when {
        installed == null -> ApkStatus.NOT_INSTALLED
        installed.longVersionCode >= archive.longVersionCode -> ApkStatus.INSTALLED_SAME_OR_NEWER
        else -> ApkStatus.INSTALLED_OLDER
    }
    return ApkEntry(file, label, archive.versionName, status)
}
