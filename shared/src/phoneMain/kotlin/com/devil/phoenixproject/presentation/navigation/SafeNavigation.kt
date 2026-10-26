package com.devil.phoenixproject.presentation.navigation

import androidx.navigation.NavController

/**
 * Pops the back stack to [dest]. If [dest] is not in the back stack (e.g. after process
 * recreation or an unusual entry path), navigates to [dest] directly, preserving Home as
 * the bottom entry so the user always has a back-path.
 */
fun NavController.safePopOrNavigate(dest: String) {
    if (!popBackStack(dest, false)) {
        navigate(dest) {
            popUpTo(NavigationRoutes.Home.route) { inclusive = false }
        }
    }
}
