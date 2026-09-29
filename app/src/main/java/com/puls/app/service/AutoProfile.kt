package com.puls.app.service

/**
 * Automatic profile selection by heart rate and step cadence. No Android dependencies: decisions are covered by tests.
 *
 * Rest is told from a walk by motion: heart rate lags behind the load by minutes, and a slow
 * walk cannot be recognized by heart rate alone. A walk from a workout - by heart rate in the training
 * range while moving, or by running cadence. Standing still with heart rate above rest (recovery after
 * exercise, housework with the phone on the table, gaming) - the profile is kept, restHigh fires in rest.
 *
 * Debouncing:
 * - median heart rate over HR_WINDOW_MS: single sensor outliers do not reach the decision;
 * - hysteresis on heart rate and cadence thresholds;
 * - the new profile must hold: faster going up, slower going down - heart rate falls with a delay;
 * - the profile does not change for MIN_DWELL_MS after a switch;
 * - without data (onGap) a pending confirmation is reset.
 *
 * In auto the range alarm fires only outside the overall bounds: below rest and above training.
 * The exception is restHigh: standing still in rest with heart rate above its range for longer than REST_HIGH_MS.
 */
class AutoProfile {
    private val hr = ArrayDeque<Pair<Long, Int>>()
    /** The mode the current data points to, and since when (for the log). */
    var candidate: Profile? = null
        private set
    var candidateSince = 0L
        private set
    /** Decision by the latest sample without confirmation; null - not enough data. */
    var target: Profile? = null
        private set
    private var switchedAt: Long? = null
    private var restHighSince: Long? = null

    /** Heart rate in rest above its range without motion: alarm by the rest range. */
    var restHigh = false
        private set

    /** Smoothed heart rate; null - too few samples for a decision yet. */
    var median: Int? = null
        private set

    /**
     * A heart rate sample and the current step cadence (steps per minute).
     * Returns the profile to switch to, or null.
     */
    fun onSample(now: Long, bpm: Int, cadenceSpm: Double, current: Profile, range: (Profile) -> IntRange): Profile? {
        hr.addLast(now to bpm)
        while (hr.first().first < now - HR_WINDOW_MS) hr.removeFirst()
        if (hr.size < MIN_SAMPLES) return null
        val med = hr.map { it.second }.sorted()[hr.size / 2]
        median = med

        val rest = range(Profile.REST)
        val trainLow = range(Profile.TRAINING).first
        val running = cadenceSpm >= if (current == Profile.TRAINING) RUN_SPM - SPM_HYST else RUN_SPM
        val moving = cadenceSpm >= if (current == Profile.WALK) STILL_SPM else WALK_SPM
        // Training heart rate without steps is both housework with the phone on the table and excitement over a game
        // (field tests 26-27.09). So by heart rate we enter training only while moving; a workout already started
        // is kept without steps (rest between sets, exercises in place).
        val trainingPulse = med >= if (current == Profile.TRAINING) trainLow - HR_HYST else trainLow
        val training = trainingPulse && (current == Profile.TRAINING || cadenceSpm >= STILL_SPM)
        val target = when {
            running || training -> Profile.TRAINING
            moving -> Profile.WALK
            med <= rest.last -> Profile.REST
            else -> current
        }
        this.target = target

        // Motion does not clear the alarm: heart rate did not become normal because of it; a profile switch will.
        if (current == Profile.REST && target == Profile.REST && med > rest.last) {
            val since = restHighSince ?: now.also { restHighSince = it }
            if (now - since >= REST_HIGH_MS) restHigh = true
        } else if (current != Profile.REST || med <= rest.last) {
            restHighSince = null
            restHigh = false
        }

        if (target == current) {
            candidate = null
            return null
        }
        if (candidate != target) {
            candidate = target
            candidateSince = now
        }
        val confirm = when {
            target.ordinal < current.ordinal -> DOWN_MS
            target == Profile.TRAINING -> UP_TRAINING_MS
            else -> UP_WALK_MS
        }
        if (now - candidateSince < confirm) return null
        if (switchedAt?.let { now - it < MIN_DWELL_MS } == true) return null
        candidate = null
        switchedAt = now
        restHighSince = null
        restHigh = false
        return target
    }

    /** No data (taken off, no contact, link lost): decisions wait for new samples. */
    fun onGap() {
        hr.clear()
        median = null
        target = null
        candidate = null
        restHighSince = null
        restHigh = false
    }

    /** Auto selection was just turned on or the profile was changed manually: start from a clean slate. */
    fun reset() {
        onGap()
        switchedAt = null
    }

    companion object {
        const val HR_WINDOW_MS = 30_000L
        const val MIN_SAMPLES = 10
        const val HR_HYST = 5

        /** Step cadence: below STILL - standing, from WALK - walking, from RUN - running. */
        const val STILL_SPM = 30.0
        const val WALK_SPM = 60.0
        const val RUN_SPM = 140.0
        const val SPM_HYST = 10.0

        const val UP_WALK_MS = 60_000L
        const val UP_TRAINING_MS = 120_000L
        const val DOWN_MS = 180_000L
        const val MIN_DWELL_MS = 180_000L
        const val REST_HIGH_MS = 120_000L
    }
}

/**
 * Step cadence, steps per minute, from a cumulative counter over a sliding window.
 * The counter reports only when there are steps, so we always divide by the whole window:
 * at the start of a walk the cadence is underestimated, which is safer than overestimated.
 */
class Cadence(private val windowMs: Long = 60_000L) {
    private val samples = ArrayDeque<Pair<Long, Long>>()

    @Synchronized
    fun add(ts: Long, count: Long) {
        // After a reboot the counter starts from zero.
        if (samples.isNotEmpty() && count < samples.last().second) samples.clear()
        samples.addLast(ts to count)
        // One reading older than the window is kept: steps inside the window are counted from it.
        while (samples.size > 1 && samples[1].first <= ts - windowMs) samples.removeFirst()
    }

    @Synchronized
    fun spm(now: Long): Double {
        if (samples.isEmpty()) return 0.0
        val from = now - windowMs
        val base = samples.lastOrNull { it.first <= from } ?: samples.first()
        return (samples.last().second - base.second) * 60_000.0 / windowMs
    }

    @Synchronized
    fun clear() = samples.clear()
}
