package com.westly.nbms.features.auth

import com.westly.nbms.core.feature.AuthFeature
import com.westly.nbms.core.feature.AuthScreenSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Registers the sign-in, register and forgot-password screens with the app root. */
@Singleton
class AuthFeatureImpl @Inject constructor() : AuthFeature {
    override val screens: List<AuthScreenSpec> = listOf(
        AuthScreenSpec("auth/login") { nav -> LoginScreen(nav) },
        AuthScreenSpec("auth/register") { nav -> RegisterScreen(nav) },
        AuthScreenSpec("auth/forgot") { nav -> ForgotPasswordScreen(nav) }
    )
}
