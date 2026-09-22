package com.puls.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

class HistoryTransferTest {
    private val now = 1_790_000_000_000L

    private fun run(csv: String): Pair<List<HrSample>, Int> = runBlocking {
        val ok = ArrayList<HrSample>()
        var bad = 0
        HistoryTransfer.parse(BufferedReader(StringReader(csv)), now) { if (it == null) bad++ else ok += it }
        ok to bad
    }

    @Test
    fun compactFormat() {
        val (ok, bad) = run("epoch_ms,bpm\n1789000000000,72\n1789000001000,73\n")
        assertEquals(listOf(HrSample(1789000000000, 72), HrSample(1789000001000, 73)), ok)
        assertEquals(0, bad)
    }

    @Test
    fun periodExportWithTimeColumn() {
        val (ok, _) = run("time,epoch_ms,bpm\n2026-09-22T15:51:04+05:00,1789000000000,88\n")
        assertEquals(listOf(HrSample(1789000000000, 88)), ok)
    }

    @Test
    fun badRowsAreSkipped() {
        val csv = "epoch_ms,bpm\n" +
            "1789000000000,0\n" + // пульс 0
            "1789000000000,400\n" + // пульс вне диапазона
            "123,70\n" + // дата до 2000 года
            "${now + 100_000_000},70\n" + // дата из будущего
            "abc,70\n" + // не число
            "1789000000000\n" + // нет колонки
            "\n" + // пустая строка - не считается
            "1789000002000,90\n"
        val (ok, bad) = run(csv)
        assertEquals(listOf(HrSample(1789000002000, 90)), ok)
        assertEquals(6, bad)
    }

    @Test
    fun bomAndSpaces() {
        val (ok, _) = run("\uFEFFbpm, epoch_ms\n 75 , 1789000000000 \n")
        assertEquals(listOf(HrSample(1789000000000, 75)), ok)
    }

    @Test(expected = IllegalArgumentException::class)
    fun wrongHeader() {
        run("a,b\n1,2\n")
    }
}
