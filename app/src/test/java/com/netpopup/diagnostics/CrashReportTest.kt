package com.netpopup.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CrashReportTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun report() = CrashReport(tmp.root.resolve("sub/last_crash.txt"))

    @Test fun `no crash means nothing pending`() = assertNull(report().pending())

    @Test fun `a recorded crash keeps the exception chain and frames`() {
        val r = report()
        r.record(Thread.currentThread(), IllegalStateException("boom", RuntimeException("root")))
        val text = r.pending()!!
        assertTrue(text.contains("java.lang.IllegalStateException: boom"))
        assertTrue(text.contains("Caused by:"))
        assertTrue(text.contains("java.lang.RuntimeException: root"))
        assertTrue(text.contains("    at com.netpopup.diagnostics.CrashReportTest"))
    }

    @Test fun `a long message is truncated`() {
        val r = report()
        r.record(Thread.currentThread(), RuntimeException("x".repeat(5000)))
        assertTrue(r.pending()!!.length < 5000)
    }

    @Test fun `an invalid report path cannot crash startup`() {
        val directory = tmp.newFolder("not-a-file")
        assertNull(CrashReport(directory).pending())
    }

    @Test fun `reading a report is bounded`() {
        val file = tmp.newFile("large-report")
        file.writeText("x".repeat(100_000))
        assertEquals(32_768, CrashReport(file).pending()!!.length)
    }

    @Test fun `clear removes the report`() {
        val r = report()
        r.record(Thread.currentThread(), RuntimeException("a"))
        r.clear()
        assertNull(r.pending())
    }

    @Test fun `the installed handler records then lets the previous handler run`() {
        val previousDefault = Thread.getDefaultUncaughtExceptionHandler()
        var forwarded: Throwable? = null
        Thread.setDefaultUncaughtExceptionHandler { _, e -> forwarded = e }
        try {
            val r = report()
            r.install()
            val error = RuntimeException("crash")
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), error)
            assertEquals(error, forwarded)
            assertFalse(r.pending().isNullOrBlank())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousDefault)
        }
    }
}
