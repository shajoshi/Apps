% =========================================================================
% JLR CAN Log Analyzer: The Ultimate Master Telemetry Plotter (V6)
% Optimized for 2015 Jaguar XF (EUCD Architecture)
% Corrected: Wheel Speeds (0.01), Odometer (0.1), and OBD Diagnostics
% =========================================================================
clear; clc; close all;

% --- 1. CONFIGURATION & FILE PICKER ---
defaultPath = 'C:\Program Files (x86)\HUD ECU Hacker\ECU\OBD2\LogFiles\*.htm';
[file, path] = uigetfile(defaultPath, 'Select HUD ECU Hacker Log File');
if isequal(file, 0)
    disp('User canceled file selection.'); return;
end
filename = fullfile(path, file);

% --- 2. MODULAR SENSOR DICTIONARY (THE MASTER LIST) ---
sensorDict = struct('Name', {}, 'ID', {}, 'Formula', {}, 'Unit', {});

% === CHASSIS DYNAMICS & IMU ===
sensorDict(end+1) = struct('Name', 'Steering_Angle', 'ID', '280', ...
    'Formula', @(b) (((b(:,7) * 256) + b(:,8)) - 7941) / 10, 'Unit', 'deg');
sensorDict(end+1) = struct('Name', 'Yaw_Rate', 'ID', '3D3', ...
    'Formula', @(b) (((b(:,7) * 256) + b(:,8)) - 1438) / 10, 'Unit', 'deg/s');
sensorDict(end+1) = struct('Name', 'Accel_Longitudinal', 'ID', '312', ...
    'Formula', @(b) (b(:,1) * 256) + b(:,2), 'Unit', 'raw_G');
sensorDict(end+1) = struct('Name', 'Accel_Lateral', 'ID', '312', ...
    'Formula', @(b) (b(:,3) * 256) + b(:,4), 'Unit', 'raw_G');

% === DRIVER INPUTS ===
sensorDict(end+1) = struct('Name', 'Accelerator_Pedal', 'ID', '2CB', ...
    'Formula', @(b) b(:,2) / 2.55, 'Unit', '%');
sensorDict(end+1) = struct('Name', 'Brake_Pressure', 'ID', '3EB', ...
    'Formula', @(b) ((b(:,5) * 256) + b(:,6)) / 100, 'Unit', 'Bar');
sensorDict(end+1) = struct('Name', 'Brake_Pedal_Switch', 'ID', '295', ...
    'Formula', @(b) bitand(b(:,7), 32) / 32, 'Unit', 'bool');

% === WHEEL SPEEDS (CORRECTED SCALING) ===
% JLR Precision scaling is 0.01 per bit for individual wheel speeds
sensorDict(end+1) = struct('Name', 'Wheel_Speed_FL', 'ID', '215', ...
    'Formula', @(b) ((bitand(b(:,1), 15) * 256) + b(:,2)) * 0.01, 'Unit', 'km/h');
sensorDict(end+1) = struct('Name', 'Wheel_Speed_FR', 'ID', '215', ...
    'Formula', @(b) ((bitand(b(:,3), 15) * 256) + b(:,4)) * 0.01, 'Unit', 'km/h');
sensorDict(end+1) = struct('Name', 'Wheel_Speed_RL', 'ID', '215', ...
    'Formula', @(b) ((bitand(b(:,5), 15) * 256) + b(:,6)) * 0.01, 'Unit', 'km/h');
sensorDict(end+1) = struct('Name', 'Wheel_Speed_RR', 'ID', '215', ...
    'Formula', @(b) ((bitand(b(:,7), 15) * 256) + b(:,8)) * 0.01, 'Unit', 'km/h');

% === POWERTRAIN & DIAGNOSTICS ===
sensorDict(end+1) = struct('Name', 'Engine_RPM', 'ID', '295', ...
    'Formula', @(b) (bitand(b(:,7), 31) * 256) + b(:,8), 'Unit', 'RPM');
sensorDict(end+1) = struct('Name', 'Vehicle_Speed', 'ID', '29B', ...
    'Formula', @(b) ((b(:,7) * 256) + b(:,8)) / 100, 'Unit', 'km/h');
sensorDict(end+1) = struct('Name', 'Coolant_Temp', 'ID', '355', ...
    'Formula', @(b) b(:,6) - 40, 'Unit', '°C');
sensorDict(end+1) = struct('Name', 'Fuel_Level', 'ID', '477', ...
    'Formula', @(b) b(:,1) / 2.55, 'Unit', '%');
sensorDict(end+1) = struct('Name', 'Ambient_Temp', 'ID', '4E1', ...
    'Formula', @(b) (b(:,7) / 4) - 40, 'Unit', '°C');
sensorDict(end+1) = struct('Name', 'Engine_Torque_Nm', 'ID', '20B', ...
    'Formula', @(b) ((b(:,5) * 256) + b(:,6)) - 1000, 'Unit', 'Nm');

