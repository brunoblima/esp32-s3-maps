package com.maisonsmd.catdrive.utils

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.time.Instant
import java.util.concurrent.Executors

object ExecutionLog {
    private const val TAG = "CatDriveExecutionLog"
    private const val FILE_NAME = "catdrive-execution.log"
    private const val MAX_FILE_SIZE = 5L * 1024L * 1024L
    private const val FILE_COUNT = 3

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var logFile: File? = null

    @Synchronized
    fun initialize(context: Context) {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        if (logFile == null) {
            logFile = file
            record("APP", "Execution logging started")
        }
    }

    fun record(source: String, message: String) {
        val file = logFile
        if (file == null) {
            Log.e(TAG, "Logger used before initialize(): source=$source")
            return
        }

        val line = "${Instant.now()} [$source] $message\n"
        executor.execute {
            try {
                if (file.length() + line.toByteArray(Charsets.UTF_8).size > MAX_FILE_SIZE) {
                    rotateFiles(file)
                }
                FileOutputStream(file, true).bufferedWriter(Charsets.UTF_8).use {
                    it.write(line)
                }
            } catch (error: Exception) {
                Log.e(TAG, "Could not write execution log", error)
            }
        }
    }

    fun exportTo(
        resolver: ContentResolver,
        destination: Uri,
        onComplete: (Result<Unit>) -> Unit
    ) {
        executor.execute {
            val result = try {
                val file = logFile ?: error("Execution log has not been initialized")
                val output = resolver.openOutputStream(destination, "wt")
                    ?: error("Could not open the selected export destination")
                output.use { stream ->
                    for (index in FILE_COUNT - 1 downTo 1) {
                        val rotatedFile = rotatedFile(file, index)
                        if (rotatedFile.isFile) appendFile(rotatedFile, stream)
                    }
                    if (file.isFile) appendFile(file, stream)
                }
                Result.success(Unit)
            } catch (error: Exception) {
                Result.failure(error)
            }
            if (result.isFailure) {
                Log.e(TAG, "Could not export execution log", result.exceptionOrNull())
            }
            mainHandler.post { onComplete(result) }
        }
    }

    private fun rotateFiles(file: File) {
        val oldest = rotatedFile(file, FILE_COUNT - 1)
        if (oldest.exists() && !oldest.delete()) {
            error("Could not remove old execution log ${oldest.name}")
        }

        for (index in FILE_COUNT - 2 downTo 1) {
            val source = rotatedFile(file, index)
            if (source.exists() && !source.renameTo(rotatedFile(file, index + 1))) {
                error("Could not rotate execution log ${source.name}")
            }
        }

        if (file.exists() && !file.renameTo(rotatedFile(file, 1))) {
            error("Could not rotate execution log ${file.name}")
        }
    }

    private fun rotatedFile(file: File, index: Int) = File(file.parentFile, "${file.name}.$index")

    private fun appendFile(file: File, output: OutputStream) {
        file.inputStream().use { input -> input.copyTo(output) }
    }
}
