/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.authentication.domain.interactor

import com.android.internal.widget.LockPatternView
import com.android.internal.widget.LockscreenCredential
import com.android.systemui.authentication.data.repository.AuthenticationRepository
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.authentication.shared.model.AuthenticationThrottlingModel
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.user.data.repository.UserRepository
import com.android.systemui.util.time.SystemClock
import javax.inject.Inject
import kotlin.math.max
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Hosts application business logic related to authentication. */
@SysUISingleton
class AuthenticationInteractor
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    private val repository: AuthenticationRepository,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val userRepository: UserRepository,
    private val clock: SystemClock,
) {
    /**
     * Whether the device is unlocked.
     *
     * A device that is not yet unlocked requires unlocking by completing an authentication
     * challenge according to the current authentication method.
     *
     * Note that this state has no real bearing on whether the lock screen is showing or dismissed.
     */
    val isUnlocked: StateFlow<Boolean> =
        repository.isUnlocked
            .map { isUnlocked ->
                if (getAuthenticationMethod() is AuthenticationMethodModel.None) {
                    true
                } else {
                    isUnlocked
                }
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = true,
            )

    /**
     * Whether lock screen bypass is enabled. When enabled, the lock screen will be automatically
     * dismisses once the authentication challenge is completed. For example, completing a biometric
     * authentication challenge via face unlock or fingerprint sensor can automatically bypass the
     * lock screen.
     */
    val isBypassEnabled: StateFlow<Boolean> = repository.isBypassEnabled

    /** The current authentication throttling state, only meaningful if [isThrottled] is `true`. */
    val throttling: StateFlow<AuthenticationThrottlingModel> = repository.throttling

    /**
     * Whether currently throttled and the user has to wait before being able to try another
     * authentication attempt.
     */
    val isThrottled: StateFlow<Boolean> =
        throttling
            .map { it.remainingMs > 0 }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = throttling.value.remainingMs > 0,
            )

    /** The length of the hinted PIN, or `null` if pin length hint should not be shown. */
    val hintedPinLength: StateFlow<Int?> =
        flow { emit(repository.getPinLength()) }
            .map { currentPinLength ->
                // Hinting is enabled for 6-digit codes only
                currentPinLength.takeIf { repository.hintedPinLength == it }
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = null,
            )

    /** Whether the auto confirm feature is enabled for the currently-selected user. */
    val isAutoConfirmEnabled: StateFlow<Boolean> = repository.isAutoConfirmEnabled

    /** Whether the pattern should be visible for the currently-selected user. */
    val isPatternVisible: StateFlow<Boolean> = repository.isPatternVisible

    private var throttlingCountdownJob: Job? = null

    init {
        applicationScope.launch {
            userRepository.selectedUserInfo
                .map { it.id }
                .distinctUntilChanged()
                .collect { onSelectedUserChanged() }
        }
    }

    /**
     * Returns the currently-configured authentication method. This determines how the
     * authentication challenge is completed in order to unlock an otherwise locked device.
     */
    suspend fun getAuthenticationMethod(): AuthenticationMethodModel {
        val authMethod = repository.getAuthenticationMethod()
        return if (
            authMethod is AuthenticationMethodModel.None && repository.isLockscreenEnabled()
        ) {
            // We treat "None" as "Swipe" when the lockscreen is enabled.
            AuthenticationMethodModel.Swipe
        } else {
            authMethod
        }
    }

    /**
     * Returns `true` if the device currently requires authentication before content can be viewed;
     * `false` if content can be displayed without unlocking first.
     */
    suspend fun isAuthenticationRequired(): Boolean {
        return !isUnlocked.value && getAuthenticationMethod().isSecure
    }

    /**
     * Attempts to authenticate the user and unlock the device.
     *
     * If [tryAutoConfirm] is `true`, authentication is attempted if and only if the auth method
     * supports auto-confirming, and the input's length is at least the code's length. Otherwise,
     * `null` is returned.
     *
     * @param input The input from the user to try to authenticate with. This can be a list of
     *   different things, based on the current authentication method.
     * @param tryAutoConfirm `true` if called while the user inputs the code, without an explicit
     *   request to validate.
     * @return `true` if the authentication succeeded and the device is now unlocked; `false` when
     *   authentication failed, `null` if the check was not performed.
     */
    suspend fun authenticate(input: List<Any>, tryAutoConfirm: Boolean = false): Boolean? {
        if (input.isEmpty()) {
            throw IllegalArgumentException("Input was empty!")
        }

        val skipCheck =
            when {
                // We're being throttled, the UI layer should not have called this; skip the
                // attempt.
                isThrottled.value -> true
                // Auto-confirm attempt when the feature is not enabled; skip the attempt.
                tryAutoConfirm && !isAutoConfirmEnabled.value -> true
                // Auto-confirm should skip the attempt if the pin entered is too short.
                tryAutoConfirm && input.size < repository.getPinLength() -> true
                else -> false
            }
        if (skipCheck) {
            return null
        }

        // Attempt to authenticate:
        val authMethod = getAuthenticationMethod()
        val credential = authMethod.createCredential(input) ?: return null
        val authenticationResult = repository.checkCredential(credential)
        credential.zeroize()

        if (authenticationResult.isSuccessful || !tryAutoConfirm) {
            repository.reportAuthenticationAttempt(
                isSuccessful = authenticationResult.isSuccessful,
            )
        }

        // Check if we need to throttle and, if so, kick off the throttle countdown:
        if (!authenticationResult.isSuccessful && authenticationResult.throttleDurationMs > 0) {
            repository.setThrottleDuration(
                durationMs = authenticationResult.throttleDurationMs,
            )
            startThrottlingCountdown()
        }

        if (authenticationResult.isSuccessful) {
            // Since authentication succeeded, we should refresh throttling to make sure that our
            // state is completely reflecting the upstream source of truth.
            refreshThrottling()
        }

        return authenticationResult.isSuccessful
    }

    /** See [isBypassEnabled]. */
    fun toggleBypassEnabled() {
        repository.setBypassEnabled(!repository.isBypassEnabled.value)
    }

    /** Starts refreshing the throttling state every second. */
    private suspend fun startThrottlingCountdown() {
        cancelCountdown()
        throttlingCountdownJob =
            applicationScope.launch {
                while (refreshThrottling() > 0) {
                    delay(1.seconds.inWholeMilliseconds)
                }
            }
    }

    /** Cancels any throttling state countdown started in [startThrottlingCountdown]. */
    private fun cancelCountdown() {
        throttlingCountdownJob?.cancel()
        throttlingCountdownJob = null
    }

    /** Notifies that the currently-selected user has changed. */
    private suspend fun onSelectedUserChanged() {
        cancelCountdown()
        if (refreshThrottling() > 0) {
            startThrottlingCountdown()
        }
    }

    /**
     * Refreshes the throttling state, hydrating the repository with the latest state.
     *
     * @return The remaining time for the current throttling countdown, in milliseconds or `0` if
     *   not being throttled.
     */
    private suspend fun refreshThrottling(): Long {
        return withContext(backgroundDispatcher) {
            val failedAttemptCount = async { repository.getFailedAuthenticationAttemptCount() }
            val deadline = async { repository.getThrottlingEndTimestamp() }
            val remainingMs = max(0, deadline.await() - clock.elapsedRealtime())
            repository.setThrottling(
                AuthenticationThrottlingModel(
                    failedAttemptCount = failedAttemptCount.await(),
                    remainingMs = remainingMs.toInt(),
                ),
            )
            remainingMs
        }
    }

    private fun AuthenticationMethodModel.createCredential(
        input: List<Any>
    ): LockscreenCredential? {
        return when (this) {
            is AuthenticationMethodModel.Pin ->
                LockscreenCredential.createPin(input.joinToString(""))
            is AuthenticationMethodModel.Password ->
                LockscreenCredential.createPassword(input.joinToString(""))
            is AuthenticationMethodModel.Pattern ->
                LockscreenCredential.createPattern(
                    input
                        .map { it as AuthenticationMethodModel.Pattern.PatternCoordinate }
                        .map { LockPatternView.Cell.of(it.y, it.x) }
                )
            else -> null
        }
    }
}
