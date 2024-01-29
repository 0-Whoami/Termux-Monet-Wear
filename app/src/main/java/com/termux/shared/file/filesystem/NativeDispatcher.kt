package com.termux.shared.file.filesystem

import android.system.ErrnoException
import android.system.Os
import java.io.IOException

internal object NativeDispatcher {
    @Throws(IOException::class)
    fun stat(filePath: String, fileAttributes: FileAttributes) {
        validateFileExistence(filePath)
        try {
            fileAttributes.loadFromStructStat(Os.stat(filePath))
        } catch (e: ErrnoException) {
            throw IOException("Failed to run Os.stat() on file at path \"" + filePath + "\": " + e.message)
        }
    }

    @Throws(IOException::class)
    fun lstat(filePath: String, fileAttributes: FileAttributes) {
        validateFileExistence(filePath)
        try {
            fileAttributes.loadFromStructStat(Os.lstat(filePath))
        } catch (e: ErrnoException) {
            throw IOException("Failed to run Os.lstat() on file at path \"" + filePath + "\": " + e.message)
        }
    }

    @Throws(IOException::class)
    private fun validateFileExistence(filePath: String?) {
        if (filePath.isNullOrEmpty()) throw IOException("The path is null or empty")
    }
}