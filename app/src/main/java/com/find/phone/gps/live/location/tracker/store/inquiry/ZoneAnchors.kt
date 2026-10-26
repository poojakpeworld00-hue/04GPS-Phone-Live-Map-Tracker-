package com.find.phone.gps.live.location.tracker.store.inquiry

import java.util.Locale

// Offline map anchor when the device Geocoder returns nothing (common on real devices): city, then state, then country.
object ZoneAnchors {

    fun fallback(iso2: String?, city: String?, state: String?): Pair<TerraTack, SectorExactness>? {
        val cc = iso2?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.length == 2 }
        if (cc == "IN") {
            city?.let { INDIA_CITY[clean(it)] }?.let { return it to SectorExactness.CITY }
            state?.let { indiaState(it) }?.let { return it to SectorExactness.STATE }
        }
        cc?.let { COUNTRY[it] }?.let { return it to SectorExactness.COUNTRY }
        return null
    }

    private fun clean(raw: String): String =
        raw.lowercase(Locale.ROOT).trim().removePrefix("state of ").removeSuffix(" state")
            .removeSuffix(" city").removeSuffix(" district").trim()

    private fun indiaState(raw: String): TerraTack? {
        val key = clean(raw)
        return INDIA_STATE[key] ?: INDIA_STATE.entries.firstOrNull { key.contains(it.key) || it.key.contains(key) }?.value
    }

    private fun m(lat: Double, lng: Double) = TerraTack(lat, lng)

    private val INDIA_CITY: Map<String, TerraTack> = mapOf(
        "surat" to m(21.1702, 72.8311), "ahmedabad" to m(23.0225, 72.5714), "vadodara" to m(22.3072, 73.1812),
        "baroda" to m(22.3072, 73.1812), "rajkot" to m(22.3039, 70.8022), "bhavnagar" to m(21.7645, 72.1519),
        "jamnagar" to m(22.4707, 70.0577), "junagadh" to m(21.5222, 70.4579), "gandhinagar" to m(23.2156, 72.6369),
        "anand" to m(22.5645, 72.9289), "nadiad" to m(22.6939, 72.8618), "surendranagar" to m(22.7271, 71.6379),
        "morbi" to m(22.8173, 70.8370), "mehsana" to m(23.5880, 72.3693), "bharuch" to m(21.7051, 72.9959),
        "navsari" to m(20.9467, 72.9520), "valsad" to m(20.5992, 72.9342), "porbandar" to m(21.6417, 69.6293),
        "vapi" to m(20.3893, 72.9106), "gandhidham" to m(23.0753, 70.1337), "bhuj" to m(23.2419, 69.6669),
        "mumbai" to m(19.0760, 72.8777), "delhi" to m(28.6139, 77.2090), "new delhi" to m(28.6139, 77.2090),
        "bengaluru" to m(12.9716, 77.5946), "bangalore" to m(12.9716, 77.5946), "hyderabad" to m(17.3850, 78.4867),
        "chennai" to m(13.0827, 80.2707), "kolkata" to m(22.5726, 88.3639), "pune" to m(18.5204, 73.8567),
        "jaipur" to m(26.9124, 75.7873), "lucknow" to m(26.8467, 80.9462), "kanpur" to m(26.4499, 80.3319),
        "nagpur" to m(21.1458, 79.0882), "indore" to m(22.7196, 75.8577), "bhopal" to m(23.2599, 77.4126),
        "patna" to m(25.5941, 85.1376), "ludhiana" to m(30.9010, 75.8573), "agra" to m(27.1767, 78.0081),
        "nashik" to m(19.9975, 73.7898), "faridabad" to m(28.4089, 77.3178), "ghaziabad" to m(28.6692, 77.4538),
        "coimbatore" to m(11.0168, 76.9558), "kochi" to m(9.9312, 76.2673), "cochin" to m(9.9312, 76.2673),
        "visakhapatnam" to m(17.6868, 83.2185), "thiruvananthapuram" to m(8.5241, 76.9366),
        "vijayawada" to m(16.5062, 80.6480), "madurai" to m(9.9252, 78.1198), "varanasi" to m(25.3176, 82.9739),
        "meerut" to m(28.9845, 77.7064), "amritsar" to m(31.6340, 74.8723), "chandigarh" to m(30.7333, 76.7794),
        "noida" to m(28.5355, 77.3910), "gurugram" to m(28.4595, 77.0266), "gurgaon" to m(28.4595, 77.0266),
        "ranchi" to m(23.3441, 85.3096), "guwahati" to m(26.1445, 91.7362), "raipur" to m(21.2514, 81.6296),
        "jodhpur" to m(26.2389, 73.0243), "kota" to m(25.2138, 75.8648), "dehradun" to m(30.3165, 78.0322),
        "jabalpur" to m(23.1815, 79.9864), "gwalior" to m(26.2183, 78.1828), "aurangabad" to m(19.8762, 75.3433),
        "solapur" to m(17.6599, 75.9064), "mangalore" to m(12.9141, 74.8560), "mysuru" to m(12.2958, 76.6394),
        "mysore" to m(12.2958, 76.6394), "tiruchirappalli" to m(10.7905, 78.7047), "salem" to m(11.6643, 78.1460),
        "bhubaneswar" to m(20.2961, 85.8245), "cuttack" to m(20.4625, 85.8830), "siliguri" to m(26.7271, 88.3953),
        "howrah" to m(22.5958, 88.2636), "allahabad" to m(25.4358, 81.8463), "prayagraj" to m(25.4358, 81.8463),
        "jamshedpur" to m(22.8046, 86.2029), "dhanbad" to m(23.7957, 86.4304), "udaipur" to m(24.5854, 73.7125),
        "ajmer" to m(26.4499, 74.6399), "bareilly" to m(28.3670, 79.4304), "aligarh" to m(27.8974, 78.0880),
        "moradabad" to m(28.8386, 78.7733), "saharanpur" to m(29.9680, 77.5452), "gorakhpur" to m(26.7606, 83.3732),
        "jalandhar" to m(31.3260, 75.5762), "patiala" to m(30.3398, 76.3869), "warangal" to m(17.9689, 79.5941),
        "guntur" to m(16.3067, 80.4365), "nellore" to m(14.4426, 79.9865), "tirupati" to m(13.6288, 79.4192),
        "kozhikode" to m(11.2588, 75.7804), "calicut" to m(11.2588, 75.7804), "thrissur" to m(10.5276, 76.2144),
        "kollam" to m(8.8932, 76.6141), "kannur" to m(11.8745, 75.3704),
    )

    private val INDIA_STATE: Map<String, TerraTack> = mapOf(
        "andhra pradesh" to m(15.91, 79.74), "arunachal pradesh" to m(28.22, 94.73),
        "assam" to m(26.20, 92.94), "bihar" to m(25.70, 85.31), "chhattisgarh" to m(21.28, 81.87),
        "goa" to m(15.30, 74.12), "gujarat" to m(22.59, 71.62), "haryana" to m(29.24, 76.32),
        "himachal pradesh" to m(31.90, 77.17), "jharkhand" to m(23.61, 85.28),
        "karnataka" to m(15.32, 75.71), "kerala" to m(10.50, 76.27), "madhya pradesh" to m(23.47, 78.46),
        "maharashtra" to m(19.66, 75.71), "manipur" to m(24.66, 93.91), "meghalaya" to m(25.47, 91.37),
        "mizoram" to m(23.24, 92.94), "nagaland" to m(26.16, 94.56), "odisha" to m(20.94, 85.10),
        "orissa" to m(20.94, 85.10), "punjab" to m(31.15, 75.34), "rajasthan" to m(27.02, 74.22),
        "sikkim" to m(27.53, 88.51), "tamil nadu" to m(11.13, 78.66), "telangana" to m(17.91, 79.59),
        "tripura" to m(23.75, 91.72), "uttar pradesh" to m(26.85, 80.95), "uttarakhand" to m(30.07, 79.32),
        "west bengal" to m(22.99, 87.86), "delhi" to m(28.61, 77.21), "jammu and kashmir" to m(33.78, 76.58),
        "ladakh" to m(34.21, 77.61), "puducherry" to m(11.94, 79.81), "chandigarh" to m(30.73, 76.78),
        "andaman and nicobar islands" to m(11.74, 92.66),
        "dadra and nagar haveli and daman and diu" to m(20.27, 73.02), "lakshadweep" to m(10.57, 72.64),
    )

    private val COUNTRY: Map<String, TerraTack> = mapOf(
        "IN" to m(22.59, 79.00), "US" to m(39.83, -98.58), "GB" to m(54.00, -2.00), "CA" to m(56.13, -106.35),
        "AU" to m(-25.27, 133.78), "PK" to m(30.38, 69.35), "BD" to m(23.69, 90.36), "LK" to m(7.87, 80.77),
        "NP" to m(28.39, 84.12), "AE" to m(23.42, 53.85), "SA" to m(23.89, 45.08), "QA" to m(25.35, 51.18),
        "KW" to m(29.31, 47.48), "OM" to m(21.47, 55.98), "BH" to m(26.07, 50.56), "SG" to m(1.35, 103.82),
        "MY" to m(4.21, 101.98), "ID" to m(-0.79, 113.92), "TH" to m(15.87, 100.99), "VN" to m(14.06, 108.28),
        "PH" to m(12.88, 121.77), "CN" to m(35.86, 104.20), "JP" to m(36.20, 138.25), "KR" to m(35.91, 127.77),
        "HK" to m(22.32, 114.17), "TW" to m(23.70, 120.96), "RU" to m(61.52, 105.32), "TR" to m(38.96, 35.24),
        "IR" to m(32.43, 53.69), "IQ" to m(33.22, 43.68), "IL" to m(31.05, 34.85), "EG" to m(26.82, 30.80),
        "ZA" to m(-30.56, 22.94), "NG" to m(9.08, 8.68), "KE" to m(-0.02, 37.91), "GH" to m(7.95, -1.02),
        "ET" to m(9.15, 40.49), "TZ" to m(-6.37, 34.89), "MA" to m(31.79, -7.09), "DZ" to m(28.03, 1.66),
        "DE" to m(51.17, 10.45), "FR" to m(46.23, 2.21), "IT" to m(41.87, 12.57), "ES" to m(40.46, -3.75),
        "PT" to m(39.40, -8.22), "NL" to m(52.13, 5.29), "BE" to m(50.50, 4.47), "CH" to m(46.82, 8.23),
        "AT" to m(47.52, 14.55), "SE" to m(60.13, 18.64), "NO" to m(60.47, 8.47), "DK" to m(56.26, 9.50),
        "FI" to m(61.92, 25.75), "PL" to m(51.92, 19.15), "UA" to m(48.38, 31.17), "RO" to m(45.94, 24.97),
        "GR" to m(39.07, 21.82), "IE" to m(53.41, -8.24), "CZ" to m(49.82, 15.47), "HU" to m(47.16, 19.50),
        "BR" to m(-14.24, -51.93), "MX" to m(23.63, -102.55), "AR" to m(-38.42, -63.62), "CO" to m(4.57, -74.30),
        "CL" to m(-35.68, -71.54), "PE" to m(-9.19, -75.02), "VE" to m(6.42, -66.59), "NZ" to m(-40.90, 174.89),
    )
}
