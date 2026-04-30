classdef OBD_AnalyticsLab_Update
    % OBD_AnalyticsLab_Update: Master orchestrator for OBD2 Deep Analytics.
    % PATCH 28: Eradicated fragile stack() functions. Built custom JSON unroller.
    
    methods (Static)
        
        function run()
            % Allow multiselection of ANY file type (bypasses extension hiding)
            [files, path] = uigetfile({'*.*', 'All Log Files (*.*)'}, ...
                                      'Select ALL Log Files (Hold Ctrl to multiselect)', 'MultiSelect', 'on');
            if isequal(files, 0), disp('Canceled.'); return; end
            if ischar(files), files = {files}; end 
            
            fprintf('\n=== Starting Multi-Modal Data Fusion ===\n');
            T_long_master = table();
            
            for f = 1:length(files)
                filename = fullfile(path, files{f});
                
                % CONTENT SNIFFING: Read the first 150 chars to detect file type
                fid = fopen(filename, 'r');
                if fid == -1, warning('Cannot open %s', files{f}); continue; end
                previewText = fread(fid, 150, '*char')';
                fclose(fid);
                
                try
                    if contains(previewText, '"ext":') && contains(previewText, '"data":')
                        fprintf('Skipping %s (Raw CAN Dump superseded by Decoded Data).\n', files{f});
                        continue;
                    elseif contains(previewText, '"sig"') || contains(previewText, '{"t":')
                        fprintf('Extracting Synchronized CAN File: %s\n', files{f});
                        T_long = OBD_AnalyticsLab_Update.extractJSONL(filename);
                    elseif contains(previewText, '"header"') || contains(previewText, '"samples"')
                        fprintf('Extracting Main GPS/IMU File: %s\n', files{f});
                        T_long = OBD_AnalyticsLab_Update.extractJSON(filename);
                    else
                        warning('Unrecognized data format in file: %s', files{f});
                        continue;
                    end
                    
                    % Append to master table
                    if ~isempty(T_long)
                        T_long_master = [T_long_master; T_long]; %#ok<AGROW>
                    end
                catch ME
                    warning('Failed to extract %s: %s', files{f}, ME.message);
                end
            end
            
            if isempty(T_long_master), error('No valid data extracted from files.'); end
            
            fprintf('Pivoting %d data points into Master Timeline...\n', height(T_long_master));
            
            % Unstack handles duplicates automatically via @mean
            TripData = unstack(T_long_master, 'Value', 'Signal', 'AggregationFunction', @mean);
            TripData = sortrows(TripData, 'Time_ms');
            
            % Clean and linearize Time
            TripData.Linearized_Time = (TripData.Time_ms - TripData.Time_ms(1)) / 1000;
            TripData = movevars(TripData, 'Linearized_Time', 'Before', 1);
            
            % Fill missing data so high-speed CAN signals match slower GPS signals
            TripData = fillmissing(TripData, 'previous');
            TripData = fillmissing(TripData, 'next');
            
            % Derive Longitudinal Acceleration if missing
            spdVar = OBD_AnalyticsLab_Update.findChannel(TripData, {'VehicleSpeed', 'gps_speedKmh', 'speedKmh'});
            if ~isempty(spdVar)
                spd_ms = TripData.(spdVar) / 3.6; % Convert kph to m/s
                dt = [0.01; diff(TripData.Linearized_Time)];
                dt(dt <= 0) = 0.01; % Prevent divide by zero
                derived_accel = [0; diff(spd_ms)] ./ dt;
                TripData.Derived_Long_Accel = smoothdata(derived_accel, 'movmean', 50); 
            end
            
            fprintf('=== Pipeline Complete. Building Dashboard... ===\n');
            assignin('base', 'TripData', TripData);
            
            OBD_AnalyticsLab_Update.buildDashboard(TripData);
        end
        
        %% --- FAST EXTRACTION ENGINES ---
        function T_long = extractJSONL(filename)
            fid = fopen(filename, 'r'); raw = fread(fid, '*char')'; fclose(fid);
            lines = splitlines(strtrim(raw));
            
            N = length(lines);
            t_vals = zeros(N, 1);
            sigs = cell(N, 1);
            v_vals = zeros(N, 1);
            valid = false(N, 1);
            
            for i = 1:N
                if isempty(lines{i}), continue; end
                try
                    val = jsondecode(lines{i});
                    t_vals(i) = val.t; 
                    sigs{i} = val.sig;
                    v_vals(i) = val.v;
                    valid(i) = true;
                catch
                end
            end
            
            T_long = table(t_vals(valid), sigs(valid), v_vals(valid), ...
                'VariableNames', {'Time_ms', 'Signal', 'Value'});
        end

        function T_long = extractJSON(filename)
            % Bulletproof custom JSON unroller. Bypasses MATLAB's fragile table functions.
            rawText = fileread(filename);
            rawText = regexprep(rawText, ',\s*,', ',');
            rawText = regexprep(rawText, ',\s*\]', ']');
            
            try jsonData = jsondecode(rawText); 
            catch ME, error('JSON Sanitization failed: %s', ME.message); end
            
            if isfield(jsonData, 'samples'), samples = jsonData.samples;
            else, samples = jsonData; end
            
            N = length(samples);
            maxPts = N * 60; % Generous preallocation memory
            time_ms = zeros(maxPts, 1);
            sigs = cell(maxPts, 1);
            vals = zeros(maxPts, 1);
            
            ptr = 1;
            for i = 1:N
                if iscell(samples), s = samples{i}; else, s = samples(i); end
                if isempty(s), continue; end
                
                % Standardize Timestamp
                if isfield(s, 'timestampMs'), t = double(s.timestampMs);
                elseif isfield(s, 'time_ms'), t = double(s.time_ms);
                else, continue; end
                
                % Recursively flatten the GPS/IMU data, isolating only numbers
                [f_names, f_vals] = OBD_AnalyticsLab_Update.flattenRecord(s, '');
                
                numF = length(f_names);
                
                % Dynamic expansion if data exceeds preallocation
                if ptr + numF > maxPts
                     time_ms = [time_ms; zeros(maxPts, 1)]; %#ok<AGROW>
                     sigs = [sigs; cell(maxPts, 1)]; %#ok<AGROW>
                     vals = [vals; zeros(maxPts, 1)]; %#ok<AGROW>
                     maxPts = maxPts * 2;
                end
                
                for j = 1:numF
                    time_ms(ptr) = t;
                    sigs{ptr} = f_names{j};
                    vals(ptr) = f_vals(j);
                    ptr = ptr + 1;
                end
            end
            
            T_long = table(time_ms(1:ptr-1), sigs(1:ptr-1), vals(1:ptr-1), ...
                'VariableNames', {'Time_ms', 'Signal', 'Value'});
        end

        function [fields, vals] = flattenRecord(s, prefix)
            % Recursively navigates nested JSON structs to extract only numeric values
            fields = {}; vals = [];
            if isstruct(s)
                fn = fieldnames(s);
                for i = 1:length(fn)
                    f = fn{i}; v = s.(f);
                    if isstruct(v)
                        [subF, subV] = OBD_AnalyticsLab_Update.flattenRecord(v, [prefix f '_']);
                        fields = [fields; subF]; %#ok<AGROW>
                        vals = [vals; subV]; %#ok<AGROW>
                    elseif isnumeric(v) && isscalar(v)
                        % Ignores text fields completely preventing crash during stack
                        fields{end+1, 1} = [prefix f]; %#ok<AGROW>
                        vals(end+1, 1) = double(v); %#ok<AGROW>
                    end
                end
            end
        end
        
        %% --- MASTER DASHBOARD ---
        function buildDashboard(T)
            fig = uifigure('Name', 'Ultimate OBD Analytics Dashboard', 'Position', [100 100 1400 800], 'Color', 'w');
            tg = uitabgroup(fig, 'Position', [10 10 1380 780]);
            
            % Tab 1: Shift Diagnostics
            tab1 = uitab(tg, 'Title', 'ZF 8HP Shift Diagnostics');
            OBD_AnalyticsLab_Update.analyzeShiftQuality(T, tab1);
            
            % Tab 2: Powertrain & Fuel
            tab2 = uitab(tg, 'Title', 'Powertrain & Fuel Efficiency');
            OBD_AnalyticsLab_Update.analyzePowertrain(T, tab2);
            
            % Tab 3: NVH Ride Quality
            tab3 = uitab(tg, 'Title', 'NVH & Ride Quality (IMU)');
            OBD_AnalyticsLab_Update.analyzeNVH(T, tab3);

            % Tab 4: Vehicle Dynamics
            tab4 = uitab(tg, 'Title', 'Vehicle Dynamics (G-Circle)');
            OBD_AnalyticsLab_Update.analyzeVehicleDynamics(T, tab4);
            
            % Tab 5: Standard Telemetry
            tab5 = uitab(tg, 'Title', 'Standard Telemetry');
            OBD_AnalyticsLab_Update.plotStandardTelemetry(T, tab5);
        end
        
        %% --- SHIFT DIAGNOSTICS ---
        function analyzeShiftQuality(T, parentTab)
            rpmVar = OBD_AnalyticsLab_Update.findChannel(T, {'EngineSpeed', 'obd_rpm'});
            actVar = OBD_AnalyticsLab_Update.findChannel(T, {'GearPosActual'});
            tgtVar = OBD_AnalyticsLab_Update.findChannel(T, {'GearPosTarget'});
            tempVar = OBD_AnalyticsLab_Update.findChannel(T, {'TransOilTemp', 'EngineOilTemp'});
            
            if isempty(rpmVar) || isempty(actVar)
                uilabel(parentTab, 'Text', 'Missing RPM or Gear Data. Ensure Decoded CAN file was loaded.', 'Position', [450 400 400 50], 'FontSize', 14);
                return;
            end
            
            time = T.Linearized_Time; actGear = T.(actVar); rpm = T.(rpmVar);
            
            % Find true gears 1-8
            validIdx = find(actGear > 0 & actGear <= 8);
            if isempty(validIdx), uilabel(parentTab, 'Text', 'No valid upshifts detected.', 'Position', [500 400 300 50]); return; end
            
            validGears = actGear(validIdx);
            shiftIdx = find(diff(validGears) > 0);
            shiftScores = struct('Shift', {}, 'Temp', {}, 'FlareRpm', {}, 'ShiftDelayMs', {});
            
            for i = 1:length(shiftIdx)
                idx_start = validIdx(shiftIdx(i)); idx_end = validIdx(shiftIdx(i) + 1);
                t_start = time(idx_start); t_end = time(idx_end);
                start_gear = actGear(idx_start); end_gear = actGear(idx_end);
                
                if (end_gear - start_gear) > 2, continue; end % Skip extreme jumps
                
                delay_ms = NaN;
                if ~isempty(tgtVar)
                    target_time_idx = find(T.(tgtVar) == end_gear & time <= t_end, 1, 'first');
                    if ~isempty(target_time_idx)
                        delay_ms = (t_end - time(target_time_idx)) * 1000;
                        if delay_ms < 0 || delay_ms > 2000, delay_ms = NaN; end 
                    end
                end
                
                windowMask = time >= (t_start - 0.5) & time <= (t_end + 1.0);
                rpm_win = rpm(windowMask);
                flare = 0; if length(rpm_win) > 1, flare = max(0, max(diff(rpm_win))); end
                temp = NaN; if ~isempty(tempVar), temp = mean(T.(tempVar)(windowMask), 'omitnan'); end
                
                shiftScores(end+1) = struct('Shift', sprintf('%d->%d', start_gear, end_gear), 'Temp', temp, 'FlareRpm', flare, 'ShiftDelayMs', delay_ms);
            end
            
            if isempty(shiftScores)
                 uilabel(parentTab, 'Text', 'No shifts occurred in this dataset.', 'Position', [500 400 300 50]); return;
            end
            
            temps = [shiftScores.Temp]; flares = [shiftScores.FlareRpm]; delays = [shiftScores.ShiftDelayMs]; 
            shifts = categorical({shiftScores.Shift});
            
            tl = tiledlayout(parentTab, 2, 2, 'TileSpacing', 'compact');
            
            ax1 = nexttile(tl);
            scatter(ax1, double(shifts), flares, 60, temps, 'filled', 'MarkerEdgeColor', 'k');
            xticks(ax1, 1:length(categories(shifts))); xticklabels(ax1, categories(shifts));
            colormap(ax1, hot); cb1 = colorbar(ax1); cb1.Label.String = 'Temp (°C)';
            title(ax1, 'Hesitation (RPM Spike) by Gear', 'FontWeight', 'bold'); ylabel(ax1, 'RPM Spike'); grid(ax1, 'on');
            
            ax2 = nexttile(tl);
            scatter(ax2, double(shifts), delays, 60, temps, 'filled', 'MarkerEdgeColor', 'k');
            xticks(ax2, 1:length(categories(shifts))); xticklabels(ax2, categories(shifts));
            colormap(ax2, hot); cb2 = colorbar(ax2); cb2.Label.String = 'Temp (°C)';
            title(ax2, 'Mechanical Shift Delay (Target to Actual)', 'FontWeight', 'bold'); ylabel(ax2, 'Delay (ms)'); grid(ax2, 'on');
            
            ax3 = nexttile(tl, [1 2]);
            scatter(ax3, temps, delays, 80, flares, 'filled', 'MarkerEdgeColor', 'k');
            colormap(ax3, hot); cb3 = colorbar(ax3); cb3.Label.String = 'RPM Spike';
            title(ax3, 'Temperature vs Shift Delay (Does Cold Fluid cause lag?)', 'FontWeight', 'bold'); 
            xlabel(ax3, 'Transmission Temp (°C)'); ylabel(ax3, 'Shift Delay (ms)'); grid(ax3, 'on');
        end

        %% --- POWERTRAIN, TURBO & FUEL ---
        function analyzePowertrain(T, parentTab)
            rpmVar = OBD_AnalyticsLab_Update.findChannel(T, {'EngineSpeed', 'obd_rpm'});
            trqVar = OBD_AnalyticsLab_Update.findChannel(T, {'EngineTorqFlywheelAct', 'Torque'});
            actBoostVar = OBD_AnalyticsLab_Update.findChannel(T, {'ManifoldPressure', 'MAP_Act'});
            fuelVar = OBD_AnalyticsLab_Update.findChannel(T, {'FuelConsumption_HS'});
            spdVar = OBD_AnalyticsLab_Update.findChannel(T, {'VehicleSpeed', 'gps_speedKmh'});
            
            tl = tiledlayout(parentTab, 3, 1, 'TileSpacing', 'compact'); time = T.Linearized_Time;
            
            ax1 = nexttile(tl); hold(ax1, 'on'); yyaxis(ax1, 'left');
            if ~isempty(rpmVar), plot(ax1, time, T.(rpmVar), 'k', 'LineWidth', 1.5, 'DisplayName', 'RPM'); ylabel(ax1, 'RPM'); end
            yyaxis(ax1, 'right');
            if ~isempty(trqVar), plot(ax1, time, T.(trqVar), 'b', 'LineWidth', 1.5, 'DisplayName', 'Torque'); ylabel(ax1, 'Torque (Nm)'); end
            title(ax1, 'Engine Demand', 'FontWeight', 'bold'); grid(ax1, 'on');
            
            ax2 = nexttile(tl); hold(ax2, 'on');
            if ~isempty(actBoostVar), plot(ax2, time, T.(actBoostVar), 'b', 'LineWidth', 1.5); title(ax2, 'Turbo Actuation', 'FontWeight', 'bold'); ylabel(ax2, 'Pressure'); end
            grid(ax2, 'on');
            
            ax3 = nexttile(tl); hold(ax3, 'on');
            if ~isempty(fuelVar) && ~isempty(spdVar)
                delta_ml = [0; diff(T.(fuelVar))]; delta_ml(delta_ml < 0) = 0;
                spd_kph = T.(spdVar); spd_kph(spd_kph < 1) = NaN;
                dist_km = spd_kph .* (0.01 / 3600);
                
                L_100km = (movmean(delta_ml, 200) / 1000) ./ movmean(dist_km, 200) * 100;
                L_100km(L_100km > 30) = 30;
                
                yyaxis(ax3, 'left'); plot(ax3, time, L_100km, 'g', 'LineWidth', 1.5); ylabel(ax3, 'L/100km');
                yyaxis(ax3, 'right'); plot(ax3, time, cumsum(delta_ml)/1000, 'm', 'LineWidth', 2); ylabel(ax3, 'Cumulative Liters');
                title(ax3, 'Fuel Economy & Efficiency', 'FontWeight', 'bold');
            else
                title(ax3, 'Fuel Data Unavailable', 'FontWeight', 'bold');
            end
            grid(ax3, 'on'); xlabel(ax3, 'Time (s)'); linkaxes([ax1, ax2, ax3], 'x');
        end

        %% --- NVH & RIDE QUALITY ---
        function analyzeNVH(T, parentTab)
            rmsVar = OBD_AnalyticsLab_Update.findChannel(T, {'accel_vertRms', 'vertRms'});
            maxVar = OBD_AnalyticsLab_Update.findChannel(T, {'accel_vertMax', 'vertMax'});
            spdVar = OBD_AnalyticsLab_Update.findChannel(T, {'VehicleSpeed', 'gps_speedKmh'});
            
            if isempty(rmsVar) || isempty(spdVar)
                uilabel(parentTab, 'Text', 'Missing Phone IMU (Accelerometer) or Speed Data.', 'Position', [500 400 400 50], 'FontSize', 14);
                return;
            end
            
            tl = tiledlayout(parentTab, 2, 1, 'TileSpacing', 'compact'); time = T.Linearized_Time;
            
            ax1 = nexttile(tl); hold(ax1, 'on');
            plot(ax1, time, T.(rmsVar), 'b', 'LineWidth', 1.5, 'DisplayName', 'Vertical RMS (Roughness)');
            if ~isempty(maxVar), plot(ax1, time, T.(maxVar), 'r', 'LineWidth', 1.0, 'DisplayName', 'Vertical Max (Potholes)'); end
            title(ax1, 'Suspension Harshness (IMU NVH)', 'FontWeight', 'bold'); ylabel(ax1, 'G-Force'); legend(ax1, 'Location', 'best'); grid(ax1, 'on');
            
            ax2 = nexttile(tl);
            scatter(ax2, T.(spdVar), T.(rmsVar), 15, time, 'filled'); colormap(ax2, jet); colorbar(ax2);
            title(ax2, 'Speed vs. Road Roughness', 'FontWeight', 'bold'); xlabel(ax2, 'Speed (km/h)'); ylabel(ax2, 'Vertical RMS'); grid(ax2, 'on');
        end

        %% --- VEHICLE DYNAMICS ---
        function analyzeVehicleDynamics(T, parentTab)
            latVar = OBD_AnalyticsLab_Update.findChannel(T, {'accel_latMax', 'LateralAcceleration'});
            spdVar = OBD_AnalyticsLab_Update.findChannel(T, {'VehicleSpeed', 'gps_speedKmh'});
            
            if isempty(latVar) || ~ismember('Derived_Long_Accel', T.Properties.VariableNames)
                uilabel(parentTab, 'Text', 'Missing Acceleration Data.', 'Position', [500 400 300 50], 'FontSize', 14); return;
            end
            
            tl = tiledlayout(parentTab, 1, 2, 'TileSpacing', 'compact');
            
            ax1 = nexttile(tl);
            latData = T.(latVar); lonData = T.Derived_Long_Accel;
            active = T.(spdVar) > 5;
            
            scatter(ax1, latData(active) / 9.81, lonData(active) / 9.81, 15, T.Linearized_Time(active), 'filled', 'MarkerFaceAlpha', 0.6);
            colormap(ax1, jet); hold(ax1, 'on'); xline(ax1, 0, 'k--'); yline(ax1, 0, 'k--');
            theta = linspace(0, 2*pi, 100); plot(ax1, cos(theta), sin(theta), 'r-', 'LineWidth', 1.5);
            title(ax1, 'Traction Circle (Derived G-Force)', 'FontWeight', 'bold'); xlabel(ax1, 'Lateral G'); ylabel(ax1, 'Longitudinal G');
            axis(ax1, 'equal'); grid(ax1, 'on'); xlim(ax1, [-1.5 1.5]); ylim(ax1, [-1.5 1.5]);
            
            ax2 = nexttile(tl);
            plot(ax2, T.Linearized_Time, T.(spdVar), 'b', 'LineWidth', 2);
            title(ax2, 'Speed Profile', 'FontWeight', 'bold'); xlabel(ax2, 'Time (s)'); ylabel(ax2, 'Speed (km/h)'); grid(ax2, 'on');
        end
        
        %% --- STANDARD TELEMETRY ---
        function plotStandardTelemetry(T, parentTab)
            timeData = T.Linearized_Time; vars = T.Properties.VariableNames;
            excludeCols = {'Linearized_Time', 'Time_ms', 'Derived_Long_Accel'};
            sensorNames = vars(~ismember(vars, excludeCols));
            validSensors = {};
            for p = 1:length(sensorNames)
                if isnumeric(T.(sensorNames{p})) && ~all(isnan(T.(sensorNames{p}))), validSensors{end+1} = sensorNames{p}; end
            end
            
            if isempty(validSensors)
                uilabel(parentTab, 'Text', 'No valid numeric sensor data found.', 'Position', [500 400 300 50]); return;
            end
            
            numPlots = min(length(validSensors), 6); 
            tl = tiledlayout(parentTab, numPlots, 1, 'TileSpacing', 'compact');
            for p = 1:numPlots
                sName = validSensors{p}; ax = nexttile(tl);
                plot(ax, timeData, T.(sName), 'LineWidth', 1.5, 'Color', [0 0.4470 0.7410]);
                title(ax, strrep(sName, '_', ' '), 'FontSize', 10, 'FontWeight', 'bold'); grid(ax, 'on');
            end
            xlabel(ax, 'Time (seconds)', 'FontSize', 12, 'FontWeight', 'bold');
        end
        
        %% --- UTILITY ---
        function colName = findChannel(T, keywords)
            vars = T.Properties.VariableNames; colName = '';
            for i = 1:length(keywords)
                idx = find(strcmpi(vars, keywords{i}), 1); 
                if isempty(idx), idx = find(contains(lower(vars), lower(keywords{i})), 1); end
                if ~isempty(idx), colName = vars{idx}; return; end
            end
        end
    end
end