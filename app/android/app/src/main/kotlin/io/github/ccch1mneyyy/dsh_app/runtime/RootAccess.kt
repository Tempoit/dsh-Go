package io.github.ccch1mneyyy.dsh_app.runtime

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * Optional root access for the Linux environment.
 *
 * Magisk keeps the daemon socket in a tmpfs whose mount point differs per device
 * and Android version (`/debug_ramdisk` on this one, `/.magisk` elsewhere), so the
 * path is read from this process's own mount table: the entry whose fstype is
 * `magisk`. That mount point is what PRoot has to bind in — the guest's `su`
 * connects to `<mount point>/.magisk/device/socket`.
 *
 * Availability is decided by actually running `/system/bin/su -c id`; the first
 * probe can take a while because Magisk shows its grant prompt. Run it off the
 * main thread (see RuntimePlugin).
 */
object RootAccess {
    /** 日志措辞用；真实路径以 [magiskMounts] 为准。 */
    const val MAGISK_SOCKET = "/debug_ramdisk/.magisk/device/socket"

    /** 挂载表读不出来时的兜底候选（按 Magisk 历史版本顺序）。 */
    private val TMPFS_CANDIDATES = listOf("/debug_ramdisk", "/.magisk", "/sbin")

    @Volatile private var cached: Map<String, Any?>? = null

    private fun errnoName(e: ErrnoException): String =
        OsConstants.errnoName(e.errno) ?: "errno ${e.errno}"

    /** stat() 结果 per candidate socket；SELinux 拦截时给出 errno。 */
    private fun socketFacts(): Map<String, String> {
        val facts = mutableMapOf<String, String>()
        for (dir in TMPFS_CANDIDATES) {
            val path = "$dir/device/socket"
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

    /**
     * 本进程挂载表里 fstype 为 `magisk` 的挂载点：Magisk 的 tmpfs 就在那里。
     * App 进程（proot 之外）能读到自己的 /proc/self/mounts，不需要 su。
     */
    private fun magiskMounts(): List<String> = try {
        File("/proc/self/mounts").readLines().mapNotNull { line ->
            val parts = line.split(" ")
            if (parts.getOrNull(2) == "magisk") parts.getOrNull(1)?.replace("\\040", " ") else null
        }.distinct()
    } catch (_: Exception) {
        emptyList()
    }

    fun manager(): String? = when {
        File("/system/bin/magisk").exists() -> "magisk"
        File("/system/bin/ksud").exists() -> "kernelsu"
        else -> null
    }

    /** 只读快照，不跑 su：诊断速览用。 */
    fun status(): Map<String, Any?> = mapOf(
        "manager" to manager(),
        "socketFacts" to socketFacts(),
        "magiskMounts" to magiskMounts(),
        "cached" to cached,
    )

    /**
     * 跑一次 `su -c 'id; …'`：拿到 uid=0 才算可用，并从真机挂载表里找出 Magisk
     * tmpfs 的挂载点。首次会弹 Magisk 授权并等待用户，[timeoutMs] 内没批就按失败算。
     * 结果缓存；[force] 时重新探测。
     */
    @Synchronized
    fun probe(timeoutMs: Long = 40_000L, force: Boolean = false): Map<String, Any?> {
        cached?.let { if (!force) return it }
        val mounts = magiskMounts()
        // 挂载点里带 .magisk 的那个才是 daemon socket 所在；取不到就按候选兜底。
        val bindPath = mounts.firstOrNull { File(it, ".magisk").exists() }
            ?: mounts.firstOrNull()
            ?: TMPFS_CANDIDATES.firstOrNull { File(it).exists() }

        val facts = LinkedHashMap<String, Any?>()
        facts["manager"] = manager()
        facts["magiskMounts"] = mounts
        facts["bindPath"] = bindPath
        facts["socketFacts"] = socketFacts()

        val (exitOk, captured) = DshProcess.runCaptured(
            listOf(
                "/system/bin/su", "-c",
                "id; echo ---; grep -E 'magisk|tmpfs' /proc/mounts",
            ),
            timeoutMs,
        )
        val out = StringBuilder(captured)
        var suDetail: String? = null
        var suOk = exitOk && out.contains("uid=0")
        if (!suOk) {
            val why = out.take(300).toString().ifBlank { "su 没有返回输出（超时、拒绝或未授权）" }
            // 挂载点一起带上：为空说明 App 进程根本看不到 Magisk tmpfs，bind 无从谈起。
            suDetail = "$why\nmagisk 挂载点: ${if (mounts.isEmpty()) "（App 进程看不到）" else mounts.joinToString()}"
        }
        if (suOk) suDetail = out.lines().firstOrNull { it.startsWith("uid=") }

        facts["suOk"] = suOk
        facts["suDetail"] = suDetail?.take(600)
        facts["available"] = suOk
        val result = facts.toMap()
        cached = result
        return result
    }

    /** 最近一次探测的说明（uid 行或失败原因），给引擎日志用。 */
    fun lastDetail(): String? = cached?.get("suDetail") as? String

    /** 只信 su 探测；没探测过就先探（可能在引擎启动的后台线程里等授权）。 */
    fun available(): Boolean = probe()["available"] == true
}
