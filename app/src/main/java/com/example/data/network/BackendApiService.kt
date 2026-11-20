package com.example.data.network

import com.example.BuildConfig
import com.squareup.moshi.JsonClass
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class InitiateUploadRequest(
    val fileName: String,
    val mimeType: String,
    val fileSizeBytes: Long
)

@JsonClass(generateAdapter = true)
data class InitiateUploadResponse(
    val uploadSessionId: String,
    val chunkSizeBytes: Long,
    val maxSizeBytes: Long
)

@JsonClass(generateAdapter = true)
data class OAuthUrlResponse(
    val authorizeUrl: String,
    val platform: String
)

@JsonClass(generateAdapter = true)
data class CloudStreamStartRequest(
    val streamId: String,
    val inputUrl: String,
    val outputUrls: List<String>,
    val platforms: List<String>,
    val orientation: String,
    val aspectFitMode: String,
    val resolution: String,
    val fps: Int,
    val bitrateKbps: Int,
    val audioBitrateKbps: Int,
    val loopMode: String,
    val durationLimitSeconds: Long
)

@JsonClass(generateAdapter = true)
data class WorkerTelemetryDto(
    val streamId: String,
    val status: String,
    val elapsedSeconds: Long,
    val reconnectAttempts: Int,
    val cpuPct: Int,
    val ramPct: Int,
    val lastError: String? = null
)

@JsonClass(generateAdapter = true)
data class CloudStreamResponse(
    val telemetry: WorkerTelemetryDto
)

@JsonClass(generateAdapter = true)
data class ServerHealthResponse(
    val serverStatus: String,
    val cpuPct: Int,
    val ramPct: Int,
    val activeWorkers: Int,
    val workers: List<WorkerTelemetryDto>
)

interface BackendApiService {
    @GET("oauth/{platform}/authorize")
    suspend fun getOAuthAuthorizeUrl(
        @Header("Authorization") bearer: String,
        @Path("platform") platform: String
    ): OAuthUrlResponse

    @POST("uploads/initiate")
    suspend fun initiateResumableUpload(
        @Header("Authorization") bearer: String,
        @Body request: InitiateUploadRequest
    ): InitiateUploadResponse

    @POST("cloud-streams/start")
    suspend fun startCloudStream(
        @Header("Authorization") bearer: String,
        @Body request: CloudStreamStartRequest
    ): CloudStreamResponse

    @POST("cloud-streams/{streamId}/stop")
    suspend fun stopCloudStream(
        @Header("Authorization") bearer: String,
        @Path("streamId") streamId: String
    ): Map<String, Boolean>

    @GET("admin/servers/health")
    suspend fun getAdminServerHealth(
        @Header("Authorization") bearer: String
    ): ServerHealthResponse

    companion object {
        fun create(): BackendApiService {
            val logging = HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) {
                    HttpLoggingInterceptor.Level.BASIC
                } else {
                    HttpLoggingInterceptor.Level.NONE
                }
            }
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .addInterceptor(logging)
                .build()

            return Retrofit.Builder()
                .baseUrl(BuildConfig.PUBLIC_API_BASE_URL)
                .client(client)
                .addConverterFactory(MoshiConverterFactory.create())
                .build()
                .create(BackendApiService::class.java)
        }
    }
}
