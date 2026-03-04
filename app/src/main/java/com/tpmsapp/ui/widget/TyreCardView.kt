package com.tpmsapp.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import com.tpmsapp.R
import com.tpmsapp.databinding.ViewTyreCardBinding
import com.tpmsapp.model.TyreData

class TyreCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : CardView(context, attrs, defStyleAttr) {

    private val binding: ViewTyreCardBinding =
        ViewTyreCardBinding.inflate(LayoutInflater.from(context), this)

    fun setTyreData(data: TyreData) {
        binding.tvPosition.text = data.position.shortLabel
        binding.tvPressure.text = context.getString(R.string.pressure_format_psi, data.pressurePsi)
        binding.tvPressureKpa.text = context.getString(R.string.pressure_format_kpa, data.pressureKpa)
        binding.tvTemperature.text = context.getString(R.string.temp_format, data.temperatureCelsius)
        binding.tvBattery.text = context.getString(R.string.battery_format, data.batteryPercent)

        val alarmColor = ContextCompat.getColor(context, R.color.alarm_red)
        val normalColor = ContextCompat.getColor(context, R.color.text_primary)
        val hasAlarm = data.isAlarmLow || data.isAlarmHigh || data.isAlarmTemp

        setCardBackgroundColor(
            if (hasAlarm) ContextCompat.getColor(context, R.color.alarm_background)
            else ContextCompat.getColor(context, R.color.card_background)
        )

        binding.tvPressure.setTextColor(if (data.isAlarmLow || data.isAlarmHigh) alarmColor else normalColor)
        binding.tvTemperature.setTextColor(if (data.isAlarmTemp) alarmColor else normalColor)

        binding.tvAlarm.visibility = if (hasAlarm) android.view.View.VISIBLE else android.view.View.GONE
        val alarmText = buildList {
            if (data.isAlarmLow)  add(context.getString(R.string.alarm_low_pressure))
            if (data.isAlarmHigh) add(context.getString(R.string.alarm_high_pressure))
            if (data.isAlarmTemp) add(context.getString(R.string.alarm_high_temp))
        }.joinToString(" | ")
        binding.tvAlarm.text = alarmText

        binding.tvNoData.visibility = android.view.View.GONE
        binding.dataGroup.visibility = android.view.View.VISIBLE
    }

    fun setNoData() {
        binding.tvNoData.visibility = android.view.View.VISIBLE
        binding.dataGroup.visibility = android.view.View.GONE
        binding.tvAlarm.visibility = android.view.View.GONE
        setCardBackgroundColor(ContextCompat.getColor(context, R.color.card_background))
    }
}
