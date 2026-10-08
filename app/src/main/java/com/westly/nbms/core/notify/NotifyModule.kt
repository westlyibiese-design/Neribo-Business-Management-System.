package com.westly.nbms.core.notify

import com.westly.nbms.core.archive.SoftDeleteListener
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import javax.inject.Singleton

/** Sends the "Record Deleted" staff alert (to super admins and managers) whenever a record is soft-deleted. */
@Singleton
class RecordDeletedAlert @Inject constructor(
    private val notifier: Notifier,
    private val session: SessionManager
) : SoftDeleteListener {

    override suspend fun onSoftDeleted(collection: String, label: String, reason: String?) {
        val name = (session.state.value as? SessionState.SignedIn)?.user?.name ?: return
        notifier.notifyStaffAlert("Record Deleted", recordDeletedMessage(name, collection, reason), "warning")
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class NotifyModule {

    @Binds
    @Singleton
    abstract fun bindNotifier(impl: NotifierImpl): Notifier

    @Binds
    @IntoSet
    abstract fun bindRecordDeletedAlert(impl: RecordDeletedAlert): SoftDeleteListener
}
