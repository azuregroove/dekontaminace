package cz.radek.dekontaminace.cargo

enum class EntryKind { DIR, FILE, LINK }

data class CargoEntry(
    val path: String,
    val name: String,
    val kind: EntryKind,
    val bytes: Long,
    val modified: Long,
)

/** Velikosti složek jedné aplikace v Android/data a Android/obb; null = složka neexistuje. */
data class CargoSizes(val packageName: String, val dataBytes: Long?, val obbBytes: Long?) {
    val totalBytes: Long get() = (dataBytes ?: 0L) + (obbBytes ?: 0L)
}

data class Listing(val entries: List<CargoEntry>, val missing: List<String>, val errors: List<String>)

/**
 * Skripty pro shell (Shizuku) a čtení jejich výstupu. Všechno, co jde do shellu, prochází
 * [quote] a [isMovable], protože jména souborů ve hrách bývají divoká.
 */
object Cargo {

    const val STORAGE = "/storage/emulated/0"
    private val PACKAGE = Regex("^[A-Za-z0-9_.]+$")

    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    fun isPackageName(s: String): Boolean = PACKAGE.matches(s)

    fun dataDir(pkg: String, root: String = STORAGE) = "$root/Android/data/$pkg"
    fun obbDir(pkg: String, root: String = STORAGE) = "$root/Android/obb/$pkg"

    /**
     * Přesouvat smíme jen věci UVNITŘ složky aplikace, nikdy složku aplikace samotnou:
     * znovu založená shellem by měla špatného vlastníka a aplikace by do ní nemusela smět psát.
     */
    fun isMovable(path: String, ownPackage: String, root: String = STORAGE): Boolean {
        if (path.any { it == '\n' || it == '\u0000' }) return false
        val prefix = "$root/Android/"
        if (!path.startsWith(prefix)) return false
        val parts = path.removePrefix(prefix).split('/')
        if (parts.size < 3) return false
        if (parts[0] != "data" && parts[0] != "obb") return false
        if (!isPackageName(parts[1]) || parts[1] == ownPackage) return false
        return parts.drop(2).none { it.isEmpty() || it == "." || it == ".." }
    }

    /** Balíček a oblast (data/obb) z cesty, nebo null. */
    fun ownerOf(path: String, root: String = STORAGE): Pair<String, String>? {
        val parts = path.removePrefix("$root/Android/").split('/')
        if (!path.startsWith("$root/Android/") || parts.size < 2) return null
        if (parts[0] != "data" && parts[0] != "obb") return null
        return parts[1] to parts[0]
    }

    /** Velikost všech složek v data a obb, případně jen vybraných balíčků. */
    fun scanScript(root: String = STORAGE, packages: Collection<String>? = null): String {
        val pkgs = packages?.filter { isPackageName(it) }
        val loop = if (pkgs == null) "for d in \"\$t\"/*; do p=\${d#*/}"
        else "for p in ${pkgs.joinToString(" ")}; do d=\"\$t/\$p\""
        return buildString {
            append("cd ${quote("$root/Android")} || exit 3\n")
            append("for t in data obb; do [ -d \"\$t\" ] || continue\n")
            append("  $loop; [ -d \"\$d\" ] || continue\n")
            append("    s=\$(du -sk \"\$d\" 2>/dev/null | cut -f1)\n")
            append("    echo \"S|\$t|\${s:-?}|\$p\"\n")
            append("  done\ndone\n")
        }
    }

    fun parseScan(output: String): List<CargoSizes> {
        val data = HashMap<String, Long>()
        val obb = HashMap<String, Long>()
        output.lineSequence().forEach { line ->
            val p = line.split('|', limit = 4)
            if (p.size != 4 || p[0] != "S" || !isPackageName(p[3])) return@forEach
            val bytes = (p[2].trim().toLongOrNull() ?: 0L) * 1024L
            when (p[1]) {
                "data" -> data[p[3]] = bytes
                "obb" -> obb[p[3]] = bytes
            }
        }
        return (data.keys + obb.keys).map { CargoSizes(it, data[it], obb[it]) }
    }

    /**
     * Obsah složek. U složek vrací du v KiB (shell Androidu počítá jen 32bitově,
     * gigabajty v bajtech by přetekly), u souborů přesnou velikost v bajtech.
     */
    fun listScript(dirs: List<String>): String = buildString {
        append("for D in ${dirs.joinToString(" ") { quote(it) }}; do\n")
        append("  echo \"DIR|\$D\"\n")
        append("  if cd -- \"\$D\" 2>/dev/null; then\n")
        append("    for f in * .[!.]* ..?*; do\n")
        append("      [ -e \"\$f\" ] || [ -L \"\$f\" ] || continue\n")
        append("      if [ -L \"\$f\" ]; then t=l; s=0\n")
        append("      elif [ -d \"\$f\" ]; then t=d; s=\$(du -sk -- \"\$f\" 2>/dev/null | cut -f1)\n")
        append("      else t=f; s=\$(stat -c %s -- \"\$f\" 2>/dev/null); fi\n")
        append("      m=\$(stat -c %Y -- \"\$f\" 2>/dev/null)\n")
        append("      echo \"E|\$t|\${s:-0}|\${m:-0}|\$f\"\n")
        append("    done\n")
        append("  elif [ -e \"\$D\" ]; then echo \"ERR|\$D\"\n")
        append("  else echo \"MISS|\$D\"; fi\n")
        append("done\n")
    }

    fun parseList(output: String): Listing {
        val entries = ArrayList<CargoEntry>()
        val missing = ArrayList<String>()
        val errors = ArrayList<String>()
        var dir: String? = null
        output.lineSequence().forEach { line ->
            when {
                line.startsWith("DIR|") -> dir = line.substring(4)
                line.startsWith("MISS|") -> missing += line.substring(5)
                line.startsWith("ERR|") -> errors += line.substring(4)
                line.startsWith("E|") -> {
                    val d = dir ?: return@forEach
                    val p = line.split('|', limit = 5)
                    if (p.size != 5 || p[4].isEmpty()) return@forEach
                    val kind = when (p[1]) {
                        "d" -> EntryKind.DIR
                        "l" -> EntryKind.LINK
                        else -> EntryKind.FILE
                    }
                    val raw = p[2].trim().toLongOrNull() ?: 0L
                    entries += CargoEntry(
                        path = "$d/${p[4]}",
                        name = p[4],
                        kind = kind,
                        bytes = if (kind == EntryKind.DIR) raw * 1024L else raw,
                        modified = (p[3].trim().toLongOrNull() ?: 0L) * 1000L,
                    )
                }
            }
        }
        return Listing(entries.sortedByDescending { it.bytes }, missing, errors)
    }
}
