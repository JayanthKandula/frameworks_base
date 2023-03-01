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

package com.android.systemui.keyguard.ui.viewmodel

import com.android.systemui.animation.Interpolators.EMPHASIZED_ACCELERATE
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.interactor.FromPrimaryBouncerTransitionInteractor.Companion.TO_GONE_DURATION
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.ui.KeyguardTransitionAnimationFlow
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.Flow

/**
 * Breaks down PRIMARY_BOUNCER->GONE transition into discrete steps for corresponding views to
 * consume.
 */
@SysUISingleton
class PrimaryBouncerToGoneTransitionViewModel
@Inject
constructor(
    private val interactor: KeyguardTransitionInteractor,
) {
    private val transitionAnimation =
        KeyguardTransitionAnimationFlow(
            transitionDuration = TO_GONE_DURATION,
            transitionFlow = interactor.primaryBouncerToGoneTransition,
        )

    /** Bouncer container alpha */
    val bouncerAlpha: Flow<Float> =
        transitionAnimation.createFlow(
            duration = 200.milliseconds,
            onStep = { 1f - it },
        )

    /** Scrim alpha */
    val scrimAlpha: Flow<Float> =
        transitionAnimation.createFlow(
            duration = TO_GONE_DURATION,
            interpolator = EMPHASIZED_ACCELERATE,
            onStep = { 1f - it },
        )
}
