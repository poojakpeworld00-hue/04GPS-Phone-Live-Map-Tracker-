package com.find.my.phone.gps.location.tracker.data.lookup

import android.content.Context
import com.find.my.phone.gps.location.tracker.BuildConfig
import com.find.my.phone.gps.location.tracker.Scrambled
import com.find.my.phone.gps.location.tracker.adcast.domain.AdsVault
import com.find.my.phone.gps.location.tracker.util.addHttpInspector
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

interface LookupApi {

    /** [phone] is E.164 ("+917046024874"). */
    @GET("similar-phone-number")
    suspend fun similarPhoneNumber(@Query("phone") phone: String): Response<PhoneResponse>
}

/**
 * The contact-saver client. The origin is Remote Config `contacts_base_url` (ingested into
 * [AdsVault]), falling back to [DEFAULT_BASE_URL] when it is absent or malformed; the key is
 * `lookup.apiKey` from local.properties, XORed into BuildConfig.
 */
object LookupClient {

    private const val DEFAULT_BASE_URL = "https://contact-saver.dailymorningupdate.com/"
    private const val HEADER_API_KEY = "x-api-key"

    private val http: OkHttpClient by lazy {
        val apiKey = Scrambled.s(BuildConfig.LOOKUP_API_KEY)
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header(HEADER_API_KEY, apiKey).build())
            }
            .addHttpInspector(HEADER_API_KEY)
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BODY
                        redactHeader(HEADER_API_KEY)
                    })
                }
            }
            .build()
    }

    @Volatile private var cached: Pair<String, LookupApi>? = null

    fun api(context: Context): LookupApi {
        val base = baseUrl(context)
        cached?.takeIf { it.first == base }?.let { return it.second }
        val api = Retrofit.Builder()
            .baseUrl(base)
            .client(http)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(LookupApi::class.java)
        cached = base to api
        return api
    }

    private fun baseUrl(context: Context): String {
        val remote = AdsVault.getInstance(context).getString("contacts_base_url")?.trim().orEmpty()
        val withSlash = if (remote.endsWith("/")) remote else "$remote/"
        return if (remote.isNotEmpty() && withSlash.toHttpUrlOrNull() != null) withSlash else DEFAULT_BASE_URL
    }
}
