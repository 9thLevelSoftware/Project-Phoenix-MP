package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.database.VitruvianDatabase

/**
 * Factory for creating in-memory test databases.
 * Each platform provides its own implementation using the appropriate driver.
 */
expect fun createTestDatabase(): VitruvianDatabase
