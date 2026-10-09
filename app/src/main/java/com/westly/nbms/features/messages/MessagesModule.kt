package com.westly.nbms.features.messages

import com.westly.nbms.core.feature.NbmsFeature
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * [MessagesApi] and [MessagesRealtime] are `@Singleton` classes with `@Inject` constructors,
 * so Hilt provides them without extra code. Only the feature needs binding.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class MessagesModule {

    @Binds
    @IntoSet
    abstract fun bindMessagesFeature(impl: MessagesFeature): NbmsFeature
}
