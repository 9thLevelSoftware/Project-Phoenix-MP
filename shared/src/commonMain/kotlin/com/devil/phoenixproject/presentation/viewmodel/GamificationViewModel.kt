package com.devil.phoenixproject.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.devil.phoenixproject.data.local.BadgeDefinitions
import com.devil.phoenixproject.data.repository.BadgeWithProgress
import com.devil.phoenixproject.data.repository.GamificationRepository
import com.devil.phoenixproject.domain.model.*
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.domain.premium.RpgAttributeEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GamificationViewModel(
    private val repository: GamificationRepository
) : ViewModel() {

    // UI State
    private val _selectedCategory = MutableStateFlow<BadgeCategory?>(null)
    val selectedCategory: StateFlow<BadgeCategory?> = _selectedCategory.asStateFlow()

    private val _badgesWithProgress = MutableStateFlow<List<BadgeWithProgress>>(emptyList())
    val badgesWithProgress: StateFlow<List<BadgeWithProgress>> = _badgesWithProgress.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Streak info from repository (real-time)
    val streakInfo: StateFlow<StreakInfo> = repository.getStreakInfo()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StreakInfo.EMPTY)

    // Gamification stats from repository
    val gamificationStats: StateFlow<GamificationStats> = repository.getGamificationStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GamificationStats.EMPTY)

    // Uncelebrated badges for celebration dialog
    val uncelebratedBadges: StateFlow<List<EarnedBadge>> = repository.getUncelebratedBadges()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // RPG Profile state (computed on demand from BadgesScreen)
    private val _rpgProfile = MutableStateFlow<RpgProfile?>(null)
    val rpgProfile: StateFlow<RpgProfile?> = _rpgProfile.asStateFlow()

    // Filtered badges based on selected category
    val filteredBadges: StateFlow<List<BadgeWithProgress>> = combine(
        _badgesWithProgress,
        _selectedCategory
    ) { badges, category ->
        if (category == null) {
            badges
        } else {
            badges.filter { it.badge.category == category }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Badge statistics
    val earnedBadgeCount: Int
        get() = _badgesWithProgress.value.count { it.isEarned }

    val totalBadgeCount: Int
        get() = BadgeDefinitions.totalBadgeCount

    init {
        loadBadges()
    }

    fun loadBadges() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val badges = repository.getAllBadgesWithProgress()
                _badgesWithProgress.value = badges
            } catch (e: Exception) {
                // Handle error
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Compute RPG profile from aggregate workout data.
     * Called from BadgesScreen LaunchedEffect (not init) to avoid
     * unnecessary computation for users who never visit the screen.
     */
    fun loadRpgProfile() {
        viewModelScope.launch {
            try {
                val input = repository.getRpgInput()
                val profile = RpgAttributeEngine.computeProfile(input)
                _rpgProfile.value = profile
                repository.saveRpgProfile(profile.copy(lastComputed = currentTimeMillis()))
            } catch (e: Exception) {
                // Log error, leave profile null (card won't show)
            }
        }
    }

    fun selectCategory(category: BadgeCategory?) {
        _selectedCategory.value = category
    }

    fun markBadgeCelebrated(badgeId: String) {
        viewModelScope.launch {
            repository.markBadgeCelebrated(badgeId)
        }
    }

    /**
     * Update stats and check for new badges
     * Should be called after workout completion
     */
    suspend fun updateAndCheckBadges(): List<Badge> {
        repository.updateStats()
        val newBadges = repository.checkAndAwardBadges()
        if (newBadges.isNotEmpty()) {
            loadBadges() // Refresh badge list
        }
        return newBadges
    }
}
