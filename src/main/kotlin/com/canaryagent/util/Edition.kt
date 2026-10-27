package com.canaryagent.util

import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.BurpSuiteEdition

/** Capabilities that depend on the Burp edition, detected once at startup. */
data class Capabilities(val edition: BurpSuiteEdition) {
    /** Collaborator is only available in Professional. */
    val collaboratorAvailable: Boolean
        get() = edition == BurpSuiteEdition.PROFESSIONAL

    /** The Scanner (and so passive scan checks / audit issues) is only available in Professional. */
    val scannerAvailable: Boolean
        get() = edition == BurpSuiteEdition.PROFESSIONAL

    companion object {
        /** Reads the edition from Burp once at load. */
        fun detect(api: MontoyaApi): Capabilities = Capabilities(api.burpSuite().version().edition())
    }
}
