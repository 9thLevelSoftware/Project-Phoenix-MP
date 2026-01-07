package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.testutil.createTestDatabase
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SqlDelightUserProfileRepositoryTest {

    private lateinit var database: VitruvianDatabase
    private lateinit var repository: SqlDelightUserProfileRepository

    @Before
    fun setup() {
        database = createTestDatabase()
        repository = SqlDelightUserProfileRepository(database)
    }

    @Test
    fun `default profile exists on init`() = runTest {
        repository.refreshProfiles()

        val active = repository.activeProfile.value
        val all = repository.allProfiles.value

        assertEquals("default", active?.id)
        assertEquals(1, all.size)
    }

    @Test
    fun `createProfile and setActiveProfile updates active`() = runTest {
        val created = repository.createProfile("Alex", 2)
        repository.setActiveProfile(created.id)

        assertEquals(created.id, repository.activeProfile.value?.id)
        assertTrue(repository.allProfiles.value.any { it.id == created.id })
    }

    @Test
    fun `deleteProfile prevents deleting default and resets active`() = runTest {
        val created = repository.createProfile("Jordan", 1)
        repository.setActiveProfile(created.id)

        val deleteDefault = repository.deleteProfile("default")
        val deleteCreated = repository.deleteProfile(created.id)

        assertFalse(deleteDefault)
        assertTrue(deleteCreated)
        assertEquals("default", repository.activeProfile.value?.id)
    }
}
