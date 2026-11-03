package io.github.ccch1mneyyy.dsh_app.runtime

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * Optional root access, exercised from this app's own process (see
 * `mcp__phone__root`): PRoot cannot carry Magisk's fd passing, so the guest's
 * `su` never works and root has to run out here.
 *
 * Neither the `su` path nor the daemon mount point is fixed: Magisk 31 moved the
 * binary to `/product/bin/su` (older releases use `/system/bin`, `/debug_ramdisk`),
 * and it mounts its tmpfs wherever it likes. Both are discovered instead —
 * mounts from this process's own mount table (fstype `magisk`), `su` by probing
 * the candidates that table suggests plus the known historical locations.
 *
 * The first probe can take a while: Magisk shows its grant prompt and waits.
 * Run it off the main thread (see RuntimePlugin).
 */
object RootAccess {
    /** 挂载表为空时的兜底候选（按 Magisk 版本从新到旧）。 */
    private val FALLBACK_MOUNTS = listOf("/product/bin", "/debug_ramdisk", "/system/bin", "/.magisk", "/sbin")

    /** `su` 的历史位置；挂载表推导出来的候选排在前面。 */
    private val FIXED_SU_PATHS = listOf(
        "/product/bin/su",
        "/system/bin/su",
        "/system/xbin/su",
        "/debug_ramdisk/su",
        "/sbin/su",
        "/data/adb/magisk/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/ap/bin/su",
    )

    @Volatile private var cached: Map<String, Any?>? = null

    private fun errnoName(e: ErrnoException): String =
        OsConstants.errnoName(e.errno) ?: "errno ${e.errno}"

    /**
     * 本进程挂载表里 fstype 为 `magisk` 的挂载点：Magisk 的 tmpfs 就在那里，
     * daemon socket 与（新版）`su` 都在其下。
     */
    private fun magiskMounts(): List<String> = try {
        File("/proc/self/mounts").readLines().mapNotNull { line ->
            val parts = line.split(" ")
            if (parts.getOrNull(2) == "magisk") parts.getOrNull(1)?.replace("\\040", " ") else null
        }.distinct()
    } catch (_: Exception) {
        emptyList()
    }

    /** 候选 `su` 路径：挂载点下的 `su` 优先，再退固定列表。 */
    fun suCandidates(): List<String> {
        val mounts = magiskMounts().ifEmpty { FALLBACK_MOUNTS }
        return (mounts.map { "$it/su" } + FIXED_SU_PATHS).distinct()
    }

