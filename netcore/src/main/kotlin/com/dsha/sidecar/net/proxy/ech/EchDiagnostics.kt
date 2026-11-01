package com.dsha.sidecar.net.proxy.ech

data class EchCauseClass(val simpleName: String, val fullName: String)

data class EchDiagnosticSnapshot(
    val stage: String,
    val causeClasses: List<EchCauseClass>,
    val causeChainTruncated: Boolean
)

/** Safe metadata only: never evaluates throwable messages, toString, stack traces or suppressed data. */
object EchDiagnostics {
    fun capture(failure: Throwable, fallbackStage: String): EchDiagnosticSnapshot {
        val seen = java.util.IdentityHashMap<Throwable, Boolean>()
        val names = mutableListOf<EchCauseClass>()
        var current: Throwable? = failure
        while (current != null && names.size < 8 && !seen.containsKey(current)) {
            seen[current] = true
            names.add(EchCauseClass(current.javaClass.simpleName, current.javaClass.name))
            current = current.cause
        }
        val stage = (failure as? EchConnectionException)?.stage
            ?.takeUnless { it == EchStage.UNKNOWN }?.name ?: fallbackStage
        return EchDiagnosticSnapshot(stage, names.toList(), current != null)
    }
}
