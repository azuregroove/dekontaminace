package cz.radek.dekontaminace.rules

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object RuleScheduler {

    private const val CACHE_WORK = "rule-cache"

    fun applyCache(context: Context, interval: RuleInterval) {
        val wm = WorkManager.getInstance(context.applicationContext)
        val days = interval.days
        if (days == null) {
            wm.cancelUniqueWork(CACHE_WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<CacheRuleWorker>(days, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        // UPDATE zachová čas posledního zařazení, změna intervalu tak nespustí úklid hned.
        wm.enqueueUniquePeriodicWork(CACHE_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
