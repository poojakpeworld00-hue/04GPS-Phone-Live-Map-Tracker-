package com.find.phone.gps.live.location.tracker.store.inquiry

import android.content.Context
import com.find.phone.gps.live.location.tracker.BuildConfig
import com.find.phone.gps.live.location.tracker.Muddle
import com.find.phone.gps.live.location.tracker.monetize.logic.SpotsLocker
import com.find.phone.gps.live.location.tracker.helpers.appendHttpInspector
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

interface InquiryEndpoint {
    @GET("similar-phone-number")
    suspend fun similarPhoneNumber(@Query("phone") phone: String): Response<MobileAnswer>
}

object InquiryCaller {
    private const val FALLBACK_ORIGIN = "https://contact-saver.dailymorningupdate.com/"
    private const val KEY_HEADER = "x-api-key"

    @Volatile private var memo: Pair<String, InquiryEndpoint>? = null

    private val client: OkHttpClient by lazy {
        val secret = Muddle.s(BuildConfig.LOOKUP_API_KEY)
        val builder = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val signed = chain.request().newBuilder().header(KEY_HEADER, secret).build()
                chain.proceed(signed)
            }
            .appendHttpInspector(KEY_HEADER)
        if (BuildConfig.DEBUG) {
            val logger = HttpLoggingInterceptor()
            logger.level = HttpLoggingInterceptor.Level.BODY
            logger.redactHeader(KEY_HEADER)
            builder.addInterceptor(logger)
        }
        builder.build()
    }

    fun api(context: Context): InquiryEndpoint {
        val origin = resolveOrigin(context)
        val hit = memo
        if (hit != null && hit.first == origin) return hit.second
        return Retrofit.Builder()
            .baseUrl(origin)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(InquiryEndpoint::class.java)
            .also { memo = origin to it }
    }

    private fun resolveOrigin(context: Context): String {
        val configured = SpotsLocker.getInstance(context).getString("contacts_base_url")?.trim() ?: ""
        if (configured.isEmpty()) return FALLBACK_ORIGIN
        val normalized = configured.takeIf { it.endsWith("/") } ?: "$configured/"
        return normalized.takeIf { it.toHttpUrlOrNull() != null } ?: FALLBACK_ORIGIN
    }
}
