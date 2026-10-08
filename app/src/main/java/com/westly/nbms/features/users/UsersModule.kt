package com.westly.nbms.features.users

import com.westly.nbms.core.feature.NbmsFeature
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class UsersModule {

    @Binds
    @IntoSet
    abstract fun bindUsersFeature(impl: UsersFeature): NbmsFeature

    @Binds
    @Singleton
    abstract fun bindBusinessRolesApi(impl: BusinessRolesApiImpl): BusinessRolesApi
}
