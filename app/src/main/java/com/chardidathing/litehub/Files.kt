package com.chardidathing.litehub

import java.io.File

// write beside and rename over, so a power cut mid write leaves the old file whole
fun File.writeAtomic(text: String) {
    val partial = File(parentFile, "$name.partial")
    partial.writeText(text)
    if (!partial.renameTo(this)) {
        delete()
        partial.renameTo(this)
    }
}
