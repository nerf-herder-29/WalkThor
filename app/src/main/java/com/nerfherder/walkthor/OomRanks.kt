package com.nerfherder.walkthor

/**
 * Reading and setting how Android's memory killer ranks processes (oom_score_adj: lower is kept
 * longer; the app in front is normally 0), through AYN's system helper.
 *
 * Only plain commands: no quote marks, no $ and no $( ). Commands built with those came back
 * with nothing at all through the helper, so for weeks the protection and the rank readings
 * silently did nothing. The work of picking processes and deciding ranks is done here instead.
 */
internal object OomRanks {
    data class Proc(val pid: Int, val name: String) {
        val pkg get() = name.substringBefore(':')
    }

    /** One process's rank before and after ([kind] 'G' for the game, 'R' for an app kept next in line, 'K' for Keeper). */
    data class Row(val kind: Char, val pkg: String, val pid: Int, val before: Int, val after: Int)

    private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
    private val ADJ_LINE = Regex("/proc/(\\d+)/oom_score_adj:(-?\\d+)")

    /** The running processes of [pkgs] (their main process and any "package:name" ones); null if the helper didn't answer. */
    fun procs(pkgs: Collection<String>): List<Proc>? {
        val want = pkgs.filter { PACKAGE.matches(it) }.toSet()
        if (want.isEmpty()) return emptyList()
        val out = AynSystem.shellLines("ps -A") ?: return null
        return out.mapNotNull { line ->
            val f = line.trim().split(Regex("\\s+"))
            if (f.size < 3) return@mapNotNull null
            val pid = f[1].toIntOrNull() ?: return@mapNotNull null
            val name = f.last()
            if (want.any { name == it || name.startsWith("$it:") }) Proc(pid, name) else null
        }
    }

    /** The ranks of [pids] (any that have gone are left out). */
    fun adj(pids: Collection<Int>): Map<Int, Int> {
        if (pids.isEmpty()) return emptyMap()
        val out = AynSystem.shellLines("grep -H . " + pids.distinct().joinToString(" ") { "/proc/$it/oom_score_adj" }) ?: return emptyMap()
        return out.mapNotNull { l -> ADJ_LINE.find(l)?.let { m -> m.groupValues[1].toInt() to m.groupValues[2].toInt() } }.toMap()
    }

    /**
     * [pkgs]' processes (and [extraPids]) lowered to [keepAdj] where they rank higher (never
     * raised), and the [game]'s on-screen processes (ranked 1 to [gameMax]) put first, at 0.
     * Returns every process looked at; null if the helper didn't answer.
     */
    fun protect(pkgs: List<String>, extraPids: List<Int>, game: String?, keepAdj: Int, gameMax: Int): List<Row>? {
        val ps = procs(pkgs + listOfNotNull(game)) ?: return null
        val before = adj(ps.map { it.pid } + extraPids)
        val want = linkedMapOf<Int, Int>()
        val kinds = linkedMapOf<Int, Pair<Char, String>>()
        for (p in ps) {
            val a = before[p.pid] ?: continue
            if (game != null && p.pkg == game) {
                kinds[p.pid] = 'G' to game
                if (a in 1..gameMax) want[p.pid] = 0
            } else {
                kinds[p.pid] = 'R' to p.pkg
                if (a > keepAdj) want[p.pid] = keepAdj
            }
        }
        for (pid in extraPids) {
            val a = before[pid] ?: continue
            kinds[pid] = 'K' to "keeper"
            if (a > keepAdj) want[pid] = keepAdj
        }
        val after = if (want.isEmpty()) emptyMap() else {
            AynSystem.shellLines(want.entries.joinToString("; ") { "echo ${it.value} > /proc/${it.key}/oom_score_adj" })
            adj(want.keys)
        }
        return kinds.map { (pid, k) ->
            val b = before[pid] ?: 0
            Row(k.first, k.second, pid, b, after[pid] ?: b)
        }
    }

    /** The app Android counts as the one in front (its package), from its activity list. */
    fun topApp(): String? = AynSystem.shellLines("dumpsys activity activities | grep -m1 mTopResumedActivity")
        ?.firstOrNull()?.let { Regex(" ([A-Za-z0-9_.]+)/").find(it)?.groupValues?.get(1) }
}
