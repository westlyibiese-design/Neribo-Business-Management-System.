package com.westly.nbms.features.auth

import com.westly.nbms.core.feature.AuthFeature
import com.westly.nbms.core.feature.AuthScreenSpec
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import javax.inject.Singleton

/** Registers the shared-device PIN screen (route `auth/pin`) with the app root. */
@Singleton
class PinAuthFeature @Inject constructor() : AuthFeature {
    override val screens: List<AuthScreenSpec> = listOf(
        AuthScreenSpec("auth/pin") { nav -> PinLoginScreen(nav) }
    )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PinAuthModule {
    @Binds
    @IntoSet
    abstract fun bindPinAuthFeature(impl: PinAuthFeature): AuthFeature
}
