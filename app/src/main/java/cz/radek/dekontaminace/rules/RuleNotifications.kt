package cz.radek.dekontaminace.rules

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cz.radek.dekontaminace.MainActivity
import cz.radek.dekontaminace.R

object RuleNotifications {

    private const val CHANNEL = "rules"
    private const val CACHE_ID = 3001

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun cacheRun(context: Context, run: RuleRun) {
        if (!canNotify(context)) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Pravidla úklidu", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Souhrny automatických dekontaminací"
            },
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_dekontaminace)
            .setContentTitle(if (run.ok) "Dekontaminace cache dokončena" else "Pravidlo cache selhalo")
            .setContentText(run.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(run.text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(CACHE_ID, notification)
        } catch (e: SecurityException) {
            // Oprávnění mohlo zmizet mezi kontrolou a odesláním; výsledek je stejně uložený v aplikaci.
        }
    }
}
