classdef OBD_Calculators
    % OBD_Calculators: Vectorized Math Engine for OBD2 Analytics
    % Applies 3-Tier Fuel Fallback and Thermodynamic Power calculations
    % across the entire timetable simultaneously.
    
    methods (Static)
        
        function T_out = applyAll(T, Profile)
            % APPLYALL Runs all advanced calculations and appends them to the Timetable.
            % Usage: TripData = OBD_Calculators.applyAll(TripData, Profile);
            
            fprintf('Applying Vectorized Calculations...\n');
            T_out = T; % Copy the table
            numRows = height(T_out);
            
            %% 1. Profile Defaults Extraction
            % Extract profile values with safe defaults if missing
            fuelType = OBD_Calculators.getSafely(Profile, 'fuelType', 'PETROL');
            disp_cc = OBD_Calculators.getSafely(Profile, 'engineDisplacementCc', 1999);
            ve_pct = OBD_Calculators.getSafely(Profile, 'volumetricEfficiencyPct', 85.0);
            energyDensity = OBD_Calculators.getSafely(Profile, 'energyDensityMJpL', 34.2);
            
            % Fuel Constants based on type
            if strcmpi(fuelType, 'DIESEL')
                mafMlPerGram = 0.0827;
                energyDensity = 38.6; % Override with standard diesel if missing
            else
                mafMlPerGram = 0.09195; % Default Petrol
            end
            
            %% 2. Safe Column Extraction
            % We use safe extraction because Custom PIDs mean schemas vary!
            rpm = OBD_Calculators.getColSafe(T_out, 'obd_rpm', NaN);
            map_kpa = OBD_Calculators.getColSafe(T_out, 'obd_intakeMapKpa', NaN);
            iat_c = OBD_Calculators.getColSafe(T_out, 'obd_intakeTempC', 20); % Default 20C
            maf_gs = OBD_Calculators.getColSafe(T_out, 'obd_mafGs', NaN);
            baro_kpa = OBD_Calculators.getColSafe(T_out, 'obd_baroPressureKpa', 101.3);
            load_pct = OBD_Calculators.getColSafe(T_out, 'obd_engineLoadPct', 50);
            
            % Check both common locations for the direct fuel rate PID
            fuel_pid = OBD_Calculators.getColSafe(T_out, 'obd_fuelRateLh', NaN);
            if all(isnan(fuel_pid))
                fuel_pid = OBD_Calculators.getColSafe(T_out, 'fuel_fuelRateEffectiveLh', NaN);
            end

            %% 3. Tier 3: Speed-Density MAF Estimation
            R = 287.05;
            iat_k = iat_c + 273.15;
            disp_l = disp_cc / 1000.0;
            ve_decimal = ve_pct / 100.0;
            
            % Vectorized calculation for the entire column
            sd_maf_gs = (map_kpa .* 1000 .* disp_l .* rpm .* ve_decimal) ./ (R .* iat_k .* 120);
            sd_maf_gs(sd_maf_gs < 0) = 0;
            
            %% 4. Tier 2: Blend MAF (Use physical MAF if available, else SD)
            effective_maf_gs = maf_gs;
            
            % Where physical MAF is missing/NaN, fill in with Speed-Density
            missingMaf = isnan(effective_maf_gs);
            effective_maf_gs(missingMaf) = sd_maf_gs(missingMaf);
            
            %% 5. Calculate Fuel Rate (L/h)
            calc_fuel_lh = NaN(numRows, 1);
            
            if strcmpi(fuelType, 'DIESEL')
                % Vectorized Diesel AFR Logic (Torque Pro MJD Style)
                afr = 50 * ones(numRows, 1);       % Heavy Acceleration
                afr(load_pct < 70) = 75;           % Highway Cruise
                afr(load_pct < 35) = 100;          % Idle / Coast
                
                % Convert to L/h with 0.25 correction factor
                fuel_gps = effective_maf_gs ./ afr;
                calc_fuel_lh = (fuel_gps .* 3.6 ./ mafMlPerGram) .* 0.25;
            else
                % Standard Petrol Calculation
                calc_fuel_lh = (effective_maf_gs .* 3.6) ./ (mafMlPerGram .* 1000);
            end
            
            %% 6. Tier 1: Direct PID Override
            % If the car actually provided a direct fuel rate, use that instead!
            hasDirectPid = ~isnan(fuel_pid) & fuel_pid > 0;
            calc_fuel_lh(hasDirectPid) = fuel_pid(hasDirectPid);
            
            % Ensure no negative fuel rates
            calc_fuel_lh(calc_fuel_lh < 0) = 0;
            
            %% 7. Thermodynamic Power Calculation
            thermal_efficiency = 0.35;
            
            % Convert MJ/h to kW: (L/h * MJ/L * 1000 / 3600) * efficiency
            energy_rate_mj_ph = calc_fuel_lh .* energyDensity;
            power_kw = (energy_rate_mj_ph .* 1000.0 ./ 3600.0) .* thermal_efficiency;
            power_bhp = power_kw .* 1.341;
            
            %% 8. Append Results to Timetable
            T_out.Calculated_FuelRate_Lh = calc_fuel_lh;
            T_out.Calculated_Power_kW = power_kw;
            T_out.Calculated_Power_BHP = power_bhp;
            
            fprintf('✓ Successfully appended Fuel and Power calculations.\n');
        end
        
        %% --- Internal Helpers ---
        
        function col = getColSafe(T, colName, defaultVal)
            % Safely extracts a column if it exists, otherwise returns a vector of defaults
            if ismember(colName, T.Properties.VariableNames)
                col = double(T.(colName));
            else
                col = repmat(defaultVal, height(T), 1);
            end
        end
        
        function val = getSafely(structData, fieldName, defaultVal)
            % Safely extracts a struct property
            if isfield(structData, fieldName) && ~isempty(structData.(fieldName))
                val = structData.(fieldName);
            else
                val = defaultVal;
            end
        end
        
    end
end