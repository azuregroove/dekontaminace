package cz.radek.dekontaminace.shizuku

import android.content.Context
import androidx.annotation.Keep
import cz.radek.dekontaminace.IShellService
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Běží v samostatném procesu, který spouští Shizuku s identitou shellu (ADB),
 * proto tu projdou příkazy jako `pm clear --cache-only`.
 */
class ShellService() : IShellService.Stub() {

    @Keep
    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context) : this()

    override fun destroy() {
        exitProcess(0)
    }

    override fun exec(command: String): String {
        // Přes binder projde jen pár typů výjimek, ostatní by se ztratily,
        // proto každé selhání vracíme jako text, který aplikace ukáže.
        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            var output = ""
            val reader = thread { output = process.inputStream.bufferedReader().use { it.readText() } }
            // Pojistka: některé systémové příkazy umí čekat navždy (např. když Android požadavek
            // tiše zahodí), a to by zablokovalo celou aplikaci.
            if (!process.waitFor(MAX_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                reader.join(1_000)
                return "124\nPříkaz nedoběhl do $MAX_SECONDS s a byl ukončen. $output"
            }
            reader.join(5_000)
            "${process.exitValue()}\n$output"
        } catch (t: Throwable) {
            "-1\n${t.javaClass.simpleName}: ${t.message}"
        }
    }

    private companion object {
        const val MAX_SECONDS = 600L
    }
}
