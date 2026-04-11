classdef OBD_AnalyticsLab
    % OBD_AnalyticsLab: The master orchestrator for OBD2 Deep Analytics.
    % PATCH 9: Universal Engine. Auto-detects HTML CAN logs vs JSON OBD logs,
    % integrating the ultimate 26-channel JLR Master CAN Dictionary.
    
    methods (Static)
        
        function run(filename)
            if nargin < 1 || isempty(filename)
                [file, path] = uigetfile({'*.htm;*.html;*.json;*.csv', 'All Log Files'}, 'Select Log File');
                if isequal(file, 0), disp('Canceled.'); return; end
                filename = fullfile(path, file);
            end
            
            %% 1. The Automated Pipeline
            fprintf('=== Starting OBD Deep Analytics Pipeline ===\n');
            fprintf('Processing: %s\n', filename);
            
            [~, ~, ext] = fileparts(filename);
            
            if strcmpi(ext, '.htm') || strcmpi(ext, '.html')
                % --- RAW CAN LOG PIPELINE ---
                fprintf('Detected raw CAN trace. Applying Master JLR Dictionary...\n');
                TripData = OBD_AnalyticsLab.parseJLR_CAN(filename);
            else
                % --- ELM327 JSON/CSV PIPELINE ---
                fprintf('Detected OBD JSON/CSV. Running standard importer...\n');
                [TripData, Profile] = OBD_Importer.read(filename);
                TripData = OBD_Calculators.applyAll(TripData, Profile);
            end
            
            % --- Force Chronological Sort and Perfect Linearity ---
            [raw_t_vec, ~, timeColName] = OBD_AnalyticsLab.getTimeData(TripData);
            if ~isempty(timeColName)
                TripData = sortrows(TripData, timeColName);
            end
            
            num_samples = height(TripData);
            t_max = max(raw_t_vec);
            if num_samples > 1 && t_max > 0
                linear_t_vec = linspace(0, t_max, num_samples)';
            else
                linear_t_vec = raw_t_vec;
            end
            
            TripData.Linearized_Time = linear_t_vec;
            
            try
                TripData = movevars(TripData, 'Linearized_Time', 'Before', 1);
            catch
                % Fallback for older MATLAB versions
            end
            
            fprintf('=== Pipeline Complete. ===\n');
            
            % Push the processed data directly to the Workspace
            assignin('base', 'TripData', TripData);
            fprintf('✓ Successfully pushed "TripData" (with all 26+ calculations) to your Workspace.\n');
            
            %% 2. The Main Dashboard Menu (Loop)
            keepRunning = true;
            
            while keepRunning
                choice = questdlg('Data processed and saved to Workspace. What would you like to do?', ...
                               'OBD Analytics Lab', ...
                               'Plot Standard Telemetry', 'Deep Analytics...', 'Exit (Keep Data)', 'Exit (Keep Data)');
                           
                if isempty(choice) || strcmp(choice, 'Exit (Keep Data)')
                    fprintf('Enjoy your data! Double-click "TripData" in the Workspace panel to view it.\n');
                    keepRunning = false;
                    
                elseif strcmp(choice, 'Plot Standard Telemetry')
                    fprintf('Launching Standard Telemetry Dashboard...\n');
                    OBD_AnalyticsLab.plotStandardTelemetry(TripData);
                    
                elseif strcmp(choice, 'Deep Analytics...')
                    
                    [~, ~, timeColName] = OBD_AnalyticsLab.getTimeData(TripData);
                    allVars = TripData.Properties.VariableNames;
                    excludeCols = {timeColName, 'Linearized_Time', 'timestampMs', 'Time', 'RelativeTime_s'};
                    validVars = allVars(~ismember(allVars, excludeCols));
                    
                    analysisType = questdlg('Select Advanced Module:', 'Deep Analytics Lab', ...
                        'Time-Domain Correlations', 'Engine Heatmap', 'Frequency Domain (FFT/PSD)', 'Time-Domain Correlations');
                    
                    if isempty(analysisType), continue; end
                    
                    switch analysisType
                        case 'Time-Domain Correlations'
                            [indx, tf] = listdlg('PromptString', 'Select signals to correlate:', ...
                                                 'SelectionMode', 'multiple', 'ListString', validVars, 'ListSize', [300 400]);
                            if tf, OBD_AnalyticsLab.plotCorrelationMatrix(TripData, validVars(indx)); end
                            
                        case 'Engine Heatmap'
                            OBD_AnalyticsLab.plotEngineHeatmap(TripData);
                            
                        case 'Frequency Domain (FFT/PSD)'
                            [indx, tf] = listdlg('PromptString', 'Select 1 or 2 signals for Frequency Analysis:', ...
                                                 'SelectionMode', 'multiple', 'ListString', validVars, 'ListSize', [300 400]);
                            if tf
                                selected = validVars(indx);
                                if length(selected) == 1
                                    OBD_AnalyticsLab.plotFrequencyAnalysis(TripData, selected{1});
                                elseif length(selected) == 2
                                    OBD_AnalyticsLab.plotCoherence(TripData, selected{1}, selected{2});
                                end
                            end
                    end
                end
            end
        end
        
        %% --- ULTIMATE JLR CAN DICTIONARY PARSER ---
        function TripData = parseJLR_CAN(filename)
            % 1. The Master Dictionary (26 Channels)
            sensorDict = struct('Name', {}, 'ID', {}, 'Formula', {});
            
            % DRIVER INPUTS
            sensorDict(end+1) = struct('Name', 'Accelerator_Pedal', 'ID', '2CB', 'Formula', @(b) b(:,2) / 2.55);
            sensorDict(end+1) = struct('Name', 'Brake_Pressure', 'ID', '3EB', 'Formula', @(b) ((b(:,5) * 256) + b(:,6)) / 100);
            sensorDict(end+1) = struct('Name', 'Brake_Pedal_Switch', 'ID', '295', 'Formula', @(b) bitand(b(:,7), 32) / 32);
            
            % CHASSIS & IMU
            sensorDict(end+1) = struct('Name', 'Steering_Angle', 'ID', '280', 'Formula', @(b) (((b(:,7) * 256) + b(:,8)) - 7941) / 10);
            sensorDict(end+1) = struct('Name', 'Yaw_Rate', 'ID', '3D3', 'Formula', @(b) (((b(:,7) * 256) + b(:,8)) - 1438) / 10);
            sensorDict(end+1) = struct('Name', 'Accel_Longitudinal', 'ID', '312', 'Formula', @(b) (b(:,1) * 256) + b(:,2));
            sensorDict(end+1) = struct('Name', 'Accel_Lateral', 'ID', '312', 'Formula', @(b) (b(:,3) * 256) + b(:,4));
            sensorDict(end+1) = struct('Name', 'Roll_Rate', 'ID', '312', 'Formula', @(b) (b(:,5) * 256) + b(:,6));
            
            % WHEEL SPEEDS
            sensorDict(end+1) = struct('Name', 'Wheel_Speed_FL', 'ID', '215', 'Formula', @(b) ((bitand(b(:,1), 15) * 256) + b(:,2)) * 0.01);
            sensorDict(end+1) = struct('Name', 'Wheel_Speed_FR', 'ID', '215', 'Formula', @(b) ((bitand(b(:,3), 15) * 256) + b(:,4)) * 0.01);
            sensorDict(end+1) = struct('Name', 'Wheel_Speed_RL', 'ID', '215', 'Formula', @(b) ((bitand(b(:,5), 15) * 256) + b(:,6)) * 0.01);
            sensorDict(end+1) = struct('Name', 'Wheel_Speed_RR', 'ID', '215', 'Formula', @(b) ((bitand(b(:,7), 15) * 256) + b(:,8)) * 0.01);
            
            % POWERTRAIN
            sensorDict(end+1) = struct('Name', 'Engine_RPM', 'ID', '295', 'Formula', @(b) (bitand(b(:,7), 31) * 256) + b(:,8));
            sensorDict(end+1) = struct('Name', 'Engine_Torque_Nm', 'ID', '20B', 'Formula', @(b) ((b(:,5) * 256) + b(:,6)) - 1000);
            sensorDict(end+1) = struct('Name', 'Vehicle_Speed', 'ID', '29B', 'Formula', @(b) ((b(:,7) * 256) + b(:,8)) / 100);
            sensorDict(end+1) = struct('Name', 'Selected_Gear', 'ID', '2CB', 'Formula', @(b) bitand(b(:,4), 15));
            sensorDict(end+1) = struct('Name', 'Coolant_Temp', 'ID', '355', 'Formula', @(b) b(:,6) - 40);
            
            % INSTRUMENTS
            sensorDict(end+1) = struct('Name', 'Fuel_Level', 'ID', '477', 'Formula', @(b) b(:,1) / 2.55);
            sensorDict(end+1) = struct('Name', 'Ambient_Temp', 'ID', '4E1', 'Formula', @(b) (b(:,7) / 4) - 40);
            sensorDict(end+1) = struct('Name', 'Odometer', 'ID', '4C0', 'Formula', @(b) ((b(:,5) * 65536) + (b(:,6) * 256) + b(:,7)) / 10);
            
            % FAULTS & STATUS
            sensorDict(end+1) = struct('Name', 'Gearbox_Fault_State', 'ID', '3F3', 'Formula', @(b) b(:,7));
            sensorDict(end+1) = struct('Name', 'Limp_Mode_Yellow', 'ID', '315', 'Formula', @(b) bitand(b(:,2), 2) / 2);
            sensorDict(end+1) = struct('Name', 'Cruise_Unavailable', 'ID', '315', 'Formula', @(b) bitand(b(:,2), 4) / 4);
            sensorDict(end+1) = struct('Name', 'Glow_Plugs_Active', 'ID', '315', 'Formula', @(b) bitand(b(:,4), 8) / 8);
            sensorDict(end+1) = struct('Name', 'Oil_Warning', 'ID', '315', 'Formula', @(b) bitand(b(:,4), 16) / 16);
            sensorDict(end+1) = struct('Name', 'Seatbelt_Warning', 'ID', '39A', 'Formula', @(b) bitand(b(:,7), 48) / 48);

            % 2. Read and Parse HTML
            filetext = fileread(filename);
            pattern = '>(\d{2}:\d{2}:\d{2}\.\d{3})</span><span class=''RxData''>([0-9A-F]{3}):\s+([0-9A-F\s]+)</span>';
            tokens = regexp(filetext, pattern, 'tokens');
            if isempty(tokens), error('No CAN data found in HTML file.'); end
            
            % 3. Extract to Map
            canMap = containers.Map('KeyType', 'char', 'ValueType', 'any');
            t0 = datetime(tokens{1}{1}, 'InputFormat', 'HH:mm:ss.SSS');
            for i = 1:length(tokens)
                t_curr = datetime(tokens{i}{1}, 'InputFormat', 'HH:mm:ss.SSS');
                time_sec = seconds(t_curr - t0);
                id = tokens{i}{2};
                byteCells = strsplit(strtrim(tokens{i}{3}));
                if length(byteCells) == 8
                    rowData = [time_sec, hex2dec(byteCells)'];
                    if isKey(canMap, id), canMap(id) = [canMap(id); rowData];
                    else, canMap(id) = rowData; end
                end
            end
            
            % 4. Interpolate onto 100Hz base (0.01s)
            lastTime = datetime(tokens{end}{1}, 'InputFormat', 'HH:mm:ss.SSS');
            t_max = seconds(lastTime - t0);
            Linearized_Time = (0 : 0.01 : t_max)';
            TripData = table(Linearized_Time);
            
            for s = 1:length(sensorDict)
                sensor = sensorDict(s);
                if isKey(canMap, sensor.ID)
                    rawTbl = canMap(sensor.ID);
                    raw_t = rawTbl(:,1);
                    raw_vals = sensor.Formula(rawTbl(:, 2:9));
                    [uTime, uIdx] = unique(raw_t, 'stable');
                    if length(uTime) > 1
                        TripData.(sensor.Name) = interp1(uTime, raw_vals(uIdx), Linearized_Time, 'previous', 'extrap');
                    else
                        TripData.(sensor.Name) = nan(size(Linearized_Time));
                    end
                else
                    TripData.(sensor.Name) = nan(size(Linearized_Time));
                end
            end
        end
        
        %% --- BULLETPROOF TIME FINDER ---
        function [t_vec, dt, timeColName] = getTimeData(T)
            vars = T.Properties.VariableNames;
            lowerVars = lower(vars);
            timeColName = '';
            
            priority = {'linearized_time', 'relativetime_s', 'time', 'timestampms'};
            for i = 1:length(priority)
                idx = find(strcmp(lowerVars, priority{i}), 1);
                if ~isempty(idx), timeColName = vars{idx}; break; end
            end
            
            if isempty(timeColName)
                idx = find(contains(lowerVars, 'time') | contains(lowerVars, 'timestamp'), 1);
                if ~isempty(idx), timeColName = vars{idx}; end
            end
            
            if ~isempty(timeColName)
                raw_t = T.(timeColName);
                if isdatetime(raw_t) || isduration(raw_t)
                    t_vec = seconds(raw_t - raw_t(1));
                elseif isnumeric(raw_t) && max(raw_t) > 1e10 
                    t_vec = (raw_t - raw_t(1)) / 1000;
                else
                    t_vec = raw_t - min(raw_t); 
                end
            else
                warning('No explicit time column found. Generating synthetic 1Hz time base.');
                timeColName = 'Synthetic_Time';
                t_vec = (0:height(T)-1)';
            end
            
            dt = mean(diff(t_vec));
            if isnan(dt) || dt <= 0, dt = 0.01; end % Default 100Hz if failed
        end
        
        %% --- UPGRADED: Smart Pagination Plotter ---
        
        function plotStandardTelemetry(T)
            [timeData, ~, timeColName] = OBD_AnalyticsLab.getTimeData(T);
            
            allVars = T.Properties.VariableNames;
            excludeCols = {timeColName, 'Linearized_Time', 'timestampMs', 'Time', 'RelativeTime_s'};
            sensorNames = allVars(~ismember(allVars, excludeCols));
            
            validSensors = {};
            for p = 1:length(sensorNames)
                colData = T.(sensorNames{p});
                if isnumeric(colData) && ~all(isnan(colData))
                    validSensors{end+1} = sensorNames{p};
                end
            end
            
            numPlots = length(validSensors);
            if numPlots == 0, disp('No valid numeric sensor data found to plot.'); return; end
            
            plotsPerWindow = 5; 
            numWindows = ceil(numPlots / plotsPerWindow);
            
            for w = 1:numWindows
                startIdx = (w - 1) * plotsPerWindow + 1;
                endIdx = min(w * plotsPerWindow, numPlots);
                currentPlotCount = endIdx - startIdx + 1;
                
                figHeight = 200 * currentPlotCount; 
                offset = (w-1) * 30; 
                
                figure('Name', sprintf('Standard Telemetry Dashboard (Page %d of %d)', w, numWindows), ...
                       'NumberTitle', 'off', 'Position', [50 + offset, 100 - offset, 1200, figHeight]);
                
                tiledlayout(currentPlotCount, 1, 'TileSpacing', 'compact', 'Padding', 'tight');
                
                for p = startIdx:endIdx
                    sName = validSensors{p};
                    nexttile;
                    
                    yData = T.(sName);
                    plot(timeData, yData, 'LineWidth', 1.5, 'Color', [0 0.4470 0.7410]);
                    
                    cleanName = strrep(sName, '_', ' ');
                    title(cleanName, 'FontSize', 11, 'FontWeight', 'bold');
                    grid on;
                    
                    validY = yData(~isnan(yData));
                    if ~isempty(validY) && all(validY == 0 | validY == 1)
                        ylim([-0.2 1.2]); yticks([0 1]); yticklabels({'OFF', 'ON'});
                    else
                        min_y = min(validY); max_y = max(validY);
                        if min_y ~= max_y
                            ylim([min_y - 0.05*(max_y-min_y), max_y + 0.05*(max_y-min_y)]);
                        end
                    end
                end
                xlabel('Time (seconds)', 'FontSize', 12, 'FontWeight', 'bold');
            end
        end
        
        %% --- FFT & Frequency Analytics ---
        
        function plotFrequencyAnalysis(T, signalName)
            y = T.(signalName);
            y = fillmissing(y, 'linear'); 
            y_ac = y - mean(y);
            
            [~, dt, ~] = OBD_AnalyticsLab.getTimeData(T);
            Fs = 1 / dt;
            L = length(y_ac);
            
            Y = fft(y_ac);
            P2 = abs(Y/L);
            P1 = P2(1:floor(L/2)+1);
            P1(2:end-1) = 2*P1(2:end-1);
            f_fft = Fs*(0:(L/2))/L;
            
            cleanName = strrep(signalName, '_', ' ');
            fig = figure('Name', sprintf('Frequency Analysis: %s', cleanName), 'Color', 'w', 'Position', [100 100 1000 600]);
            tiledlayout(2, 1, 'TileSpacing', 'compact');
            
            ax1 = nexttile;
            plot(ax1, f_fft, P1, 'b', 'LineWidth', 1.5);
            title(ax1, sprintf('Single-Sided FFT Amplitude Spectrum: %s', cleanName), 'FontWeight', 'bold');
            xlabel(ax1, 'Frequency (Hz)'); ylabel(ax1, 'Amplitude');
            grid(ax1, 'on');
            
            ax2 = nexttile;
            try
                [pxx, f_psd] = pwelch(y_ac, [], [], [], Fs);
                plot(ax2, f_psd, 10*log10(pxx), 'r', 'LineWidth', 1.5);
                title(ax2, 'Welch''s Power Spectral Density (PSD)', 'FontWeight', 'bold');
                xlabel(ax2, 'Frequency (Hz)'); ylabel(ax2, 'Power/Frequency (dB/Hz)');
                grid(ax2, 'on');
            catch
                title(ax2, '(Signal Processing Toolbox required for PSD plot)');
                axis(ax2, 'off');
            end
        end
        
        function plotCoherence(T, sig1, sig2)
            y1 = fillmissing(T.(sig1), 'linear'); y2 = fillmissing(T.(sig2), 'linear');
            y1 = y1 - mean(y1); y2 = y2 - mean(y2);
            
            [~, dt, ~] = OBD_AnalyticsLab.getTimeData(T);
            Fs = 1 / dt;
            clean1 = strrep(sig1, '_', ' '); clean2 = strrep(sig2, '_', ' ');
            
            fig = figure('Name', 'Cross-Spectral Coherence', 'Color', 'w', 'Position', [150 150 900 500]);
            try
                [Cxy, f] = mscohere(y1, y2, [], [], [], Fs);
                plot(f, Cxy, 'k', 'LineWidth', 2);
                title(sprintf('Signal Coherence: %s vs. %s', clean1, clean2), 'FontSize', 14, 'FontWeight', 'bold');
                xlabel('Frequency (Hz)'); ylabel('Coherence (0 to 1)');
                grid on; yline(0.5, 'r--', 'Significant Correlation Threshold', 'LabelHorizontalAlignment', 'left');
            catch
                text(0.1, 0.5, 'Signal Processing Toolbox required for Coherence Analysis.', 'FontSize', 12);
                axis off;
            end
        end
        
        %% --- Existing Plotting Functions ---
        
        function plotCorrelationMatrix(T, selectedVars)
            fig = figure('Name', 'Correlation Matrix', 'Color', 'w', 'Position', [100 100 900 700]);
            dataMatrix = T{:, selectedVars};
            cleanRows = ~any(isnan(dataMatrix), 2);
            cleanData = dataMatrix(cleanRows, :);
            if isempty(cleanData), close(fig); return; end
            corrMat = corrcoef(cleanData);
            cleanNames = strrep(selectedVars, '_', ' ');
            heatmap(cleanNames, cleanNames, corrMat, 'Title', 'Cross-Signal Correlation', 'Colormap', jet, 'CellLabelFormat', '%.2f');
        end
        
        function plotEngineHeatmap(T)
            vars = T.Properties.VariableNames;
            
            if ismember('Engine_RPM', vars), rpm = T.Engine_RPM; 
            elseif ismember('obd_rpm', vars), rpm = T.obd_rpm;
            else, return; end
            
            if ismember('Accelerator_Pedal', vars), load = T.Accelerator_Pedal;
            elseif ismember('obd_engineLoadPct', vars), load = T.obd_engineLoadPct;
            else, return; end
                
            fig = figure('Name', 'Engine Operating Range', 'Color', 'w', 'Position', [150 150 800 600]);
            valid = ~isnan(rpm) & ~isnan(load);
            histogram2(rpm(valid), load(valid), [40 40], 'DisplayStyle', 'tile', 'ShowEmptyBins', 'off');
            colormap(jet); cb = colorbar; cb.Label.String = 'Samples';
            title('Engine Heatmap (RPM vs. Load/Pedal)', 'FontSize', 14, 'FontWeight', 'bold'); 
            xlabel('RPM'); ylabel('Load/Pedal (%)');
        end
    end
end