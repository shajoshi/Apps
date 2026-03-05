package com.sj.gpsutil.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sj.gpsutil.data.CalibrationSettings
import com.sj.gpsutil.data.DriverThresholdSettings
import com.sj.gpsutil.tracking.ThresholdRecommendation
import java.util.Locale

@Composable
fun ThresholdRecommendationDialog(
    trackName: String,
    recommendation: ThresholdRecommendation,
    currentProfileName: String?,
    currentCalibration: CalibrationSettings,
    currentDriverThresholds: DriverThresholdSettings,
    availableProfiles: List<String>,
    isApplying: Boolean,
    onApplyToCurrent: () -> Unit,
    onApplyToProfile: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val locale = Locale.getDefault()
    var showProfilePicker by remember { mutableStateOf(false) }

    if (showProfilePicker) {
        ProfilePickerDialog(
            profiles = availableProfiles,
            onSelect = { name ->
                showProfilePicker = false
                onApplyToProfile(name)
            },
            onDismiss = { showProfilePicker = false }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Threshold Recommendations", fontWeight = FontWeight.SemiBold)
                Text(
                    text = trackName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Summary
                Text(
                    "Analysis Summary",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text("Sampling rate: %.0f Hz".format(locale, recommendation.samplingRateHz))
                Text("Fixes analyzed: ${recommendation.totalFixes}")
                Text("Vert samples: ${recommendation.totalVertSamples}")
                Text("Peak Threshold Z: %.2f".format(locale, recommendation.recommendedPeakZ))
                Text("Smooth: %.1f%%".format(locale, recommendation.achievedSmoothPct))
                Text("Rough: %.1f%%".format(locale, recommendation.achievedRoughPct))
                Text("Bumps: ${recommendation.bumpCount}  Potholes: ${recommendation.potholeCount}")

                Spacer(Modifier.height(4.dp))
                HorizontalDivider()

                // Road Quality Thresholds
                Text(
                    "Road Quality Thresholds",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp)
                )
                ThresholdRow("RMS Smooth Max", currentCalibration.rmsSmoothMax, recommendation.recommended.rmsSmoothMax, locale)
                ThresholdRow("StdDev Smooth Max", currentCalibration.stdDevSmoothMax, recommendation.recommended.stdDevSmoothMax, locale)
                ThresholdRow("RMS Rough Min", currentCalibration.rmsRoughMin, recommendation.recommended.rmsRoughMin, locale)
                ThresholdRow("StdDev Rough Min", currentCalibration.stdDevRoughMin, recommendation.recommended.stdDevRoughMin, locale)
                ThresholdRow("MagMax Severe Min", currentCalibration.magMaxSevereMin, recommendation.recommended.magMaxSevereMin, locale)
                ThresholdRow("Peak Threshold Z", currentCalibration.peakThresholdZ, recommendation.recommended.peakThresholdZ, locale)

                Spacer(Modifier.height(4.dp))
                HorizontalDivider()

                // Driver Thresholds
                Text(
                    "Driver Thresholds",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp)
                )
                ThresholdRow("Hard Brake Fwd Max", currentDriverThresholds.hardBrakeFwdMax, recommendation.recommendedDriver.hardBrakeFwdMax, locale)
                ThresholdRow("Hard Accel Fwd Max", currentDriverThresholds.hardAccelFwdMax, recommendation.recommendedDriver.hardAccelFwdMax, locale)
                ThresholdRow("Swerve Lat Max", currentDriverThresholds.swerveLatMax, recommendation.recommendedDriver.swerveLatMax, locale)
                ThresholdRow("Corner Lat Max", currentDriverThresholds.aggressiveCornerLatMax, recommendation.recommendedDriver.aggressiveCornerLatMax, locale)
                ThresholdRow("Min Speed (km/h)", currentDriverThresholds.minSpeedKmph, recommendation.recommendedDriver.minSpeedKmph, locale)
            }
        },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (currentProfileName != null) {
                    TextButton(
                        onClick = onApplyToCurrent,
                        enabled = !isApplying
                    ) {
                        Text("Apply to $currentProfileName")
                    }
                }
                TextButton(
                    onClick = { showProfilePicker = true },
                    enabled = !isApplying && availableProfiles.isNotEmpty()
                ) {
                    Text("Apply to…")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun ThresholdRow(label: String, current: Float, recommended: Float, locale: Locale) {
    val changed = kotlin.math.abs(current - recommended) > 0.001f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "%.2f → %.2f".format(locale, current, recommended),
            style = MaterialTheme.typography.bodySmall,
            color = if (changed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (changed) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun ProfilePickerDialog(
    profiles: List<String>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Profile", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (profiles.isEmpty()) {
                    Text("No profiles found.")
                } else {
                    profiles.forEach { name ->
                        TextButton(
                            onClick = { onSelect(name) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(name)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
