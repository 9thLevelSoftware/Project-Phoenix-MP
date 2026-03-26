package com.devil.phoenixproject.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.devil.phoenixproject.data.local.BadgeDefinitions
import com.devil.phoenixproject.data.repository.BadgeWithProgress
import com.devil.phoenixproject.data.repository.GamificationRepository
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.domain.model.*
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.domain.premium.RpgAttributeEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GamificationViewModel(
    private val repository: GamificationRepository,
    private val userProfileRepository: UserProfileRepository
) : ViewModel() {

    /** Resolved active profile ID, reactive to profile switches. */
    private val activeProfileId: StateFlow<String> = userProfileRepository.activeProfile
        .map { it?.id ?: "default" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "default")

    // UI State
    private val _selectedCategory = MutableStateFlow<BadgeCategory?>(null)
    val selectedCategory: StateFlow<BadgeCategory?> = _selectedCategory.asStateFlow()

    private val _badgesWithProgress = MutableStateFlow<List<BadgeWithProgress>>(emptyList())
    val badgesWithProgress: StateFlow<List<BadgeWithProgress>> = _badgesWithProgress.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Streak info from repository (real-time, reactive to profile switches)
    val streakInfo: StateFlow<StreakInfo> = activeProfileId
        .flatMapLatest { profileId -> repository.getStreakInfo(profileId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StreakInfo.EMPTY)

    // Gamification stats from repository (reactive to profile switches)
    val gamificationStats: StateFlow<GamificationStats> = activeProfileId
        .flatMapLatest { profileId -> repository.getGamificationStats(profileId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GamificationStats.EMPTY)

    // Uncelebrated badges for celebration dialog (reactive to profile switches)
    val uncelebratedBadges: StateFlow<List<EarnedBadge>> = activeProfileId
        .flatMapLatest { profileId -> repository.getUncelebratedBadges(profileId) }
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
                val profileId = activeProfileId.value
                val badges = repository.getAllBadgesWithProgress(profileId)
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
                val profileId = activeProfileId.value
                val input = repository.getRpgInput(profileId)
                val profile = RpgAttributeEngine.computeProfile(input)
                _rpgProfile.value = profile
                repository.saveRpgProfile(profile.copy(lastComputed = currentTimeMillis()), profileId)
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
            val profileId = activeProfileId.value
            repository.markBadgeCelebrated(badgeId, profileId)
        }
    }

    /**
     * Update stats and check for new badges
     * Should be called after workout completion
     */
    suspend fun updateAndCheckBadges(): List<Badge> {
        val profileId = activeProfileId.value
        repository.updateStats(profileId)
        val newBadges = repository.checkAndAwardBadges(profileId)
        if (newBadges.isNotEmpty()) {
            loadBadges() // Refresh badge list
        }
        return newBadges
    }
}
