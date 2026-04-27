classdef OBD_AnalyticsLab
    % OBD_AnalyticsLab: The master orchestrator for OBD2 Deep Analytics.
    % PATCH 14: The Ultimate Edition. Pre-synced App Data, Universal DBC Parsing, 
    % Advanced Diagnostics, Dead Channel Filtering, and Shift Quality Analysis.
    
    methods (Static)
        
        function run(filename)
            if nargin < 1 || isempty(filename)
                [file, path] = uigetfile({'*.json;*.jsonl;*.htm;*.html;*.csv', 'All Log Files'}, 'Select Log File');
                if isequal(file, 0), disp('Canceled.'); return; end
                filename = fullfile(path, file);
            end
            
            %% 1. The Automated Pipeline
            fprintf('=== Starting OBD Deep Analytics Pipeline ===\n');
            fprintf('Processing: %s\n', filename);
            
            [~, name, ext] = fileparts(filename);
            
            if contains(name, 'samples') && contains(filename, '.json')
                % --- 1. CUSTOM APP (PRE-SYNCED) PIPELINE ---
                fprintf('Detected Pre-Synced App Samples. Pivoting into Table...\n');
                TripData = OBD_AnalyticsLab.parseAppSamplesSynced(filename);
                
            elseif contains(name, 'raw') && contains(filename, '.json')
                % --- 2. CUSTOM APP (RAW) + DBC PIPELINE ---
                fprintf('Detected RAW CAN Frames. You must select a DBC file to decode this.\n');
                [dbcFile, dbcPath] = uigetfile('*.dbc', 'Select DBC File for Decoding');
                if isequal(dbcFile, 0), error('DBC file required for raw frames.'); end
                TripData = OBD_AnalyticsLab.parseAppRawWithDBC(filename, fullfile(dbcPath, dbcFile));
                
            elseif strcmpi(ext, '.htm') || strcmpi(ext, '.html')
                % --- 3. RAW CAN LOG PIPELINE (Mongoose) ---
                TripData = OBD_AnalyticsLab.parseJLR_CAN(filename);
            else
                % --- 4. STANDARD ELM327 PIPELINE ---
                [TripData, Profile] = OBD_Importer.read(filename);
                TripData = OBD_Calculators.applyAll(TripData, Profile);
                
                % Force Chronological Linearity
                [raw_t_vec, ~, timeColName] = OBD_AnalyticsLab.getTimeData(TripData);
                TripData = sortrows(TripData, timeColName);
                num_samples = height(TripData);
                if num_samples > 1 && max(raw_t_vec) > 0
                    TripData.Linearized_Time = linspace(0, max(raw_t_vec), num_samples)';
                else
                    TripData.Linearized_Time = raw_t_vec;
                end
                try TripData = movevars(TripData, 'Linearized_Time', 'Before', 1); catch; end
            end
            
            % --- FILTER OUT DEAD/UNCHANGING CHANNELS ---
            TripData = OBD_AnalyticsLab.removeDeadChannels(TripData);
            
            fprintf('=== Pipeline Complete. ===\n');
            assignin('base', 'TripData', TripData);
            fprintf('✓ Successfully pushed cleaned "TripData" to your Workspace.\n');
            
            %% 2. The Main Dashboard Menu (Loop)
            keepRunning = true;
            
            while keepRunning
                choice = questdlg('Data processed. Select Analysis Suite:', ...
                               'OBD Analytics Lab', ...
                               'Standard Telemetry', 'Advanced Diagnostics...', 'Exit', 'Exit');
                           
                if isempty(choice) || strcmp(choice, 'Exit')
                    fprintf('Enjoy your data! See "TripData" in the Workspace.\n');
                    keepRunning = false;
                    
                elseif strcmp(choice, 'Standard Telemetry')
                    OBD_AnalyticsLab.plotStandardTelemetry(TripData);
                    
                elseif strcmp(choice, 'Advanced Diagnostics...')
                    analysisType = questdlg('Select Diagnostic Suite:', 'Advanced Diagnostics', ...
                        'Vehicle Dynamics (G-Circle)', 'Transmission Health (Slip)', 'ZF 8HP Deep Shift Analysis', 'Vehicle Dynamics (G-Circle)');
                    
                    switch analysisType
                        case 'Vehicle Dynamics (G-Circle)'
                            OBD_AnalyticsLab.analyzeVehicleDynamics(TripData);
                        case 'Transmission Health (Slip)'
                            OBD_AnalyticsLab.analyzeTransmissionHealth(TripData);
                        case 'ZF 8HP Deep Shift Analysis'
                            OBD_AnalyticsLab.analyzeShiftQuality(TripData);
                    end
                end
            end
        end
        
        %% --- DATA CLEANING ---
        function T_clean = removeDeadChannels(T)
            fprintf('Scanning for dead/unchanging CAN channels...\n');
            vars = T.Properties.VariableNames;
            keepCols = true(1, length(vars));
            
            for i = 1:length(vars)
                colName = vars{i};
                if strcmp(colName, 'Linearized_Time') || strcmp(colName, 'Time_ms') || strcmp(colName, 'timestampMs'), continue; end
                
                colData = T.(colName);
                if isnumeric(colData)
                    validData = colData(~isnan(colData));
                    if isempty(validData) || max(validData) == min(validData)
                        keepCols(i) = false;
                    end
                end
            end
            T_clean = T(:, keepCols);
        end

        %% --- APP PARSERS ---
        function TripData = parseAppSamplesSynced(filename)
            fid = fopen(filename, 'r'); raw = fread(fid, '*char')'; fclose(fid);
            lines = splitlines(strtrim(raw));
            t_vals = []; sigs = {}; v_vals = [];
            for i = 1:length(lines)
                if isempty(lines{i}), continue; end
                try
                    val = jsondecode(lines{i});
                    t_vals(end+1, 1) = val.t; %#ok<*AGROW>
                    sigs{end+1, 1} = val.sig;
                    v_vals(end+1, 1) = val.v;
                catch
                end
            end
            T_long = table(t_vals, sigs, v_vals, 'VariableNames', {'Time_ms', 'Signal', 'Value'});
            [~, uniqueIdx] = unique(strcat(num2str(T_long.Time_ms), T_long.Signal), 'stable');
            T_long = T_long(uniqueIdx, :);
            TripData = unstack(T_long, 'Value', 'Signal', 'AggregationFunction', @mean);
            TripData = sortrows(TripData, 'Time_ms');
            TripData.Linearized_Time = (TripData.Time_ms - TripData.Time_ms(1)) / 1000;
            TripData = movevars(TripData, 'Linearized_Time', 'Before', 1);
            TripData = fillmissing(TripData, 'previous');
        end

        function TripData = parseAppRawWithDBC(rawFile, dbcFile)
            hasVNT = license('test', 'Vehicle_Network_Toolbox');
            if hasVNT
                try db = canDatabase(dbcFile); catch, hasVNT = false; end
            end
            fid = fopen(rawFile, 'r'); rawStr = fread(fid, '*char')'; fclose(fid);
            lines = splitlines(strtrim(rawStr));
            t_vals = []; sigs = {}; v_vals = [];
            for i = 1:length(lines)
                if isempty(lines{i}), continue; end
                try
                    val = jsondecode(lines{i});
                    if hasVNT
                        msg = canMessage(val.id, false, 8);
                        msg.Data = uint8(hex2dec(reshape(val.data, 2, [])'));
                        physVals = decodePhysicalValues(db, msg);
                        fields = fieldnames(physVals);
                        for f = 1:length(fields)
                            t_vals(end+1, 1) = val.t; sigs{end+1, 1} = fields{f}; v_vals(end+1, 1) = physVals.(fields{f});
                        end
                    end
                catch
                end
            end
            if isempty(t_vals), error('Could not decode any raw frames. Vehicle Network Toolbox required.'); end
            T_long = table(t_vals, sigs, v_vals, 'VariableNames', {'Time_ms', 'Signal', 'Value'});
            [~, uniqueIdx] = unique(strcat(num2str(T_long.Time_ms), T_long.Signal), 'stable');
            T_long = T_long(uniqueIdx, :);
            TripData = unstack(T_long, 'Value', 'Signal', 'AggregationFunction', @mean);
            TripData = sortrows(TripData, 'Time_ms');
            TripData.Linearized_Time = (TripData.Time_ms - TripData.Time_ms(1)) / 1000;
            TripData = movevars(TripData, 'Linearized_Time', 'Before', 1);
            TripData = fillmissing(TripData, 'previous');
        end
        
        %% --- ADVANCED DIAGNOSTICS SUITES ---
        
        function analyzeVehicleDynamics(T)
            vars = T.Properties.VariableNames;
            fig = figure('Name', 'Vehicle Dynamics Suite', 'Color', 'w', 'Position', [100 100 1200 600]);
            tiledlayout(1, 2, 'TileSpacing', 'compact');
            
            % Lat/Lon mapping
            latVar = ''; lonVar = ''; yawVar = '';
            if ismember('LateralAcceleration_HS', vars), latVar = 'LateralAcceleration_HS'; end
            if ismember('EPBLongitudinalAcc_HS', vars), lonVar = 'EPBLongitudinalAcc_HS';
            elseif ismember('LongitudinalAccel_HS', vars), lonVar = 'LongitudinalAccel_HS'; end
            if ismember('YawRate_HS', vars), yawVar = 'YawRate_HS'; end
            
            ax1 = nexttile;
            if ~isempty(latVar) && ~isempty(lonVar)
                lat = T.(latVar) / 9.81; lon = T.(lonVar) / 9.81;
                scatter(ax1, lat, lon, 15, T.Linearized_Time, 'filled', 'MarkerFaceAlpha', 0.6);
                colormap(ax1, jet); cb = colorbar(ax1); cb.Label.String = 'Time (s)';
                hold on; xline(0, 'k--'); yline(0, 'k--');
                title(ax1, 'Traction Circle (G-G Diagram)', 'FontWeight', 'bold');
                xlabel(ax1, 'Lateral G'); ylabel(ax1, 'Longitudinal G');
                axis(ax1, 'equal'); grid(ax1, 'on');
            else
                title(ax1, 'Traction Circle Data Missing');
            end
            
            ax2 = nexttile;
            if ~isempty(latVar) && ~isempty(yawVar)
                scatter(ax2, T.(latVar), T.(yawVar), 15, T.Linearized_Time, 'filled');
                colormap(ax2, jet); colorbar(ax2);
                title(ax2, 'Yaw vs. Lateral Accel (Handling Balance)', 'FontWeight', 'bold');
                xlabel(ax2, 'Lateral Accel (m/s^2)'); ylabel(ax2, 'Yaw Rate (deg/s)');
                grid(ax2, 'on');
            else
                title(ax2, 'Handling Balance Data Missing');
            end
        end
        
        function analyzeTransmissionHealth(T)
            vars = T.Properties.VariableNames;
            fig = figure('Name', 'ZF 8HP Transmission Diagnostics', 'Color', 'w', 'Position', [150 150 1000 700]);
            tiledlayout(3, 1, 'TileSpacing', 'compact');
            time = T.Linearized_Time;
            
            ax1 = nexttile; hold(ax1, 'on');
            if ismember('TransOilTemp_HS', vars), plot(ax1, time, T.TransOilTemp_HS, 'b', 'LineWidth', 1.5, 'DisplayName', 'Trans Temp'); end
            if ismember('EngineCoolantTemp_HS', vars), plot(ax1, time, T.EngineCoolantTemp_HS, 'r', 'LineWidth', 1.5, 'DisplayName', 'Coolant Temp'); end
            title(ax1, 'Thermal Status', 'FontWeight', 'bold'); ylabel(ax1, 'Temp (°C)'); legend(ax1, 'Location', 'best'); grid(ax1, 'on');
            
            ax2 = nexttile; hold(ax2, 'on');
            if ismember('GearPosActual_HS', vars), plot(ax2, time, T.GearPosActual_HS, 'k', 'LineWidth', 2, 'DisplayName', 'Actual Gear'); end
            if ismember('GearPosTarget_HS', vars), plot(ax2, time, T.GearPosTarget_HS, 'r--', 'LineWidth', 1.5, 'DisplayName', 'Target Gear'); end
            title(ax2, 'Gear Shift Commands', 'FontWeight', 'bold'); ylabel(ax2, 'Gear'); yticks(ax2, 0:8); legend(ax2, 'Location', 'best'); grid(ax2, 'on');
            
            ax3 = nexttile; hold(ax3, 'on');
            if ismember('TorqConvSlip_HS', vars)
                plot(ax3, time, T.TorqConvSlip_HS, 'm', 'LineWidth', 1.5, 'DisplayName', 'Slip %');
            end
            if ismember('EngineSpeed_HS', vars) && ismember('TransInputSpeed_HS', vars)
                calcSlip = T.EngineSpeed_HS - T.TransInputSpeed_HS;
                plot(ax3, time, calcSlip, 'r', 'LineWidth', 1.5, 'DisplayName', 'Calculated Slip (RPM)');
            end
            title(ax3, 'Torque Converter Slip', 'FontWeight', 'bold'); grid(ax3, 'on'); xlabel(ax3, 'Time (s)'); legend(ax3, 'Location', 'best');
            linkaxes([ax1, ax2, ax3], 'x');
        end
        
        function analyzeShiftQuality(T)
            fprintf('Running Deep Shift Analysis...\n');
            vars = T.Properties.VariableNames;
            
            % Variable mapping for robustness
            rpmVar = ''; if ismember('EngineSpeed_HS', vars), rpmVar = 'EngineSpeed_HS'; elseif ismember('obd_rpm', vars), rpmVar = 'obd_rpm'; end
            actVar = ''; if ismember('GearPosActual_HS', vars), actVar = 'GearPosActual_HS'; end
            tgtVar = ''; if ismember('GearPosTarget_HS', vars), tgtVar = 'GearPosTarget_HS'; end
            
            if isempty(rpmVar) || isempty(actVar) || isempty(tgtVar)
                errordlg('Missing RPM, Actual Gear, or Target Gear for Shift Analysis.'); return;
            end
            
            accelVar = '';
            if ismember('EPBLongitudinalAcc_HS', vars), accelVar = 'EPBLongitudinalAcc_HS';
            elseif ismember('LongitudinalAccel_HS', vars), accelVar = 'LongitudinalAccel_HS'; end
            
            tempVar = '';
            if ismember('TransOilTemp_HS', vars), tempVar = 'TransOilTemp_HS';
            elseif ismember('EngineCoolantTemp_HS', vars), tempVar = 'EngineCoolantTemp_HS'; end
            
            time = T.Linearized_Time; actualGear = T.(actVar); targetGear = T.(tgtVar); rpm = T.(rpmVar);
            shiftIdx = find(diff(actualGear) > 0 & actualGear(1:end-1) > 0);
            shiftScores = struct('GearChange', {}, 'Temp', {}, 'HarshnessG', {}, 'DelayMs', {}, 'RpmFlare', {});
            
            for i = 1:length(shiftIdx)
                idx = shiftIdx(i); t_shift = time(idx);
                windowMask = time >= (t_shift - 0.5) & time <= (t_shift + 1.0);
                if sum(windowMask) < 5, continue; end
                
                t_win = time(windowMask);
                gear_act_win = actualGear(windowMask); gear_tgt_win = targetGear(windowMask); rpm_win = rpm(windowMask);
                
                idx_tgt_change = find(diff(gear_tgt_win) > 0, 1, 'last');
                idx_act_change = find(diff(gear_act_win) > 0, 1, 'last');
                
                if ~isempty(idx_tgt_change) && ~isempty(idx_act_change)
                    delay = (t_win(idx_act_change) - t_win(idx_tgt_change)) * 1000;
                    if delay < 0, delay = 0; end
                else, delay = NaN; end
                
                flare = 0;
                if ~isempty(idx_act_change)
                    flareMask = t_win >= (t_win(idx_act_change) - 0.3) & t_win <= t_win(idx_act_change);
                    if sum(flareMask) > 1, flare = max(0, max(diff(rpm_win(flareMask)))); end
                end
                
                if ~isempty(accelVar), accel_win = T.(accelVar)(windowMask) / 9.81; harshness = max(accel_win) - min(accel_win);
                else, harshness = NaN; end
                
                if ~isempty(tempVar), temp = mean(T.(tempVar)(windowMask), 'omitnan'); else, temp = NaN; end
                
                gearStr = sprintf('%d->%d', gear_act_win(1), gear_act_win(end));
                shiftScores(end+1) = struct('GearChange', gearStr, 'Temp', temp, 'HarshnessG', harshness, 'DelayMs', delay, 'RpmFlare', flare);
            end
            
            if isempty(shiftScores), msgbox('No valid upshifts found in this log.'); return; end
            
            temps = [shiftScores.Temp]; harsh = [shiftScores.HarshnessG];
            delays = [shiftScores.DelayMs]; flares = [shiftScores.RpmFlare];
            
            fig = figure('Name', 'ZF 8HP Deep Shift Analysis', 'Color', 'w', 'Position', [100 100 1200 600]);
            tiledlayout(1, 2, 'TileSpacing', 'compact');
            
            ax1 = nexttile; scatter(ax1, temps, harsh, 60, delays, 'filled', 'MarkerEdgeColor', 'k');
            colormap(ax1, jet); cb1 = colorbar(ax1); cb1.Label.String = 'Shift Delay (ms)';
            title(ax1, 'Shift Harshness (Clunk) vs. Temp', 'FontWeight', 'bold'); xlabel(ax1, 'Temp (°C)'); ylabel(ax1, 'Peak-to-Peak G'); grid(ax1, 'on');
            
            ax2 = nexttile; scatter(ax2, temps, flares, 60, harsh, 'filled', 'MarkerEdgeColor', 'k');
            colormap(ax2, hot); cb2 = colorbar(ax2); cb2.Label.String = 'Harshness (G)';
            title(ax2, 'Shift Hesitation (RPM Flare) vs. Temp', 'FontWeight', 'bold'); xlabel(ax2, 'Temp (°C)'); ylabel(ax2, 'RPM Flare'); grid(ax2, 'on');
        end
        
        %% --- UTILITY FUNCTIONS ---
        function [t_vec, dt, timeColName] = getTimeData(T)
            vars = T.Properties.VariableNames; lowerVars = lower(vars); timeColName = '';
            priority = {'linearized_time', 'time_ms', 'relativetime_s', 'time', 'timestampms'};
            for i = 1:length(priority)
                idx = find(strcmp(lowerVars, priority{i}), 1);
                if ~isempty(idx), timeColName = vars{idx}; break; end
            end
            if ~isempty(timeColName), t_vec = T.(timeColName); else, t_vec = (0:height(T)-1)'; end
            dt = 0.01;
        end
        
        function plotStandardTelemetry(T)
            [timeData, ~, timeColName] = OBD_AnalyticsLab.getTimeData(T);
            allVars = T.Properties.VariableNames;
            excludeCols = {timeColName, 'Linearized_Time', 'Time_ms', 'timestampMs'};
            sensorNames = allVars(~ismember(allVars, excludeCols));
            validSensors = {};
            for p = 1:length(sensorNames)
                colData = T.(sensorNames{p});
                if isnumeric(colData) && ~all(isnan(colData)), validSensors{end+1} = sensorNames{p}; end
            end
            if isempty(validSensors), disp('No valid numeric sensor data found.'); return; end
            
            numPlots = length(validSensors); plotsPerWindow = 5; numWindows = ceil(numPlots / plotsPerWindow);
            for w = 1:numWindows
                startIdx = (w - 1) * plotsPerWindow + 1; endIdx = min(w * plotsPerWindow, numPlots);
                currentPlotCount = endIdx - startIdx + 1;
                figure('Name', sprintf('Standard Telemetry Dashboard (Page %d of %d)', w, numWindows), 'Position', [50, 100, 1200, 200 * currentPlotCount]);
                tiledlayout(currentPlotCount, 1, 'TileSpacing', 'compact');
                for p = startIdx:endIdx
                    sName = validSensors{p}; nexttile;
                    plot(timeData, T.(sName), 'LineWidth', 1.5, 'Color', [0 0.4470 0.7410]);
                    title(strrep(sName, '_', ' '), 'FontSize', 11, 'FontWeight', 'bold'); grid on;
                end
                xlabel('Time (seconds)', 'FontSize', 12, 'FontWeight', 'bold');
            end
        end
    end
end