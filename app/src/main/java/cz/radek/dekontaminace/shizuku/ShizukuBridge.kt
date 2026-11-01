package cz.radek.dekontaminace.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.IBinder
import cz.radek.dekontaminace.IShellService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

enum class ShizukuState {
    NOT_INSTALLED,
    NOT_RUNNING,
    UNSUPPORTED,
    NO_PERMISSION,
    DENIED,
    READY,
}

data class ShellResult(val exitCode: Int, val output: String) {
    val ok: Boolean get() = exitCode == 0
}

class ShizukuUnavailableException(val state: ShizukuState) :
    IllegalStateException("Shizuku není připravené ($state)")

object ShizukuBridge {

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val PERMISSION_REQUEST_CODE = 4711

    private lateinit var appContext: Context
    private val _state = MutableStateFlow(ShizukuState.NOT_RUNNING)
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var service: IShellService? = null
    private var pending: CompletableDeferred<IShellService>? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) {
                val s = IShellService.Stub.asInterface(binder)
                service = s
                pending?.complete(s)
            } else {
                pending?.completeExceptionally(IllegalStateException("Služba Shizuku vrátila neplatný binder"))
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    private val userServiceArgs by lazy {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        val debuggable = (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        Shizuku.UserServiceArgs(ComponentName(appContext.packageName, ShellService::class.java.name))
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(debuggable)
            // Shizuku znovu použije běžící službu, dokud sedí verze. Při vývoji má každá
            // instalace stejný versionCode, takže bereme čas instalace, jinak by po
            // aktualizaci dál běžel starý kód služby.
            .version((info.lastUpdateTime / 1000L).toInt())
    }

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        Shizuku.addBinderReceivedListenerSticky { refresh() }
        Shizuku.addBinderDeadListener {
            service = null
            refresh()
        }
        Shizuku.addRequestPermissionResultListener { _, _ -> refresh() }
        refresh()
    }

    fun refresh() {
        _state.value = try {
            when {
                !Shizuku.pingBinder() ->
                    if (isShizukuInstalled()) ShizukuState.NOT_RUNNING else ShizukuState.NOT_INSTALLED
                Shizuku.isPreV11() -> ShizukuState.UNSUPPORTED
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> ShizukuState.READY
                Shizuku.shouldShowRequestPermissionRationale() -> ShizukuState.DENIED
                else -> ShizukuState.NO_PERMISSION
            }
        } catch (e: Exception) {
            ShizukuState.NOT_RUNNING
        }
    }

    /** Počká, až dorazí binder Shizuku (po studeném startu procesu na pozadí chvíli trvá), a vrátí stav. */
    suspend fun awaitReady(timeoutMs: Long): ShizukuState {
        withTimeoutOrNull(timeoutMs) {
            while (!Shizuku.pingBinder()) delay(250)
        }
        refresh()
        return _state.value
    }

    fun requestPermission() {
        if (_state.value == ShizukuState.NO_PERMISSION) {
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        }
    }

    fun isShizukuInstalled(): Boolean = try {
        appContext.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private suspend fun shell(): IShellService = mutex.withLock {
        service?.let { if (it.asBinder().pingBinder()) return@withLock it }
        refresh()
        val current = _state.value
        if (current != ShizukuState.READY) throw ShizukuUnavailableException(current)

        val deferred = CompletableDeferred<IShellService>()
        pending = deferred
        Shizuku.bindUserService(userServiceArgs, connection)
        try {
            withTimeout(15_000L) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException("Služba Shizuku neodpověděla do 15 s")
        } finally {
            pending = null
        }
    }

    suspend fun exec(command: String): ShellResult {
        val s = withContext(Dispatchers.Main) { shell() }
        val raw = withContext(Dispatchers.IO) { s.exec(command) }
        val newline = raw.indexOf('\n')
        if (newline < 0) return ShellResult(-1, raw)
        return ShellResult(
            exitCode = raw.substring(0, newline).trim().toIntOrNull() ?: -1,
            output = raw.substring(newline + 1),
        )
    }
}
