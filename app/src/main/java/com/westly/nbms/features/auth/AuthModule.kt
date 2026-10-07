package com.westly.nbms.features.auth

import com.westly.nbms.core.feature.AuthFeature
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import io.github.jan.supabase.SupabaseClient
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AuthModule {

    @Binds
    @IntoSet
    abstract fun bindAuthFeature(impl: AuthFeatureImpl): AuthFeature

    companion object {
        /** The second, non-persistent Supabase client used only by password recovery. */
        @Provides
        @Singleton
        @Named("recovery")
        fun provideRecoverySupabaseClient(): SupabaseClient = createRecoverySupabaseClient()
    }
}
