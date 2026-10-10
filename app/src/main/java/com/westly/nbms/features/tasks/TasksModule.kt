package com.westly.nbms.features.tasks

import com.westly.nbms.core.feature.NbmsFeature
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class TasksModule {
    @Binds
    @IntoSet
    abstract fun bind(f: TasksFeature): NbmsFeature
}
