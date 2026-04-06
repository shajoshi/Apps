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

% CALIBRATED STEERING ANGLE: True Zero = 7941, Scale Factor = 10
sensorDict(end+1) = struct('Name', 'Steering_Angle', 'ID', '280', ...
    'Formula', @(b) (((b(:,7) * 256) + b(:,8)) - 7941) / 10, 'Unit', 'deg');

% Steering Velocity (Byte E)
sensorDict(end+1) = struct('Name', 'Steering_Velocity', 'ID', '280', ...
    'Formula', @(b) b(:,5), 'Unit', 'raw');

% Yaw Rate
sensorDict(end+1) = struct('Name', 'Yaw_Rate', 'ID', '3D3', ...
    'Formula', @(b) ((b(:,7) * 256) + b(:,8)) - 1438, 'Unit', 'raw');

% SENSOR: Brake Master Cylinder Pressure
sensorDict(end+1) = struct('Name', 'Brake_Pressure', 'ID', '3EB', ...
    'Formula', @(b) (b(:,5) * 256) + b(:,6), 'Unit', 'raw_pressure');

% SENSOR: Brake Pedal Switch (Boolean 0 or 1)
% We use bitand to isolate the specific bit that flips when you press the pedal
sensorDict(end+1) = struct('Name', 'Brake_Switch', 'ID', '295', ...
    'Formula', @(b) bitand(b(:,7), 32) / 32, 'Unit', 'on/off');

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
figure('Name', 'JLR Master Telemetry', 'Position', [100, 100, 1000, 250 * length(sensorNames)]);
for p = 1:length(sensorNames)
    subplot(length(sensorNames), 1, p);
    plot(DecodedData.Linearized_Time, DecodedData.(sensorNames{p}), 'LineWidth', 1.5);
    title(strrep(sensorNames{p}, '_', ' ')); xlabel('Time (s)'); ylabel(tableUnits{p+1}); grid on;
end
disp('Telemetry loaded successfully.');