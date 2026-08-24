package com.witvpn.ikev2.presentation.di

import android.content.Context
import android.content.SharedPreferences
import com.witvpn.ikev2.data.AppSettings
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.strongswan.android.data.VpnProfileDataSource
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LocalSourceModule {

    @Singleton
    @Provides
    fun provideAppSharedPreferences(@ApplicationContext context: Context): SharedPreferences {
        return context.getSharedPreferences("GLOBAL_APP_SHARES", Context.MODE_PRIVATE)
    }

    @Singleton
    @Provides
    fun provideAppSettings(sharedPreferences: SharedPreferences): AppSettings {
        return AppSettings(sharedPreferences)
    }

    @Singleton
    @Provides
    fun provideVpnProfileDataSource(@ApplicationContext context: Context): VpnProfileDataSource {
        return VpnProfileDataSource(context)
    }
}