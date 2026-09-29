package com.puls.app.service

import android.content.Context

/**
 * Release stub of the field test log: the real one lives in src/debug and never ships.
 * Same API, so HrService calls it without checks.
 */
object Telemetry {
    fun init(context: Context) {}

    fun log(kind: String, vararg fields: Any?) {}
}
