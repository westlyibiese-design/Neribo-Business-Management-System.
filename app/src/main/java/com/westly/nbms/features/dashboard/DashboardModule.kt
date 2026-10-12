package com.westly.nbms.features.dashboard

import com.westly.nbms.core.feature.DashboardProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class DashboardModule {
    @Binds
    @IntoSet
    abstract fun bind(p: ManagementDashboardProvider): DashboardProvider
}
