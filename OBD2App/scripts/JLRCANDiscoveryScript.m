% =========================================================================
% JLR CAN Log Analyzer: The "Maneuver Hunter" (Discovery Script)
% =========================================================================
clear; clc; close all;

% =========================================================================
% 1. DEFINE YOUR MANEUVER WINDOW HERE!
% Look at the raw time of your log. When did you perform the action?
% Example: I hit the brakes between second 5 and second 9.
maneuver_start_time = 5.0; % seconds
maneuver_end_time = 8.0;   % seconds
% =========================================================================

% --- 2. LOAD FILE ---
defaultPath = 'C:\Program Files (x86)\HUD ECU Hacker\ECU\OBD2\LogFiles\*.htm';
[file, path] = uigetfile(defaultPath, 'Select HUD ECU Hacker Log File');
if isequal(file, 0)
    return;
end
filename = fullfile(path, file);

disp(['Scanning log for signals active between ', num2str(maneuver_start_time), 's and ', num2str(maneuver_end_time), 's...']);
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

% --- 3. THE DISCOVERY ENGINE ---
% We will score every single byte based on how much it changed DURING the 
% maneuver window compared to how quiet it was OUTSIDE the window.
results = struct('ID', {}, 'ByteIndex', {}, 'Score', {});

allIDs = keys(canMap);
for k = 1:length(allIDs)
    id = allIDs{k};
    data = canMap(id);
    time = data(:,1);
    
    % Find indices for inside and outside the maneuver window
    idx_in = find(time >= maneuver_start_time & time <= maneuver_end_time);
    idx_out = find(time < maneuver_start_time | time > maneuver_end_time);
    
    % If we don't have enough data inside or outside, skip this ID
    if isempty(idx_in) || isempty(idx_out)
        continue;
    end
    
    % Loop through bytes A(2) to H(9)
    for b = 2:9 
        byteData = data(:, b);
        
        % Calculate range (max - min) inside and outside the window
        range_in = max(byteData(idx_in)) - min(byteData(idx_in));
        range_out = max(byteData(idx_out)) - min(byteData(idx_out));
        
        % We want bytes that spiked inside the window, but were quiet outside.
        % We also ignore bytes that act like rolling counters (range > 200)
        if range_in > 0 && max(byteData) < 250
            score = range_in - range_out;
            if score > 5 % Only log significant correlations
                results(end+1) = struct('ID', id, 'ByteIndex', b-1, 'Score', score);
            end
        end
    end
end

% --- 4. SORT AND PLOT TOP MATCHES ---
if isempty(results)
    disp('No obvious sensors correlated with that specific time window.');
    return;
end

% Sort results by Highest Score
T = struct2table(results);
T_sorted = sortrows(T, 'Score', 'descend');
top_matches = table2struct(T_sorted);

% Limit to top 6 to fit on screen
num_plots = min(6, length(top_matches));
byteLetters = {'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H'};

figure('Name', 'Sensor Discovery: Top Matches', 'Position', [150, 150, 1000, 800]);
for p = 1:num_plots
    match = top_matches(p);
    data = canMap(match.ID);
    time = data(:,1);
    byteVals = data(:, match.ByteIndex + 1);
    
    subplot(num_plots, 1, p);
    plot(time, byteVals, 'k-', 'LineWidth', 1.2);
    hold on;
    
    % Highlight the Maneuver Window in RED
    ylims = ylim;
    patch([maneuver_start_time maneuver_end_time maneuver_end_time maneuver_start_time], ...
          [ylims(1) ylims(1) ylims(2) ylims(2)], 'r', 'FaceAlpha', 0.1, 'EdgeColor', 'none');
      
    title(['Suspect Sensor: ID ' match.ID ' | Byte ' byteLetters{match.ByteIndex} ' (Score: ' num2str(match.Score) ')']);
    xlabel('Time (s)'); ylabel('Raw Value'); grid on;
end

disp('Discovery Complete! Check the plots. The shaded red area is your maneuver window.');