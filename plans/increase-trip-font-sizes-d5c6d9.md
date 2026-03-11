# Increase Trip Screen Font Sizes by 40%

Update all TextView textSize attributes in the Trip screen layout (fragment_trip.xml) by increasing each current size by 40%. This will make all displayed text in the Trip screen more readable by scaling up the font sizes proportionally.

## Changes Required

**File: `c:\Users\ShaileshJoshi\AndroidStudioProjects\Apps\OBD2App\app\src\main\res\layout\fragment_trip.xml`**

- Update TextView with text="SENSOR READINESS" from 11sp to 15.4sp (rounded to 15sp)
- Update TextView with text="OBD2" from 13sp to 18.2sp (rounded to 18sp)  
- Update TextView with id="tv_obd_status" from 13sp to 18.2sp (rounded to 18sp)
- Update TextView with text="GPS" from 13sp to 18.2sp (rounded to 18sp)
- Update TextView with id="tv_gps_status" from 13sp to 18.2sp (rounded to 18sp)
- Update TextView with id="tv_gps_detail" from 11sp to 15.4sp (rounded to 15sp)
- Update TextView with text="Accel" from 13sp to 18.2sp (rounded to 18sp)
- Update TextView with id="tv_accel_status" from 13sp to 18.2sp (rounded to 18sp)
- Update TextView with id="tv_accel_power" from 11sp to 15.4sp (rounded to 15sp)
- Update TextView with text="Logging" from 13sp to 18.2sp (rounded to 18sp)
- Update TextView with id="tv_logging_status" from 13sp to 18.2sp (rounded to 18sp)
- Update TextView with text="TRIP" from 11sp to 15.4sp (rounded to 15sp)
- Update TextView with text="Phase" from 11sp to 15.4sp (rounded to 15sp)
- Update TextView with id="tv_trip_phase" from 15sp to 21sp
- Update TextView with text="Samples" from 11sp to 15.4sp (rounded to 15sp)
- Update TextView with id="tv_trip_samples" from 15sp to 21sp
- Update TextView with text="Duration" from 11sp to 15.4sp (rounded to 15sp)
- Update TextView with id="tv_trip_duration" from 15sp to 21sp
- Update TextView with text="Distance" from 11sp to 15.4sp (rounded to 15sp)
- Update TextView with id="tv_trip_distance" from 15sp to 21sp
- Update TextView with text="Fuel Cost" from 11sp to 15.4sp (rounded to 15sp)
- Update TextView with id="tv_fuel_cost" from 15sp to 21sp
- Update TextView with text="Idle %" from 11sp to 15.4sp (rounded to 15sp)
- Update TextView with id="tv_idle_percent" from 15sp to 21sp
- Update TextView with text="ORIENTATION" from 11sp to 15.4sp (rounded to 15sp)
- Update TextView with id="tv_gravity_values" from 14sp to 19.6sp (rounded to 20sp)
- Update TextView with id="tv_gravity_magnitude" from 13sp to 18.2sp (rounded to 18sp)
- Update TextView with id="tv_gravity_label" from 12sp to 16.8sp (rounded to 17sp)
- Update MaterialButton with text="Start" from 14sp to 19.6sp (rounded to 20sp)
- Update MaterialButton with text="Pause" from 14sp to 19.6sp (rounded to 20sp)
- Update MaterialButton with text="Stop" from 14sp to 19.6sp (rounded to 20sp)

## Notes
- Rounding to nearest whole sp value for consistency
- All TextViews and Buttons in the Trip screen layout will be updated
- No programmatic changes required - all sizes defined in XML layout
