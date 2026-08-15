package com.devil.phoenixproject.di

import com.devil.phoenixproject.data.repository.ActiveWorkoutRuntimeRepository
import com.devil.phoenixproject.data.repository.SqlDelightActiveWorkoutRuntimeRepository
import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.testutil.createTestDatabase
import kotlin.test.assertIs
import org.junit.Test
import org.koin.dsl.koinApplication
import org.koin.dsl.module

class ActiveWorkoutRuntimeRepositoryBindingTest {
    @Test
    fun dataModuleResolvesTheSqlRuntimeRepository() {
        val database = createTestDatabase()
        val application = koinApplication {
            allowOverride(true)
            modules(
                dataModule,
                module { single<VitruvianDatabase> { database } },
            )
        }

        try {
            assertIs<SqlDelightActiveWorkoutRuntimeRepository>(application.koin.get<ActiveWorkoutRuntimeRepository>())
        } finally {
            application.close()
        }
    }
}
