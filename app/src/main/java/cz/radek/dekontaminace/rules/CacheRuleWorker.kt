package cz.radek.dekontaminace.rules

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class CacheRuleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val run = CacheRule.run(applicationContext, manual = false)
        RuleNotifications.cacheRun(applicationContext, run)
        // I neúspěch vracíme jako úspěch: opakování by Shizuku nenahodilo, jen by spamovalo oznámeními.
        return Result.success()
    }
}
