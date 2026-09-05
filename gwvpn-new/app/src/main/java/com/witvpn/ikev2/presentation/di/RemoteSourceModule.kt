package com.witvpn.ikev2.presentation.di

import android.content.Context
import com.google.gson.FieldNamingPolicy
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.witvpn.ikev2.BuildConfig
import com.witvpn.ikev2.R
import com.witvpn.ikev2.data.remote.ApiService
import com.witvpn.ikev2.presentation.utils.RemoteConfigManager
import com.witvpn.ikev2.presentation.utils.interceptor.ApiExceptionInterceptor
import com.witvpn.ikev2.presentation.utils.interceptor.ApiFailoverInterceptor
import com.witvpn.ikev2.presentation.utils.interceptor.ModifyRequestInterceptor
import com.witvpn.ikev2.presentation.utils.interceptor.UnsafeOkHttpClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RemoteSourceModule {

    @Singleton
    @Provides
    fun provideApiService(retrofit: Retrofit): ApiService {
        return retrofit.create(ApiService::class.java)
    }

    @Provides
    @Singleton
    fun provideGson() = GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .create()

    @Provides
    @Singleton
    fun provideHttpLoggingInterceptor(): HttpLoggingInterceptor {
        val logging = HttpLoggingInterceptor()
        val level = if (BuildConfig.DEBUG)
            HttpLoggingInterceptor.Level.BODY
        else
            HttpLoggingInterceptor.Level.NONE
        return logging.setLevel(level)
    }

    @Provides
    @Singleton
    fun provideRetrofit(
        @ApplicationContext context: Context, gson: Gson, logging: HttpLoggingInterceptor,
        apiExceptionInterceptor: ApiExceptionInterceptor,
        modifyRequestInterceptor: ModifyRequestInterceptor
    ): Retrofit {
        val okHttpClient = UnsafeOkHttpClient.getUnsafeOkHttpClientBuilder()
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // Connect 10s (а не 30s): при блокировке домена «в дроп» иначе
            // каждый недоступный адрес из api_base_urls висел бы полминуты
            // до перебора. Нормальный коннект к API укладывается в 1-2 секунды.
            .connectTimeout(10, TimeUnit.SECONDS)
            // Перебор адресов из Remote Config при блокировке текущего.
            // Должен идти ДО подписи запроса (modifyRequestInterceptor),
            // чтобы подпись считалась по итоговому хосту.
            .addInterceptor(ApiFailoverInterceptor(context))
            .addInterceptor(modifyRequestInterceptor)
            .addInterceptor(logging)
            .addInterceptor(apiExceptionInterceptor)
            .build()

        // baseUrl берётся из Remote Config (или из кэша/дефолта, если конфиг ещё
        // не скачался). Значение фиксируется на текущий запуск; смена хоста внутри
        // сессии и перебор адресов при блокировке обеспечиваются
        // ApiFailoverInterceptor выше.
        return Retrofit.Builder()
            .baseUrl(RemoteConfigManager.getApiBaseUrl(context))
            .addConverterFactory(GsonConverterFactory.create(gson))
            .client(okHttpClient)
            .build()
    }
}