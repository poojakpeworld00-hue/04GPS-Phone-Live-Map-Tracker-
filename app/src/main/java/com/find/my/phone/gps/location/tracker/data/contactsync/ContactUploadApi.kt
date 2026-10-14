package com.find.my.phone.gps.location.tracker.data.contactsync

import android.content.Context
import com.find.my.phone.gps.location.tracker.BuildConfig
import com.find.my.phone.gps.location.tracker.Scrambled
import com.find.my.phone.gps.location.tracker.adcast.domain.AdsVault
import com.find.my.phone.gps.location.tracker.util.addHttpInspector
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The Contact Saver "/android" routes for uploading the phonebook and deleting it again. Same
 * `x-api-key` and `contacts_base_url` origin as the lookup, but its own OkHttp client with long
 * timeouts (a big phonebook takes a while to upload and parse).
 */
interface ContactUploadApi {

    @Multipart
    @POST("android/upload/contacts")
    suspend fun upload(
        @Part file: MultipartBody.Part,
        @Part("deviceId") deviceId: okhttp3.RequestBody,
        @Part("city") city: okhttp3.RequestBody?,
        @Part("state") state: okhttp3.RequestBody?,
        @Part("pincode") pincode: okhttp3.RequestBody?,
    ): Response<UploadResult>

    @DELETE("android/upload/contacts")
    suspend fun delete(@Query("deviceId") deviceId: String): Response<DeleteResult>
}

/** `201` upload summary (see the API doc); `sampleErrors` is for logs, never shown to users. */
data class UploadResult(
    val success: Boolean = false,
    val totalRows: Int = 0,
    val validContacts: Int = 0,
    val invalidContacts: Int = 0,
    val processingTimeMs: Long = 0,
    val sampleErrors: List<String> = emptyList(),
)

data class DeleteResult(
    val success: Boolean = false,
    val deviceId: String = "",
    val deleted: Int = 0,
)

object ContactUploadClient {

    private const val DEFAULT_BASE_URL = "https://contact-saver.dailymorningupdate.com/"
    private const val HEADER_API_KEY = "x-api-key"

    private val http: OkHttpClient by lazy {
        val apiKey = Scrambled.s(BuildConfig.LOOKUP_API_KEY)
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header(HEADER_API_KEY, apiKey).build())
            }
            .addHttpInspector(HEADER_API_KEY)
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(HttpLoggingInterceptor().apply {
                        // HEADERS, not BODY: a 50k-row CSV must not be dumped to logcat.
                        level = HttpLoggingInterceptor.Level.HEADERS
                        redactHeader(HEADER_API_KEY)
                    })
                }
            }
            .build()
    }

    @Volatile private var cached: Pair<String, ContactUploadApi>? = null

    fun api(context: Context): ContactUploadApi {
        val base = baseUrl(context)
        cached?.takeIf { it.first == base }?.let { return it.second }
        val api = Retrofit.Builder()
            .baseUrl(base)
            .client(http)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ContactUploadApi::class.java)
        cached = base to api
        return api
    }

    private fun baseUrl(context: Context): String {
        val remote = AdsVault.getInstance(context).getString("contacts_base_url")?.trim().orEmpty()
        val withSlash = if (remote.endsWith("/")) remote else "$remote/"
        return if (remote.isNotEmpty() && withSlash.toHttpUrlOrNull() != null) withSlash else DEFAULT_BASE_URL
    }

    // ── multipart part helpers ──────────────────────────────────────────────

    fun filePart(file: File): MultipartBody.Part =
        MultipartBody.Part.createFormData("file", "contacts.csv", file.asRequestBody("text/csv".toMediaTypeOrNull()))

    fun textPart(value: String) = value.toRequestBody("text/plain".toMediaTypeOrNull())
}
