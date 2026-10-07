package com.westly.nbms.features.staff

import com.westly.nbms.core.feature.ShellOverlay
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class StaffModule {

    @Binds
    @Singleton
    abstract fun bindStaffAccountsApi(impl: StaffAccountsApiImpl): StaffAccountsApi

    /** Signs PIN users out after 15 idle minutes (order 10 = outermost). */
    @Binds
    @IntoSet
    abstract fun bindInactivityOverlay(impl: InactivityOverlay): ShellOverlay
}
