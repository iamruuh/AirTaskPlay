// Copied unchanged from github.com/jqssun/android-airplay-server (GPL-3.0). The native library binds to this package name.
package io.github.jqssun.airplay.bridge

/**
 * log lines from native code, forwarded to UI; called directly from native threads
 * (attached to JVM for the call), implementations must be thread-safe and cheap
 */
interface LogListener {
    fun onLog(msg: String)
}
