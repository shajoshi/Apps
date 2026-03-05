package com.sj.gpsutil.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sj.gpsutil.tracking.CalibrateParams

@Composable
fun CalibrateParametersDialog(
    trackName: String,
    initialParams: CalibrateParams,
    onAnalyze: (CalibrateParams) -> Unit,
    onDismiss: () -> Unit
) {
    var smoothPct by remember { mutableStateOf(initialParams.smoothTargetPct.toString()) }
    var roughPct by remember { mutableStateOf(initialParams.roughTargetPct.toString()) }
    var bumpTarget by remember { mutableStateOf(initialParams.bumpTarget.toString()) }
    var potholeTarget by remember { mutableStateOf(initialParams.potholeTarget.toString()) }
    var minSpeed by remember { mutableStateOf(initialParams.minSpeedKmph.toString()) }
    var hardBrake by remember { mutableStateOf(initialParams.hardBrakeTarget.toString()) }
    var hardAccel by remember { mutableStateOf(initialParams.hardAccelTarget.toString()) }
    var swerve by remember { mutableStateOf(initialParams.swerveTarget.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Calibrate Thresholds", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = trackName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Road Quality Targets",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = smoothPct,
                        onValueChange = { smoothPct = it },
                        label = { Text("Smooth %") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = roughPct,
                        onValueChange = { roughPct = it },
                        label = { Text("Rough %") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
                Text(
                    text = "Feature Targets",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = bumpTarget,
                        onValueChange = { bumpTarget = it },
                        label = { Text("Bumps") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = potholeTarget,
                        onValueChange = { potholeTarget = it },
                        label = { Text("Potholes") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
                Text(
                    text = "Driver Event Targets",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = hardBrake,
                        onValueChange = { hardBrake = it },
                        label = { Text("Hard Brakes") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = hardAccel,
                        onValueChange = { hardAccel = it },
                        label = { Text("Hard Accels") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
                OutlinedTextField(
                    value = swerve,
                    onValueChange = { swerve = it },
                    label = { Text("Swerve Events") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = minSpeed,
                    onValueChange = { minSpeed = it },
                    label = { Text("Min Speed (km/h)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val params = CalibrateParams(
                    smoothTargetPct = smoothPct.toDoubleOrNull() ?: initialParams.smoothTargetPct,
                    roughTargetPct = roughPct.toDoubleOrNull() ?: initialParams.roughTargetPct,
                    bumpTarget = bumpTarget.toIntOrNull() ?: initialParams.bumpTarget,
                    potholeTarget = potholeTarget.toIntOrNull() ?: initialParams.potholeTarget,
                    minSpeedKmph = minSpeed.toFloatOrNull() ?: initialParams.minSpeedKmph,
                    hardBrakeTarget = hardBrake.toIntOrNull() ?: initialParams.hardBrakeTarget,
                    hardAccelTarget = hardAccel.toIntOrNull() ?: initialParams.hardAccelTarget,
                    swerveTarget = swerve.toIntOrNull() ?: initialParams.swerveTarget
                )
                onAnalyze(params)
            }) {
                Text("Analyze")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
