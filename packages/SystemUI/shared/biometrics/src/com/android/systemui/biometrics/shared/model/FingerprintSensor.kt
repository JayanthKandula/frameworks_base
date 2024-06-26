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

package com.android.systemui.biometrics.shared.model

import android.hardware.fingerprint.FingerprintSensorPropertiesInternal

/** Fingerprint sensor property. Represents [FingerprintSensorPropertiesInternal]. */
data class FingerprintSensor(
    val sensorId: Int,
    val sensorStrength: SensorStrength,
    val maxEnrollmentsPerUser: Int,
    val sensorType: FingerprintSensorType
)

/** Convert [FingerprintSensorPropertiesInternal] to corresponding [FingerprintSensor] */
fun FingerprintSensorPropertiesInternal.toFingerprintSensor(): FingerprintSensor {
    val sensorStrength: SensorStrength = this.sensorStrength.toSensorStrength()
    val sensorType: FingerprintSensorType = this.sensorType.toSensorType()
    return FingerprintSensor(this.sensorId, sensorStrength, this.maxEnrollmentsPerUser, sensorType)
}
