package com.maisonsmd.catdrive.lib

object KeyValueProtocol {
    fun encode(values: Map<String, String>): String =
        values.entries.joinToString(separator = "\n") { (key, value) -> "$key=$value" }
}
