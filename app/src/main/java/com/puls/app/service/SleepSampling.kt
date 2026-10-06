package com.puls.app.service

/**
 * Heart rate in sleep is measured in short windows instead of all night. Each HRS notification
 * (once a second) wakes the phone through the Bluetooth UART: with the stream on the phone barely
 * sleeps and drains about 2.4%/h, without it about 0.5%/h (field test 30.09-06.10). Between windows
 * the link is dropped: COROS ignores disabled notifications, and a live link alone keeps the phone awake.
 */
object SleepSampling {
    /** How long the stream runs in each window. */
    const val WINDOW_MS = 60_000L

    /** Interval choices in minutes; 0 - the stream is never paused. */
    val INTERVALS = listOf(0, 2, 3, 5, 10, 15)
    const val DEFAULT_INTERVAL = 5

    /**
     * Whether to pause the stream now. Never while the alarm is on (it repeats until heart rate is
     * back in range) or while the screen is on (the user is looking at live heart rate).
     * streamSince - elapsedRealtime of the first measurement of the current window.
     */
    fun shouldPause(
        intervalMin: Int, sleep: Boolean, alarmOn: Boolean, screenOn: Boolean, streamSince: Long, now: Long,
    ): Boolean = intervalMin > 0 && sleep && !alarmOn && !screenOn && now - streamSince >= WINDOW_MS

    /** When the next window starts: the interval counts from the start of the current one. */
    fun resumeAt(intervalMin: Int, streamSince: Long, now: Long): Long =
        maxOf(streamSince + intervalMin * 60_000L, now + MIN_PAUSE_MS)

    /** A pause shorter than this saves nothing: the window itself already took most of the interval. */
    const val MIN_PAUSE_MS = 30_000L
}
