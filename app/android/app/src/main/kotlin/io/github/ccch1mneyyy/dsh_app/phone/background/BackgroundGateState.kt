package io.github.ccch1mneyyy.dsh_app.phone.background

/** Capability facts from completed probes; an occupied display is not a failed capability. */
internal class BackgroundGateState {
    data class Snapshot(
        val trustedDisplayOk: Boolean? = null,
        val injectOk: Boolean? = null,
        val injectError: String? = null,
        val gateDetail: String? = null,
    )

    @Volatile var snapshot = Snapshot()
        private set

    fun reset() { snapshot = Snapshot() }

    /** False means the probe did not run, so none of the cached facts may be replaced. */
    fun update(ok: Boolean, error: String?, injectOk: Boolean?, injectError: String?, detail: String?): Boolean {
        if (error == "probeBusy") return false
        snapshot = Snapshot(ok, injectOk, if (injectOk == false) injectError else null, detail)
        return true
    }
}
