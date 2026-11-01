package cz.radek.dekontaminace.cargo

import java.util.concurrent.TimeUnit

data class CargoTrashItem(
    val id: String,
    val originalPath: String,
    val bytes: Long,
    val deletedAt: Long,
) {
    val name: String get() = originalPath.substringAfterLast('/')
    val expiresAt: Long get() = deletedAt + CargoTrash.RETENTION_MS
}

data class OpResult(val id: String, val ok: Boolean, val detail: String)

/**
 * Koš pro Android/data a obb. Leží v Android/data naší aplikace, tedy na stejném oddílu jako
 * mazané složky: přesun je jen přejmenování, zachová vlastníka souborů, a po obnově je tak
 * aplikace dál vlastní. Koš ve sdíleném úložišti by znamenal kopírování a cizího vlastníka.
 * Všechno dělá shell, naše aplikace na soubory cizích aplikací nedosáhne.
 */
class CargoTrash(val root: String) {

    fun moveScript(items: List<Pair<String, String>>, now: Long): String = buildString {
        append("set -f\n")
        append("K=${Cargo.quote(root)}\n")
        append("mkdir -p \"\$K\" 2>/dev/null\n")
        append("[ -d \"\$K\" ] || { echo \"ERR|Koš \$K nejde vytvořit\"; exit 3; }\n")
        append("kd=\$(stat -c %d \"\$K\")\n")
        append("one() {\n")
        append("  id=\$1; src=\$2; n=\${src##*/}; d=\"\$K/\$id\"\n")
        append("  if [ ! -e \"\$src\" ] && [ ! -L \"\$src\" ]; then echo \"FAIL|\$id|Už neexistuje\"; return; fi\n")
        // Přes oddíly by mv potichu kopíroval, u gigabajtových herních dat by to trvalo věčnost a místo by na chvíli dvakrát ubylo.
        append("  if [ \"\$(stat -c %d -- \"\$src\")\" != \"\$kd\" ]; then echo \"FAIL|\$id|Jiný oddíl než koš, přesun by znamenal kopírování\"; return; fi\n")
        append("  s=\$(du -sk -- \"\$src\" 2>/dev/null | cut -f1)\n")
        append("  r=\$(mkdir -p \"\$d/data\" 2>&1) || { echo \"FAIL|\$id|\"\$r; return; }\n")
        append("  printf 'path=%s\\nkb=%s\\ntime=%s\\n' \"\$src\" \"\${s:-0}\" \"\$3\" > \"\$d/meta\" || { rm -rf -- \"\$d\"; echo \"FAIL|\$id|Nejde zapsat popis do koše\"; return; }\n")
        append("  r=\$(mv -- \"\$src\" \"\$d/data/\$n\" 2>&1) || { rm -rf -- \"\$d\"; echo \"FAIL|\$id|\"\$r; return; }\n")
        append("  echo \"OK|\$id|\${s:-0}\"\n")
        append("}\n")
        items.forEach { (id, path) ->
            require(ID.matches(id)) { "Neplatné id $id" }
            append("one ${Cargo.quote(id)} ${Cargo.quote(path)} $now\n")
        }
    }

    fun listScript(): String = buildString {
        append("K=${Cargo.quote(root)}\n")
        append("[ -d \"\$K\" ] || exit 0\n")
        append("for d in \"\$K\"/*; do [ -f \"\$d/meta\" ] || continue\n")
        append("  echo \"ITEM|\${d##*/}\"; cat \"\$d/meta\"; echo; echo END\n")
        append("done\n")
    }

    fun parseList(output: String): List<CargoTrashItem> {
        val items = ArrayList<CargoTrashItem>()
        var id: String? = null
        val props = HashMap<String, String>()
        output.lineSequence().forEach { line ->
            when {
                line.startsWith("ITEM|") -> {
                    id = line.substring(5)
                    props.clear()
                }
                line == "END" -> {
                    val i = id
                    val path = props["path"]
                    if (i != null && ID.matches(i) && path != null) {
                        items += CargoTrashItem(
                            id = i,
                            originalPath = path,
                            bytes = (props["kb"]?.toLongOrNull() ?: 0L) * 1024L,
                            deletedAt = props["time"]?.toLongOrNull() ?: 0L,
                        )
                    }
                    id = null
                }
                id != null -> {
                    val eq = line.indexOf('=')
                    if (eq > 0) props[line.substring(0, eq)] = line.substring(eq + 1)
                }
            }
        }
        return items.sortedByDescending { it.deletedAt }
    }

    fun restoreScript(items: List<CargoTrashItem>): String = buildString {
        append("set -f\n")
        append("K=${Cargo.quote(root)}\n")
        append("r1() {\n")
        append("  id=\$1; n=\$2; shift 2; src=\"\$K/\$id/data/\$n\"\n")
        append("  if [ ! -e \"\$src\" ] && [ ! -L \"\$src\" ]; then echo \"FAIL|\$id|V koši chybí obsah\"; return; fi\n")
        append("  p=\${1%/*}\n")
        // Rodičovskou složku schválně nezakládáme: založená shellem by patřila shellu, ne aplikaci.
        append("  if [ ! -d \"\$p\" ]; then echo \"FAIL|\$id|Cílová složka už neexistuje (aplikace odinstalovaná?)\"; return; fi\n")
        append("  t=; for c in \"\$@\"; do if [ ! -e \"\$c\" ] && [ ! -L \"\$c\" ]; then t=\$c; break; fi; done\n")
        append("  [ -n \"\$t\" ] || { echo \"FAIL|\$id|Na místě už jsou soubory se stejným jménem\"; return; }\n")
        append("  r=\$(mv -- \"\$src\" \"\$t\" 2>&1) || { echo \"FAIL|\$id|\"\$r; return; }\n")
        append("  rm -rf -- \"\$K/\$id\"; echo \"OK|\$id|\$t\"\n")
        append("}\n")
        items.forEach { item ->
            require(ID.matches(item.id)) { "Neplatné id ${item.id}" }
            val candidates = restoreCandidates(item.originalPath).joinToString(" ") { Cargo.quote(it) }
            append("r1 ${Cargo.quote(item.id)} ${Cargo.quote(item.name)} $candidates\n")
        }
    }

    fun deleteScript(ids: Collection<String>): String = buildString {
        append("set -f\n")
        append("K=${Cargo.quote(root)}\n")
        ids.forEach { id ->
            require(ID.matches(id)) { "Neplatné id $id" }
            append("if rm -rf -- \"\$K/$id\"; then echo \"OK|$id|\"; else echo \"FAIL|$id|rm selhal\"; fi\n")
        }
    }

    companion object {
        val RETENTION_MS: Long = TimeUnit.DAYS.toMillis(7)
        val ID = Regex("^[0-9]+_[0-9]+$")

        fun parseResults(output: String): List<OpResult> = output.lineSequence().mapNotNull { line ->
            val p = line.split('|', limit = 3)
            if (p.size < 2 || (p[0] != "OK" && p[0] != "FAIL")) null
            else OpResult(p[1], p[0] == "OK", p.getOrElse(2) { "" }.trim())
        }.toList()

        /** Původní cesta a pak varianty s „(obnoveno)“, kdyby tam mezitím vzniklo něco nového. */
        fun restoreCandidates(path: String): List<String> {
            val parent = path.substringBeforeLast('/')
            val name = path.substringAfterLast('/')
            val dot = name.lastIndexOf('.')
            val (base, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
            return listOf(path) + (1..9).map { n -> "$parent/$base (obnoveno${if (n > 1) " $n" else ""})$ext" }
        }
    }
}
