package com.example.mushroomexposed

import java.io.File
import java.util.UUID

/** Bounded local JPEG store; callers receive only an opaque filename. */
class ReferenceImageStore(private val directory: File) {
    @Synchronized
    fun write(jpeg: ByteArray): String {
        require(jpeg.isNotEmpty()) { "reference JPEG must not be empty" }
        directory.mkdirs()
        check(directory.isDirectory) { "cannot create reference image directory" }
        val name = "${UUID.randomUUID()}.jpg"
        val target = File(directory, name)
        val temporary = File(directory, ".${name}.tmp")
        try {
            temporary.writeBytes(jpeg)
            check(temporary.renameTo(target)) { "cannot finalize reference image" }
            return name
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    @Synchronized
    fun discard(name: String?) {
        if (name.isNullOrBlank()) return
        File(directory, name).delete()
    }

    @Synchronized
    fun prune(keep: Set<String>) {
        directory.listFiles()?.forEach { file ->
            if (file.isFile && file.name !in keep) file.delete()
        }
        if (directory.isDirectory && directory.listFiles().isNullOrEmpty()) directory.delete()
    }

    @Synchronized
    fun clear() {
        if (directory.exists()) directory.deleteRecursively()
    }
}
