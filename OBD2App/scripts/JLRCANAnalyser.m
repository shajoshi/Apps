% =========================================================================
% JLR CAN Log Analyzer: Master Telemetry (Calibrated)
% =========================================================================
clear; clc; close all;

% --- 1. CONFIGURATION & FILE PICKER ---
defaultPath = 'C:\Program Files (x86)\HUD ECU Hacker\ECU\OBD2\LogFiles\*.htm';
[file, path] = uigetfile(defaultPath, 'Select HUD ECU Hacker Log File');
if isequal(file, 0)
    disp('User canceled.'); return;
end
filename = fullfile(path, file);

% --- 2. MODULAR SENSOR DICTIONARY (CALIBRATED) ---
sensorDict = struct('Name', {}, 'ID', {}, 'Formula', {}, 'Unit', {});

% === CHASSIS DYNAMICS ===
% CALIBRATED STEERING ANGLE: True Zero = 7941, Scale Factor = 10
sensorDict(end+1) = struct('Name', 'Steering_Angle', 'ID', '280', ...
    'Formula', @(b) (((b(:,7) * 256) + b(:,8)) - 7941) / 10, 'Unit', 'deg');

% Steering Velocity (Byte E)
sensorDict(end+1) = struct('Name', 'Steering_Velocity', 'ID', '280', ...
    'Formula', @(b) b(:,5), 'Unit', 'raw');

% Yaw Rate
sensorDict(end+1) = struct('Name', 'Yaw_Rate', 'ID', '3D3', ...
    'Formula', @(b) ((b(:,7) * 256) + b(:,8)) - 1438, 'Unit', 'raw');

% === BRAKING SYSTEM ===
% Brake Master Cylinder Pressure
sensorDict(end+1) = struct('Name', 'Brake_Pressure', 'ID', '3EB', ...
    'Formula', @(b) (b(:,5) * 256) + b(:,6), 'Unit', 'raw_pressure');

% Brake Pedal Switch (Boolean 0 or 1)
sensorDict(end+1) = struct('Name', 'Brake_Switch', 'ID', '295', ...
    'Formula', @(b) bitand(b(:,7), 32) / 32, 'Unit', 'on/off');

% === STANDARD OBD POWERTRAIN CHANNELS ===
% Engine RPM (Masks out the brake switch data sharing the same byte)
sensorDict(end+1) = struct('Name', 'Engine_RPM', 'ID', '295', ...
    'Formula', @(b) ((bitand(b(:,7), 31) * 256) + b(:,8)) / 4, 'Unit', 'RPM');

% Vehicle Speed
sensorDict(end+1) = struct('Name', 'Vehicle_Speed', 'ID', '29B', ...
    'Formula', @(b) ((b(:,7) * 256) + b(:,8)) / 100, 'Unit', 'km/h');

% Engine Coolant Temperature
sensorDict(end+1) = struct('Name', 'Coolant_Temp', 'ID', '355', ...
    'Formula', @(b) b(:,6), 'Unit', 'raw_temp');

% Fuel Level
sensorDict(end+1) = struct('Name', 'Fuel_Level', 'ID', '477', ...
    'Formula', @(b) (b(:,1) * 256) + b(:,2), 'Unit', 'raw_fuel');

% === PROPRIETARY BODY & MAINTENANCE SENSORS ===
% DSC Drive Mode (1 = DSC On, 2 = TracDSC, 3 = DSC Off)
sensorDict(end+1) = struct('Name', 'DSC_Drive_Mode', 'ID', '3EB', ...
    'Formula', @(b) b(:,2), 'Unit', 'mode_id');

% Door Matrix (Binary Bitmasks)
sensorDict(end+1) = struct('Name', 'Door_FrontLeft', 'ID', '337', ...
    'Formula', @(b) bitand(b(:,3), 1), 'Unit', 'bool');
sensorDict(end+1) = struct('Name', 'Door_FrontRight', 'ID', '337', ...
    'Formula', @(b) bitand(b(:,3), 2) / 2, 'Unit', 'bool');
sensorDict(end+1) = struct('Name', 'Trunk_Open', 'ID', '337', ...
    'Formula', @(b) bitand(b(:,3), 16) / 16, 'Unit', 'bool');

% MS-CAN Sensors (Tire Pressure & Wear)
sensorDict(end+1) = struct('Name', 'Tire_Pressure_1', 'ID', '4E8', ...
    'Formula', @(b) b(:,2), 'Unit', 'raw');
sensorDict(end+1) = struct('Name', 'Brake_Pads_Worn', 'ID', '4E3', ...
    'Formula', @(b) bitand(b(:,7), 2) / 2, 'Unit', 'bool');


% --- 3. PARSE HTML & LINEARIZE ---
disp('Loading log file...');
filetext = fileread(filename);
pattern = '>(\d{2}:\d{2}:\d{2}\.\d{3})</span><span class=''RxData''>([0-9A-F]{3}):\s+([0-9A-F\s]+)</span>';
tokens = regexp(filetext, pattern, 'tokens');

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

% Extract time vector safely
lastTime = datetime(tokens{end}{1}, 'InputFormat', 'HH:mm:ss.SSS');
Linearized_Time = (0 : 0.01 : seconds(lastTime - t0))';
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
        tableUnits{end+1} = sensor.Unit;
    end
end
DecodedData.Properties.VariableUnits = tableUnits;

% --- 4. PLOT ---
sensorNames = DecodedData.Properties.VariableNames(2:end);

% Calculate a clean figure height based on the number of sensors actually found
numPlots = length(sensorNames);
figHeight = min(1000, 200 * numPlots); % Caps height so it doesn't run off screen

figure('Name', 'JLR Master Telemetry', 'Position', [100, 100, 1000, figHeight]);
for p = 1:numPlots
    subplot(numPlots, 1, p);
    plot(DecodedData.Linearized_Time, DecodedData.(sensorNames{p}), 'LineWidth', 1.5);
    
    title(strrep(sensorNames{p}, '_', ' ')); 
    xlabel('Time (s)'); 
    ylabel(tableUnits{p+1}); 
    grid on;
    
    % Format Boolean plots nicely
    if strcmp(tableUnits{p+1}, 'bool') || strcmp(tableUnits{p+1}, 'on/off')
        ylim([-0.1 1.5]);
        yticks([0 1]);
    end
end
disp('Telemetry loaded successfully. Check workspace for DecodedData table.');