package com.westly.nbms.features.notifications

import com.westly.nbms.core.feature.NbmsFeature
import com.westly.nbms.core.feature.TopBarAction
import com.westly.nbms.features.device.DeviceSettingsSection
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class NotificationsFeatureModule {

    @Binds
    @IntoSet
    abstract fun bindNotificationsFeature(impl: NotificationsFeature): NbmsFeature

    @Binds
    @IntoSet
    abstract fun bindNotificationBell(impl: NotificationBell): TopBarAction

    @Binds
    @IntoSet
    abstract fun bindPushSection(impl: PushDeviceSettingsSection): DeviceSettingsSection
}
