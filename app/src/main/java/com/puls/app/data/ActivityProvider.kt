package com.puls.app.data

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * Прогулки и тренировки для приложения Calorie: поминутный средний пульс в режимах
 * "прогулка" и "тренировка". Доступ - только приложениям с той же подписью
 * (разрешение com.puls.app.permission.READ_ACTIVITY, protectionLevel signature).
 * Только чтение. Расход калорий считает Calorie: у него вес, пол и возраст.
 *
 * content://com.puls.app.activity/minutes?from=<мс>&to=<мс>
 */
class ActivityProvider : ContentProvider() {
    /** Больше двух недель за раз не отдаём: Calorie столько и нужно, а hr растёт на 86 тысяч строк в сутки. */
    private val maxSpanMs = 15 * 24 * 3_600_000L

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        if (uri.pathSegments != listOf(ActivityShare.PATH_MINUTES)) return null
        val to = uri.getQueryParameter(ActivityShare.PARAM_TO)?.toLongOrNull() ?: System.currentTimeMillis()
        val from = (uri.getQueryParameter(ActivityShare.PARAM_FROM)?.toLongOrNull() ?: 0L).coerceAtLeast(to - maxSpanMs)
        val cursor = MatrixCursor(ActivityShare.COLUMNS)
        if (from >= to) return cursor
        // Запрос приходит в потоке binder, не в главном: Room можно звать синхронно.
        val dao = HrDb.get(context!!).dao()
        // Сначала интервалы по журналу режимов (их немного), и только в них - пульс по минутам.
        for (iv in ActivityShare.intervals(dao.marksNow(from, to), from, to)) {
            for (m in dao.minutesNow(iv.from, iv.to)) cursor.addRow(arrayOf<Any>(m.minute, iv.mode, m.bpm, m.samples))
        }
        return cursor
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("read only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("read only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("read only")
}
