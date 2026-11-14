package com.maisonsmd.catdrive.lib

object BlePayloadEncoder {
    fun encodeSettings(lightTheme: Boolean, brightness: Int, speedLimit: Int): String =
        KeyValueProtocol.encode(
            linkedMapOf(
                "lightTheme" to lightTheme.toString(),
                "brightness" to brightness.toString(),
                "speedLimit" to speedLimit.toString()
            )
        )

    fun encodeNavigation(
        nextRoad: String?,
        nextRoadDescription: String?,
        distanceToNext: String?,
        totalDistance: String?,
        eta: String?,
        ete: String?,
        iconHash: String
    ): String = KeyValueProtocol.encode(
        linkedMapOf(
            "nextRd" to sanitize(nextRoad),
            "nextRdDesc" to sanitize(nextRoadDescription),
            "distToNext" to sanitize(distanceToNext),
            "totalDist" to sanitize(totalDistance),
            "eta" to sanitize(eta),
            "ete" to sanitize(ete),
            "iconHash" to iconHash
        )
    )

    private fun sanitize(value: String?): String =
        value.orEmpty().replace("\u00a0", " ").replace("\n", " ").replace("…", "...")
}
