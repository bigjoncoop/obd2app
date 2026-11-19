package com.obd2logger.data.protocol

/**
 * Maps standard 17-character VIN World Manufacturer Identifier (WMI, chars 0..2)
 * to vehicle make names for filtering candidate manufacturer PIDs.
 */
object VinWmiDecoder {

    fun detectMakesFromVin(vin: String): Set<String> {
        val clean = vin.trim().uppercase()
        if (clean.length < 3 || clean == "-") return emptySet()
        val wmi3 = clean.substring(0, 3)
        val wmi2 = clean.substring(0, 2)

        return when {
            wmi3 in setOf("1GC", "1GT", "1G1", "1G2", "1G3", "1G4", "1G6", "1GY", "2G1", "3G1", "3GC", "3GT", "KL1", "KL8") ||
                wmi2 in setOf("1G", "2G", "3G") ->
                setOf("GM", "Chevrolet", "GMC", "Cadillac", "Buick")

            wmi3 in setOf("1FA", "1FB", "1FC", "1FD", "1FM", "1FT", "2FA", "2FM", "2FT", "3FA", "3FT", "WF0", "1LN", "5LM") ||
                wmi2 in setOf("1F", "2F", "3F") ->
                setOf("Ford", "Lincoln", "Mercury")

            wmi3 in setOf("JTD", "JTE", "JTH", "JTJ", "JTK", "JTL", "JTM", "JTN", "2T1", "2T2", "4T1", "4T3", "4T4", "5TD", "5TF", "5YJ") && wmi3 != "5YJ" ||
                wmi2 in setOf("JT", "4T", "2T") ->
                setOf("Toyota", "Lexus", "Scion")

            wmi3 in setOf("1HG", "2HG", "2HK", "2HN", "5FN", "5J6", "5J8", "JHM", "JH4", "SHH", "SHS") ||
                wmi2 == "JH" ->
                setOf("Honda", "Acura")

            wmi3 in setOf("KMH", "KM8", "KNA", "KND", "5NP", "5NM", "5XY") ||
                wmi2 in setOf("KM", "KN") ->
                setOf("Hyundai", "Kia", "Genesis")

            wmi3 in setOf("WVW", "WV1", "WV2", "WAU", "WA1", "TRU", "3VW", "1VW", "WP0", "WP1") ||
                wmi2 in setOf("WV", "WA") ->
                setOf("Volkswagen", "Audi", "Porsche")

            wmi3 in setOf("WBA", "WBS", "WBX", "WBY", "WMW", "4US", "5UX", "5YM") ||
                wmi2 == "WB" ->
                setOf("BMW", "MINI")

            else -> emptySet()
        }
    }
}
