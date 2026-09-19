package com.devil.phoenixproject.util

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AndroidCrashLogTest {

    private var originalHandler: Thread.UncaughtExceptionHandler? = null
    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        dir = Files.createTempDirectory("crashlog").toFile()
    }

    @AfterTest
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(originalHandler)
        dir.deleteRecursively()
    }

    @Test
    fun handlerWritesReportFileThenDelegatesToPreviousHandler() {
        val delegated = mutableListOf<Pair<Thread, Throwable>>()
        var fileExistedWhenDelegated = false
        val file = File(dir, CrashLog.FILE_NAME)
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            fileExistedWhenDelegated = file.exists()
            delegated += thread to throwable
        }

        AndroidCrashLog.installHandler(file) { "report for ${it.message}" }
        val crash = RuntimeException("boom")
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), crash)

        assertEquals("report for boom", file.readText())
        assertEquals(1, delegated.size)
        assertSame(crash, delegated.single().second)
        assertTrue(fileExistedWhenDelegated, "report must be written before delegating")
        assertEquals("report for boom", CrashLog.pending(FileCrashLogStore(file)))
    }

    @Test
    fun handlerStillDelegatesWhenTheFileCannotBeWritten() {
        var delegated = 0
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> delegated++ }
        val unwritable = File(dir, "missing-dir/${CrashLog.FILE_NAME}")

        AndroidCrashLog.installHandler(unwritable) { "r" }
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), RuntimeException())

        assertFalse(unwritable.exists())
        assertEquals(1, delegated)
    }

    @Test
    fun handlerStillDelegatesWhenFormattingFails() {
        var delegated = 0
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> delegated++ }

        AndroidCrashLog.installHandler(File(dir, CrashLog.FILE_NAME)) { error("format failed") }
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), RuntimeException())

        assertEquals(1, delegated)
    }

    @Test
    fun realFormatterReportIsReadBackByThePromptStore() {
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
        val file = File(dir, CrashLog.FILE_NAME)

        AndroidCrashLog.installHandler(file) { CrashLog.formatReport(it, "2.0.0 (7)", "Android test", 0L) }
        Thread.getDefaultUncaughtExceptionHandler()!!
            .uncaughtException(Thread.currentThread(), IllegalStateException("routine add failed"))

        val pending = CrashLog.pending(FileCrashLogStore(file))!!
        assertTrue(pending.contains("App version: 2.0.0 (7)"), pending)
        assertTrue(pending.contains("routine add failed"), pending)
    }

    @Test
    fun installingTwiceDoesNotWrapTheHandlerTwice() {
        var delegated = 0
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> delegated++ }
        val file = File(dir, CrashLog.FILE_NAME)

        AndroidCrashLog.installHandler(file) { "r" }
        AndroidCrashLog.installHandler(file) { "r" }
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), RuntimeException())

        assertEquals(1, delegated)
    }
}
