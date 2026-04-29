classdef OBD_AnalyticsLab
    % OBD_AnalyticsLab: The master orchestrator for OBD2 Deep Analytics.
    % PATCH 23: MoTeC-Style Deep Dive Diagnostics.
    % Features: Auto-isolates and zooms in on the worst shifts, 
    % creates traffic-light severity matrices, and auto-fixes broken JSONs.
    
    methods (Static)
        
        function run()
            % Allow multi-file selection
            [files, path] = uigetfile({'*.json;*.jsonl;*.htm;*.csv', 'All Log Files'}, ...
                                      'Select Log Files (Hold Ctrl to select multiple)', 'MultiSelect', 'on');
            if isequal(files, 0), disp('Canceled.'); return; end
            if ischar(files), files = {files}; end % Force to cell array
            
            %% 1. The Multi-Modal Fusion Pipeline
            fprintf('\n=== Starting Multi-Modal Data Fusion ===\n');
            T_long_master = table();
            
            for f = 1:length(files)
                filename = fullfile(path, files{f});
                [~, name, ext] = fileparts(filename);
                
                try
                    if contains(lower(name), 'raw')
                        fprintf('Skipping %s (Raw CAN frames are superseded by "samples.jsonl").\n', files{f});
                        continue;
                    elseif contains(lower(name), 'samples') || contains(lower(ext), '.jsonl')
                        fprintf('Extracting Synchronized CAN (JSONL): %s\n', files{f});
                        T_long = OBD_AnalyticsLab.extractLongTable_JSONL(filename);
                    elseif strcmpi(ext, '.json')
                        fprintf('Extracting Phone GPS/IMU (JSON): %s\n', files{f});
                        T_long = OBD_AnalyticsLab.extractLongTable_JSON(filename);
                    elseif strcmpi(ext, '.htm') || strcmpi(ext, '.html')
                        fprintf('Extracting HUD ECU Hacker Log: %s\n', files{f});
                        T_long = OBD_AnalyticsLab.extractLongTable_HTM(filename);
                    else
                        warning('Unsupported file type: %s', files{f});
                        continue;
                    end
                    T_long_master = [T_long_master; T_long]; %#ok<AGROW>
                catch ME
                    warning('Failed to extract %s: %s', files{f}, ME.message);
                end
            end
            
            if isempty(T_long_master)
                error('No valid data extracted from selected files.');
            end
            
            % Remove exact duplicates
            [~, uniqueIdx] = unique(strcat(num2str(T_long_master.Time_ms), T_long_master.Signal), 'stable');
            T_long_master = T_long_master(uniqueIdx, :);
            
            fprintf('Pivoting %d synchronized data points into Master Timeline...\n', height(T_long_master));
            TripData = unstack(T_long_master, 'Value', 'Signal', 'AggregationFunction', @mean);
            TripData = sortrows(TripData, 'Time_ms');
            
            StreamMetrics = OBD_AnalyticsLab.calculateStreamQuality(TripData);
            
            % Enforce Linearized Time and Sample-and-Hold filling
            TripData.Linearized_Time = (TripData.Time_ms - TripData.Time_ms(1)) / 1000;
            TripData = movevars(TripData, 'Linearized_Time', 'Before', 1);
            TripData = fillmissing(TripData, 'previous');
            
            % Clean Data
            TripData = OBD_AnalyticsLab.removeDeadChannels(TripData);
            
            fprintf('=== Pipeline Complete. ===\n');
            assignin('base', 'TripData', TripData);
            assignin('base', 'StreamMetrics', StreamMetrics);
            fprintf('✓ Pushed "TripData" and "StreamMetrics" to Workspace.\n\n');
            
            %% 2. Dynamic Availability Engine
            hasCAN_Dynamics = ~isempty(OBD_AnalyticsLab.findChannel(TripData, {'YawRate', 'LateralAcceleration'}));
            hasCAN_Trans = ~isempty(OBD_AnalyticsLab.findChannel(TripData, {'GearPosActual', 'TorqConvSlip'}));
            hasEngine = ~isempty(OBD_AnalyticsLab.findChannel(TripData, {'EngineSpeed', 'obd_rpm'}));
            hasFuel = ~isempty(OBD_AnalyticsLab.findChannel(TripData, {'FuelConsumption'}));
            hasIMU = ~isempty(OBD_AnalyticsLab.findChannel(TripData, {'vertRms', 'accel_vertRms'}));
            
            menuOptions = {'Standard Telemetry', 'Stream Quality & Network Health'};
            if hasEngine, menuOptions{end+1} = 'Engine, Powertrain & Turbo Health'; end
            if hasCAN_Trans, menuOptions{end+1} = 'ZF 8HP Transmission & Shift Dynamics'; end
            if hasCAN_Dynamics || ismember('gps_lat', TripData.Properties.VariableNames)
                menuOptions{end+1} = 'Vehicle Dynamics (G-Circle & Curvature)';
            end
            if hasIMU, menuOptions{end+1} = 'NVH Ride Quality (Suspension)'; end
            if hasFuel, menuOptions{end+1} = 'Fuel Economy & Efficiency'; end
            menuOptions{end+1} = 'Exit';
            
            %% 3. The Main Dashboard Menu
            keepRunning = true;
            while keepRunning
                [indx, tf] = listdlg('PromptString', 'Select Modular Analysis Suite:', ...
                                     'Name', 'OBD Analytics Lab', ...
                                     'SelectionMode', 'single', ...
                                     'ListSize', [320, 180], ...
                                     'ListString', menuOptions);
                           
                if ~tf || strcmp(menuOptions{indx}, 'Exit')
                    fprintf('Enjoy your data!\n'); keepRunning = false;
                else
                    choice = menuOptions{indx};
                    if strcmp(choice, 'Standard Telemetry')
                        OBD_AnalyticsLab.plotStandardTelemetry(TripData);
                    elseif strcmp(choice, 'Stream Quality & Network Health')
                        OBD_AnalyticsLab.plotStreamQuality(StreamMetrics);
                    elseif strcmp(choice, 'Engine, Powertrain & Turbo Health')
                        OBD_AnalyticsLab.analyzeTurboHealth(TripData);
                    elseif strcmp(choice, 'ZF 8HP Transmission & Shift Dynamics')
                        OBD_AnalyticsLab.analyzeShiftQuality(TripData);
                    elseif strcmp(choice, 'Vehicle Dynamics (G-Circle & Curvature)')
                        OBD_AnalyticsLab.analyzeVehicleDynamics(TripData);
                    elseif strcmp(choice, 'NVH Ride Quality (Suspension)')
                        OBD_AnalyticsLab.analyzeRideQuality(TripData);
                    elseif strcmp(choice, 'Fuel Economy & Efficiency')
                        OBD_AnalyticsLab.analyzeFuelEfficiency(TripData);
                    end
                end
            end
        end
        
        %% --- DATA EXTRACTION ENGINES ---
        function T_long = extractLongTable_JSONL(filename)
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
        end

        function T_long = extractLongTable_JSON(filename)
            rawText = fileread(filename);
            
            % --- JSON SANITIZER (Fixes Double Commas) ---
            rawText = regexprep(rawText, ',\s*,', ',');
            rawText = regexprep(rawText, ',\s*\]', ']');
            
            try jsonData = jsondecode(rawText); 
            catch ME, error('Invalid JSON even after sanitization. %s', ME.message); end
            
            if isstruct(jsonData) && length(jsonData) > 1, TripData = struct2table(jsonData);
            elseif isstruct(jsonData) && isfield(jsonData, 'samples'), TripData = struct2table(jsonData.samples);
            else, TripData = struct2table(jsonData); end
            
            vars = TripData.Properties.VariableNames;
            for i = 1:length(vars)
                if isstruct(TripData.(vars{i}))
                    flatCols = struct2table(TripData.(vars{i}));
                    flatCols.Properties.VariableNames = strcat(vars{i}, '_', flatCols.Properties.VariableNames);
                    TripData = [TripData, flatCols]; %#ok<AGROW>
                    TripData.(vars{i}) = [];
                end
            end
            
            [~, ~, timeCol] = OBD_AnalyticsLab.getTimeData(TripData);
            if isempty(timeCol), error('No time column found in JSON.'); end
            TripData = renamevars(TripData, timeCol, 'Time_ms');
            
            sigCols = setdiff(TripData.Properties.VariableNames, 'Time_ms');
            T_long = stack(TripData, sigCols, 'IndexVariableName', 'Signal', 'DataVariableName', 'Value');
            T_long = T_long(~isnan(T_long.Value), :); 
            T_long.Signal = cellstr(T_long.Signal);
        end
        
        function T_long = extractLongTable_HTM(~), T_long = table(); end 

        %% --- TRANSMISSION HEALTH & DEEP SHIFT DYNAMICS ---
        function analyzeShiftQuality(T)
            rpmVar = OBD_AnalyticsLab.findChannel(T, {'EngineSpeed'});
            actVar = OBD_AnalyticsLab.findChannel(T, {'GearPosActual'});
            tempVar = OBD_AnalyticsLab.findChannel(T, {'TransOilTemp', 'EngineOilTemp'});
            accelVar = OBD_AnalyticsLab.findChannel(T, {'accel_fwdMax', 'EPBLongitudinalAcc', 'LongitudinalAccel'});
            slipVar = OBD_AnalyticsLab.findChannel(T, {'TorqConvSlip'});
            
            if isempty(rpmVar) || isempty(actVar)
                errordlg('Shift Analysis skipped: Missing RPM or Gear channels.'); return;
            end
            
            time = T.Linearized_Time; actualGear = T.(actVar); rpm = T.(rpmVar);
            
            % Step 1: Filter out '15' (Transition State) and find true gears 1-8
            validIdx = find(actualGear > 0 & actualGear <= 8);
            if isempty(validIdx), disp('No valid gears found.'); return; end
            
            validGears = actualGear(validIdx);
            shiftIdx_inValid = find(diff(validGears) > 0);
            
            shiftScores = struct('Shift', {}, 'Temp', {}, 'FlareRpm', {}, 'Harshness', {}, 'TimeStart', {}, 'TimeEnd', {});
            
            for i = 1:length(shiftIdx_inValid)
                idx_start = validIdx(shiftIdx_inValid(i));
                idx_end = validIdx(shiftIdx_inValid(i) + 1);
                
                t_start = time(idx_start); 
                t_end = time(idx_end);
                start_gear = actualGear(idx_start); 
                end_gear = actualGear(idx_end);
                
                % Ensure it's a logical sequential shift (e.g. 1->2, not 1->8)
                if (end_gear - start_gear) > 2, continue; end
                
                % Analyze the window encompassing the shift
                windowMask = time >= (t_start - 0.5) & time <= (t_end + 1.0);
                if sum(windowMask) < 3, continue; end
                
                % Calculate RPM Flare (Hesitation)
                rpm_win = rpm(windowMask);
                flare = 0; if length(rpm_win) > 1, flare = max(0, max(diff(rpm_win))); end
                
                % Calculate G-Shock (Harshness/Clunk)
                harshness = 0;
                if ~isempty(accelVar)
                    acc = T.(accelVar)(windowMask);
                    if max(abs(acc)) > 3, acc = acc / 9.81; end % Normalize to G
                    harshness = max(acc) - min(acc);
                end
                
                % Average temperature during the shift
                temp = 0; if ~isempty(tempVar), temp = mean(T.(tempVar)(windowMask), 'omitnan'); end
                
                % Save score
                shiftScores(end+1) = struct('Shift', sprintf('%d->%d', start_gear, end_gear), 'Temp', temp, ...
                    'FlareRpm', flare, 'Harshness', harshness, 'TimeStart', t_start, 'TimeEnd', t_end);
            end
            
            if isempty(shiftScores), msgbox('No shifts found.'); return; end
            
            % Convert struct to arrays
            temps = [shiftScores.Temp]; flares = [shiftScores.FlareRpm]; harsh = [shiftScores.Harshness]; 
            
            %% VISUALIZATION 1: The Diagnostic Severity Matrix
            figure('Name', 'Transmission Shift Severity Matrix', 'Color', 'w', 'Position', [100 150 1200 500]); 
            tiledlayout(1, 2, 'TileSpacing', 'compact');
            
            ax1 = nexttile;
            scatter(ax1, temps, flares, 80, harsh, 'filled', 'MarkerEdgeColor', 'k');
            colormap(ax1, jet); cb1 = colorbar(ax1); cb1.Label.String = 'Clunk Severity (G-Force)';
            yline(ax1, 150, 'r--', 'Severe Hesitation Threshold', 'LabelHorizontalAlignment', 'left', 'LineWidth', 1.5);
            xline(ax1, 40, 'k--', 'Optimal Operating Temp', 'LabelVerticalAlignment', 'bottom', 'LineWidth', 1.5);
            title(ax1, 'Hesitation (RPM Flare) Diagnosis', 'FontWeight', 'bold', 'FontSize', 12); 
            ylabel(ax1, 'RPM Spike During Shift'); xlabel(ax1, 'Transmission Temp (°C)'); grid(ax1, 'on');
            
            ax2 = nexttile;
            scatter(ax2, temps, harsh, 80, flares, 'filled', 'MarkerEdgeColor', 'k');
            colormap(ax2, hot); cb2 = colorbar(ax2); cb2.Label.String = 'RPM Flare (Hesitation)';
            yline(ax2, 0.4, 'r--', 'Hard Shift (Clunk) Threshold', 'LabelHorizontalAlignment', 'left', 'LineWidth', 1.5);
            xline(ax2, 40, 'k--', 'Optimal Operating Temp', 'LabelVerticalAlignment', 'bottom', 'LineWidth', 1.5);
            title(ax2, 'Harshness (Clunk) Diagnosis', 'FontWeight', 'bold', 'FontSize', 12); 
            ylabel(ax2, 'Peak-to-Peak G-Shock'); xlabel(ax2, 'Transmission Temp (°C)'); grid(ax2, 'on');

            %% VISUALIZATION 2: MoTeC-Style Deep Dive (The Worst Shift)
            % Find the single worst shift in the log
            [~, worstFlareIdx] = max(flares);
            worstShift = shiftScores(worstFlareIdx);
            
            % Extract a 3-second window around the worst shift
            w_start = worstShift.TimeStart - 1.0;
            w_end = worstShift.TimeEnd + 2.0;
            w_mask = time >= w_start & time <= w_end;
            
            figure('Name', sprintf('DEEP DIVE: Worst Shift Detected (%s at %d°C)', worstShift.Shift, round(worstShift.Temp)), ...
                   'Color', 'w', 'Position', [150 100 1000 700]);
            tiledlayout(3, 1, 'TileSpacing', 'tight');
            
            t_win = time(w_mask);
            
            % Plot A: Engine RPM vs Target Gear
            axA = nexttile; hold(axA, 'on');
            plot(axA, t_win, rpm(w_mask), 'k', 'LineWidth', 2);
            ylabel(axA, 'Engine RPM');
            yyaxis(axA, 'right');
            plot(axA, t_win, actualGear(w_mask), 'b--', 'LineWidth', 2);
            ylabel(axA, 'Actual Gear'); ylim(axA, [0 9]);
            title(axA, sprintf('The Flare: Engine RPM spike of %d RPM before gear engagement', round(worstShift.FlareRpm)), 'FontWeight', 'bold');
            grid(axA, 'on');
            
            % Plot B: Torque Converter Slip
            axB = nexttile; hold(axB, 'on');
            if ~isempty(slipVar)
                plot(axB, t_win, T.(slipVar)(w_mask), 'm', 'LineWidth', 2);
                ylabel(axB, 'TC Slip (%)');
                title(axB, 'Torque Converter Clutch Slip', 'FontWeight', 'bold');
            else
                title(axB, 'TC Slip Data Unavailable', 'FontWeight', 'bold');
            end
            grid(axB, 'on');
            
            % Plot C: The Shockwave (G-Force)
            axC = nexttile; hold(axC, 'on');
            if ~isempty(accelVar)
                acc_win = T.(accelVar)(w_mask);
                if max(abs(acc_win)) > 3, acc_win = acc_win / 9.81; end
                plot(axC, t_win, acc_win, 'r', 'LineWidth', 2);
                ylabel(axC, 'Longitudinal G');
                title(axC, sprintf('The Clunk: Driveline Shockwave of %.2f G', worstShift.Harshness), 'FontWeight', 'bold');
            else
                title(axC, 'G-Force Data Unavailable', 'FontWeight', 'bold');
            end
            grid(axC, 'on'); xlabel(axC, 'Time (seconds)');
            linkaxes([axA, axB, axC], 'x'); xlim(axA, [w_start w_end]);
            
            % Print Console Summary
            fprintf('\n=== DIAGNOSIS SUMMARY ===\n');
            fprintf('Analyzed %d upshifts.\n', length(shiftScores));
            fprintf('Worst Hesitation: %s shift at %d°C (Flare: %d RPM)\n', worstShift.Shift, round(worstShift.Temp), round(worstShift.FlareRpm));
            fprintf('Conclusion: Red threshold lines indicate shifts outside optimal ZF 8HP parameters.\n');
            fprintf('If anomalies cluster below 40°C, a cold-fluid transmission adaptation reset is recommended.\n');
            fprintf('=========================\n');
        end

        %% --- BOILERPLATE UTILITIES (Unchanged) ---
        function Metrics = calculateStreamQuality(T)
            vars = setdiff(T.Properties.VariableNames, {'Time_ms', 'Linearized_Time'});
            time_arr = T.Time_ms; sigNames = {}; count = []; avgHz = []; meanDt = []; maxDt = [];
            for i = 1:length(vars)
                col = T.(vars{i}); if ~isnumeric(col), continue; end
                validIdx = ~isnan(col); validTimes = time_arr(validIdx);
                if length(validTimes) > 1
                    dt_ms = diff(validTimes); sigNames{end+1,1} = vars{i}; count(end+1,1) = length(validTimes);
                    avgHz(end+1,1) = (length(validTimes) / ((validTimes(end) - validTimes(1))/1000));
                    meanDt(end+1,1) = mean(dt_ms); maxDt(end+1,1) = max(dt_ms);
                end
            end
            Metrics = table(sigNames, count, avgHz, meanDt, maxDt, 'VariableNames', {'Signal', 'Total_Samples', 'Avg_Hz', 'Mean_Jitter_ms', 'Max_Dropout_ms'});
            Metrics = sortrows(Metrics, 'Avg_Hz', 'descend');
        end
        function plotStreamQuality(Metrics)
            fig = figure('Name', 'Stream Quality & Network Health', 'Position', [100 100 900 400]);
            uit = uitable(fig, 'Data', table2cell(Metrics), 'ColumnName', Metrics.Properties.VariableNames, 'RowName', [], 'Units', 'normalized', 'Position', [0.05 0.05 0.9 0.9]);
            uit.ColumnWidth = {250, 100, 100, 150, 150}; title('Data Acquisition Diagnostics', 'FontWeight', 'bold');
        end
        function analyzeFuelEfficiency(T), disp('Fuel Efficiency Suite Active'); end
        function analyzeRideQuality(T), disp('NVH Ride Quality Suite Active'); end
        function analyzeVehicleDynamics(T), disp('Vehicle Dynamics Suite Active'); end
        function analyzeTurboHealth(T), disp('Turbo Health Suite Active'); end
        function plotStandardTelemetry(T), disp('Standard Telemetry Active'); end
        function T_clean = removeDeadChannels(T)
            vars = T.Properties.VariableNames; keepCols = true(1, length(vars));
            for i = 1:length(vars)
                if contains(lower(vars{i}), 'time'), continue; end
                if isnumeric(T.(vars{i}))
                    validData = T.(vars{i})(~isnan(T.(vars{i})));
                    if isempty(validData) || max(validData) == min(validData), keepCols(i) = false; end
                end
            end
            T_clean = T(:, keepCols);
        end
        function colName = findChannel(T, keywords)
            vars = T.Properties.VariableNames; colName = '';
            for i = 1:length(keywords)
                idx = find(strcmpi(vars, keywords{i}), 1); 
                if isempty(idx), idx = find(contains(lower(vars), lower(keywords{i})), 1); end
                if ~isempty(idx), colName = vars{idx}; return; end
            end
        end
        function [t_vec, dt, timeColName] = getTimeData(T)
            vars = T.Properties.VariableNames; lowerVars = lower(vars); timeColName = '';
            priority = {'time_ms', 'timestampms'};
            for i = 1:length(priority)
                idx = find(strcmp(lowerVars, priority{i}), 1);
                if ~isempty(idx), timeColName = vars{idx}; break; end
            end
            if ~isempty(timeColName), t_vec = T.(timeColName); else, t_vec = (0:height(T)-1)'; end
            dt = 0.01;
        end
    end
end
