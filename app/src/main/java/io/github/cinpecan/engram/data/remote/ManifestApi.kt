package io.github.cinpecan.engram.data.remote

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET

interface ManifestApi {
    @GET("/api/v1/models/manifest")
    suspend fun getModelManifest(): ModelManifestResponseDto

    companion object {
        fun create(
            baseUrl: String,
            okHttpClient: OkHttpClient,
            json: Json,
        ): ManifestApi {
            val normalized = baseUrl.trim().trimEnd('/') + "/"
            return Retrofit.Builder()
                .baseUrl(normalized)
                .client(okHttpClient)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(ManifestApi::class.java)
        }
    }
}
