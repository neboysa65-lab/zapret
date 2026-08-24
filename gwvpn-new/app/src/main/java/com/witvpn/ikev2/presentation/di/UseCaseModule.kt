package com.witvpn.ikev2.presentation.di

import com.witvpn.ikev2.data.AppSettings
import com.witvpn.ikev2.features.entropy.EntropyUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object UseCaseModule {

    @Singleton
    @Provides
    fun provideEntropyUseCase(appSettings: AppSettings): EntropyUseCase {
        return EntropyUseCase(appSettings)
    }
}