    /** 存在的候选（顺序同 [suCandidates]）；不判断可执行性——那要用一次真实执行来验。 */
    fun existingSuCandidates(): List<String> = suCandidates().filter { path ->
        try {
            File(path).exists()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 实际能用的 `su`：上一次探测成功的那个。null 表示还没探测过或全部失败。
     * 路径存在不等于能执行——Magisk 31 把 `/product/bin/su` 放在这里，
     * 普通 App 执行会 Permission denied，得换 `/debug_ramdisk/su`。
     */
    fun suPath(): String? = cached?.get("suPath") as? String

    fun manager(): String? = when {
        magiskMounts().isNotEmpty() -> "magisk"
        File("/data/adb/ksu").exists() || File("/system/bin/ksud").exists() -> "kernelsu"
        File("/data/adb/ap").exists() -> "apatch"
        // 挂载表读不出来（受限 / 旧内核）时，能跑 su 就当 Magisk 系。
        suPath() != null -> "magisk"
        else -> null
    }

    /** stat() 结果 per 候选 socket；SELinux 拦截时给出 errno。 */
    private fun socketFacts(): Map<String, String> {
        val facts = mutableMapOf<String, String>()
        for (dir in (magiskMounts().ifEmpty { FALLBACK_MOUNTS })) {
            val path = "$dir/.magisk/device/socket"
            facts[path] = try {
                Os.stat(path); "ok"
            } catch (e: ErrnoException) {
                errnoName(e)
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
        }
        return facts
    }

    /** 只读快照，不跑 su：诊断速览用。 */
    fun status(): Map<String, Any?> = mapOf(
        "manager" to manager(),
        "suPath" to suPath(),
        "suCandidates" to suCandidates(),
        "magiskMounts" to magiskMounts(),
        "socketFacts" to socketFacts(),
        "cached" to cached,
    )

    /**
     * 跑一次 `su -c id`：拿到 uid=0 才算可用。首次会弹 Magisk 授权并等待用户，
     * [timeoutMs] 内没批就按失败算。结果缓存；[force] 时重新探测。
     */
    @Synchronized
    fun probe(timeoutMs: Long = 40_000L, force: Boolean = false): Map<String, Any?> {
        cached?.let { if (!force) return it }
        val mounts = magiskMounts()

        val candidates = existingSuCandidates()
        val facts = LinkedHashMap<String, Any?>()
        facts["manager"] = manager()
        facts["magiskMounts"] = mounts
        facts["suCandidates"] = suCandidates()

        var working: String? = null
        var suDetail: String? = null
        val attempts = LinkedHashMap<String, String>()
        if (candidates.isEmpty()) {
            // l10n:keep agent tool result
            suDetail = "没找到 su 可执行文件。试过：${suCandidates().joinToString()}；" +
                "magisk 挂载点：${if (mounts.isEmpty()) "（App 进程看不到）" else mounts.joinToString()}"
        } else {
            // 路径存在不等于能执行（Magisk 31 的 /product/bin/su 对普通 App 是 Permission denied），
            // 所以逐个真跑一次，第一个拿到 uid=0 的才算数。
            val perCandidate = timeoutMs.coerceAtMost(15_000L)
            for (candidate in candidates) {
                val (exitOk, captured) = DshProcess.runCaptured(listOf(candidate, "-c", "id"), perCandidate)
                val out = captured.trim()
                if (exitOk && out.contains("uid=0")) {
                    working = candidate
                    attempts[candidate] = "ok"
                    break
                }
                attempts[candidate] = out.take(160).ifBlank { "没有输出（超时或被拒）" }
            }
            suDetail = if (working != null) {
                "uid=0（$working）"
            } else {
                val lines = attempts.entries.joinToString("\n") { (path, why) -> "$path：$why" }
                val denied = attempts.values.any { it.contains("denied", ignoreCase = true) }
                if (denied) "$lines\n${grantHint()}" else lines
            }
        }

        facts["suPath"] = working
        facts["suAttempts"] = attempts
        facts["suOk"] = working != null
        facts["suDetail"] = suDetail?.take(1200)
        facts["available"] = working != null
        val result = facts.toMap()
        cached = result
        return result
    }

    /**
     * 真机结论（Magisk 31.1）：`policies` 表里没有这个 uid 的记录时，它不弹授权框、
     * 直接 `Permission denied`。给一条能直接粘到 root 终端补救的命令，省掉一轮猜。
     */
    // l10n:keep agent tool result
    private fun grantHint(): String {
        val uid = try {
            android.os.Process.myUid()
        } catch (_: Throwable) {
            -1
        }
        return "Magisk 31 查不到 uid=$uid 的授权记录时不弹窗、直接拒绝。在能拿到 root 的终端执行：" +
            "magisk --sqlite \"INSERT OR REPLACE INTO policies (uid,policy,until,logging,notification) " +
            "VALUES ($uid,2,0,1,1)\"，然后重新打开本开关。"
    }

    /** 最近一次探测的说明（uid 行或失败原因），给引擎日志用。 */
    fun lastDetail(): String? = cached?.get("suDetail") as? String

    /** 只信 su 探测；没探测过就先探（可能在引擎启动的后台线程里等授权）。 */
    fun available(): Boolean = probe()["available"] == true
}
