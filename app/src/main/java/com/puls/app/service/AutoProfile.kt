package com.puls.app.service

/**
 * Автовыбор профиля по пульсу и темпу шагов. Без Android-зависимостей: решения проверяются тестами.
 *
 * Покой от прогулки отличает движение: пульс отстаёт от нагрузки на минуты, и медленную
 * прогулку по одному пульсу не узнать. Прогулку от тренировки - пульс в коридоре тренировки
 * или темп бега. Стоим, а пульс между покоем и тренировкой (восстановление после нагрузки,
 * велосипед) - профиль не меняем.
 *
 * Защита от дребезга:
 * - медиана пульса за HR_WINDOW_MS: одиночные выбросы датчика не доходят до решения;
 * - гистерезис порогов пульса и темпа;
 * - новый профиль должен продержаться: вверх быстрее, вниз медленнее - пульс падает с запаздыванием;
 * - MIN_DWELL_MS после смены профиль не меняется;
 * - без данных (onGap) начатое подтверждение сбрасывается.
 *
 * В авто сигнал о коридоре идёт только за общими границами: ниже покоя и выше тренировки.
 * Исключение - restHigh: стоим в покое, а пульс выше его коридора дольше REST_HIGH_MS.
 */
class AutoProfile {
    private val hr = ArrayDeque<Pair<Long, Int>>()
    /** Режим, к которому ведут текущие данные, и с какого момента (для журнала). */
    var candidate: Profile? = null
        private set
    var candidateSince = 0L
        private set
    /** Решение по последнему замеру без учёта подтверждения; null - данных мало. */
    var target: Profile? = null
        private set
    private var switchedAt: Long? = null
    private var restHighSince: Long? = null

    /** Пульс в покое выше его коридора без движения: сигналить по коридору покоя. */
    var restHigh = false
        private set

    /** Сглаженный пульс; null - замеров ещё мало для решения. */
    var median: Int? = null
        private set

    /**
     * Замер пульса и текущий темп шагов (шагов в минуту).
     * Возвращает профиль, на который пора переключиться, или null.
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
        val training = med >= if (current == Profile.TRAINING) trainLow - HR_HYST else trainLow
        val target = when {
            running || training -> Profile.TRAINING
            moving -> Profile.WALK
            med <= rest.last -> Profile.REST
            else -> current
        }
        this.target = target

        // Движение не снимает сигнал: пульс от него не стал нормальным; снимет смена профиля.
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

    /** Данных нет (снят, нет контакта, обрыв): решения ждут новых замеров. */
    fun onGap() {
        hr.clear()
        median = null
        target = null
        candidate = null
        restHighSince = null
        restHigh = false
    }

    /** Автовыбор только что включили или профиль сменили вручную: начинаем с чистого листа. */
    fun reset() {
        onGap()
        switchedAt = null
    }

    companion object {
        const val HR_WINDOW_MS = 30_000L
        const val MIN_SAMPLES = 10
        const val HR_HYST = 5

        /** Темп шагов: ниже STILL - стоим, от WALK - идём, от RUN - бежим. */
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
 * Темп шагов, шагов в минуту, по показаниям накопительного счётчика за скользящее окно.
 * Счётчик шлёт показания только когда есть шаги, поэтому делим всегда на всё окно:
 * в начале ходьбы темп занижен, это безопаснее, чем завышен.
 */
class Cadence(private val windowMs: Long = 60_000L) {
    private val samples = ArrayDeque<Pair<Long, Long>>()

    @Synchronized
    fun add(ts: Long, count: Long) {
        // После перезагрузки счётчик начинается с нуля.
        if (samples.isNotEmpty() && count < samples.last().second) samples.clear()
        samples.addLast(ts to count)
        // Одно показание старше окна храним: от него считаются шаги внутри окна.
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
