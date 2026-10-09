package com.westly.nbms.features.laundry

import com.westly.nbms.core.data.Resource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LaundryHistoryRepositoryTest {

    private class Rig {
        val store = LaundryHistoryFakeStore()
        val repo = LaundryHistoryRepository(store)
    }

    @Test fun observePassesTheValetScopeToTheStore() = runTest {
        val r = Rig()
        r.repo.observe("u1").first()
        r.repo.observe(null).first()
        assertEquals(listOf<String?>("u1", null), r.store.observedWith)
    }

    @Test fun observeHandsBackTheStoresRequests() = runTest {
        val r = Rig()
        r.store.requests.value = Resource.Success(listOf(laundryHistoryRequestOf("a"), laundryHistoryRequestOf("b")))
        val result = r.repo.observe(null).first() as Resource.Success
        assertEquals(listOf("a", "b"), result.data.map { it.id })
    }

    @Test fun softDeletedRequestsAreLeftOut() = runTest {
        val r = Rig()
        r.store.requests.value = Resource.Success(
            listOf(laundryHistoryRequestOf("a"), laundryHistoryRequestOf("gone", deleted = true), laundryHistoryRequestOf("c"))
        )
        val result = r.repo.observe(null).first() as Resource.Success
        assertEquals(listOf("a", "c"), result.data.map { it.id })
    }

    @Test fun loadingAndErrorPassThroughUnchanged() = runTest {
        val r = Rig()
        r.store.requests.value = Resource.Loading
        assertTrue(r.repo.observe(null).first() is Resource.Loading)
        r.store.requests.value = Resource.Error("We couldn't load laundry history.")
        val error = r.repo.observe(null).first() as Resource.Error
        assertEquals("We couldn't load laundry history.", error.message)
    }
}
