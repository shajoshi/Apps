package com.tpmsapp.model

data class SensorConfig(
    val macAddress: String,
    val position: TyrePosition,
    val nickname: String = position.label
)
