package com.witvpn.ikev2.presentation.di

import com.witvpn.ikev2.data.repository.PayRepositoryImpl
import com.witvpn.ikev2.data.repository.ServerRepositoryImpl
import com.witvpn.ikev2.data.repository.UserRepositoryImpl
import com.witvpn.ikev2.domain.repository.PayRepository
import com.witvpn.ikev2.domain.repository.ServerRepository
import com.witvpn.ikev2.domain.repository.UserRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    @Singleton
    fun provideServerRepository(serverRepositoryImpl: ServerRepositoryImpl): ServerRepository {
        return serverRepositoryImpl
    }

    @Provides
    @Singleton
    fun provideUserRepository(userRepositoryImpl: UserRepositoryImpl): UserRepository {
        return userRepositoryImpl
    }

    @Provides
    @Singleton
    fun providePayURLRepository(payRepository: PayRepositoryImpl): PayRepository = payRepository
}