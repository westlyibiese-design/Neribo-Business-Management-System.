package com.westly.nbms.features.device

import com.westly.nbms.core.feature.NbmsFeature
import com.westly.nbms.core.feature.ShellOverlay
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DeviceModule {

    @Binds
    @Singleton
    abstract fun bindDeviceApi(impl: DeviceApiImpl): DeviceApi

    @Binds
    abstract fun bindDeviceLockController(impl: DeviceLockControllerImpl): DeviceLockController

    /** Locks the app after 5 idle minutes (order 20, inside the inactivity logout). */
    @Binds
    @IntoSet
    abstract fun bindDeviceLockOverlay(impl: DeviceLockOverlay): ShellOverlay

    @Binds
    @IntoSet
    abstract fun bindDeviceFeature(impl: DeviceFeature): NbmsFeature

    /** Other phases (Phase 10 push notifications) add cards to Device Settings with `@IntoSet`. */
    @Multibinds
    abstract fun deviceSettingsSections(): Set<DeviceSettingsSection>
}