% === SYSTEM FAULTS & STATUS ===
sensorDict(end+1) = struct('Name', 'Gearbox_Fault_State', 'ID', '3F3', 'Formula', @(b) b(:,7), 'Unit', 'fault_code');
sensorDict(end+1) = struct('Name', 'Limp_Mode_Yellow', 'ID', '315', 'Formula', @(b) bitand(b(:,2), 2) / 2, 'Unit', 'bool');
sensorDict(end+1) = struct('Name', 'Oil_Warning', 'ID', '315', 'Formula', @(b) bitand(b(:,4), 16) / 16, 'Unit', 'bool');
sensorDict(end+1) = struct('Name', 'Seatbelt_Warning', 'ID', '39A', 'Formula', @(b) bitand(b(:,7), 48) / 48, 'Unit', 'bool');

% === ODOMETER (CORRECTED) ===
% Scale of 0.1km found in cluster documentation for EUCD platform
sensorDict(end+1) = struct('Name', 'Odometer', 'ID', '4C0', ...
    'Formula', @(b) ((b(:,5) * 65536) + (b(:,6) * 256) + b(:,7)) / 10, 'Unit', 'km');

% --- 3. PARSE HTML LOG ---
disp(['Loading log file: ', filename]);
filetext = fileread(filename);
pattern = '>(\d{2}:\d{2}:\d{2}\.\d{3})</span><span class=''RxData''>([0-9A-F]{3}):\s+([0-9A-F\s]+)</span>';
tokens = regexp(filetext, pattern, 'tokens');

if isempty(tokens)
    error('No valid CAN data found in this file.');
end

% --- 4. EXTRACT RAW DATA INTO MAP ---
canMap = containers.Map('KeyType', 'char', 'ValueType', 'any');
t0 = datetime(tokens{1}{1}, 'InputFormat', 'HH:mm:ss.SSS');

for i = 1:length(tokens)
    t_curr = datetime(tokens{i}{1}, 'InputFormat', 'HH:mm:ss.SSS');
    time_sec = seconds(t_curr - t0);
    id = tokens{i}{2};
    byteCells = strsplit(strtrim(tokens{i}{3}));
    
    if length(byteCells) == 8
        rowData = [time_sec, hex2dec(byteCells)']; 
        if isKey(canMap, id)
            canMap(id) = [canMap(id); rowData];
        else
            canMap(id) = rowData;
        end
    end
end

% --- 5. SYNCHRONIZE & LINEARIZE TIME BASE ---
disp('Synchronizing CAN frames to linear time base...');
lastTime = datetime(tokens{end}{1}, 'InputFormat', 'HH:mm:ss.SSS');
t_max = seconds(lastTime - t0);

Linearized_Time = (0 : 0.01 : t_max)';
DecodedData = table(Linearized_Time);
tableUnits = {'seconds'}; 

for s = 1:length(sensorDict)
    sensor = sensorDict(s);
    if isKey(canMap, sensor.ID)
        rawTbl = canMap(sensor.ID);
        raw_t = rawTbl(:,1);
        raw_vals = sensor.Formula(rawTbl(:, 2:9));
        
        [uTime, uIdx] = unique(raw_t, 'stable');
        if length(uTime) > 1
            DecodedData.(sensor.Name) = interp1(uTime, raw_vals(uIdx), Linearized_Time, 'previous', 'extrap');
        else
            DecodedData.(sensor.Name) = nan(size(Linearized_Time));
        end
    else
        DecodedData.(sensor.Name) = nan(size(Linearized_Time));
    end
    tableUnits{end+1} = sensor.Unit;
end

DecodedData.Properties.VariableUnits = tableUnits;

% --- 6. PLOT ONLY THE SENSORS FOUND IN THIS SPECIFIC LOG ---
sensorNames = DecodedData.Properties.VariableNames(2:end);
validSensors = {};
for p = 1:length(sensorNames)
    if ~all(isnan(DecodedData.(sensorNames{p})))
        validSensors{end+1} = sensorNames{p};
    end
end

numPlots = length(validSensors);

if numPlots > 0
    figHeight = min(2000, 180 * numPlots); 
    figure('Name', 'JLR Master Telemetry V6', 'NumberTitle', 'off', 'Position', [100, 50, 1200, 900]);
    
    % Grouping logic: Use Scrollable UI if many sensors are found
    tiledlayout(numPlots, 1, 'TileSpacing', 'compact', 'Padding', 'tight');
    
    for p = 1:numPlots
        sName = validSensors{p};
        nexttile;
        
        plot(DecodedData.Linearized_Time, DecodedData.(sName), 'LineWidth', 1.2);
        
        unitIdx = find(strcmp(DecodedData.Properties.VariableNames, sName));
        unitStr = DecodedData.Properties.VariableUnits{unitIdx};
        
        title(strrep(sName, '_', ' '), 'FontSize', 10);
        ylabel(unitStr);
        grid on;
        
        if strcmp(unitStr, 'bool')
            ylim([-0.2 1.2]); yticks([0 1]); yticklabels({'OFF', 'ON'});
        end
    end
    xlabel('Time (s)');
    disp(['Successfully plotted ' num2str(numPlots) ' sensors.']);
else
    disp('No known sensors from the dictionary were found in this log file.');
end

disp('======================================================');
disp('See "DecodedData" in the workspace for the synchronized CSV-ready table.');