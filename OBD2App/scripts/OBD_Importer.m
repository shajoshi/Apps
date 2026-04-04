classdef OBD_Importer
    % OBD_Importer: A Schema-Less JSON-to-Timetable ingestion engine.
    % Automatically detects standard and custom PIDs, flattens the nested 
    % JSON structure, and synchronizes everything to a master timeline.
    
    methods (Static)
        
        function [T, Profile] = read(filename)
            % READ Parses the OBD2App JSON file into a MATLAB timetable
            %
            % Usage:
            %   [TripData, Profile] = OBD_Importer.read('track_file.json');
            
            if nargin < 1 || isempty(filename)
                [file, path] = uigetfile('*.json', 'Select the OBD JSON log file');
                if isequal(file, 0)
                    error('File selection canceled.');
                end
                filename = fullfile(path, file);
            end
            
            fprintf('Ingesting JSON Data: %s...\n', filename);
            
            % 1. Decode the JSON
            rawStr = fileread(filename);
            data = jsondecode(rawStr);
            
            header = data.header;
            samples = data.samples;
            
            % Extract Profile cleanly
            if isfield(header, 'vehicleProfile')
                Profile = header.vehicleProfile;
            else
                Profile = struct();
            end
            
            % Handle cases where jsondecode returns a struct array vs cell array
            if isstruct(samples)
                samples = num2cell(samples);
            end
            
            numSamples = length(samples);
            if numSamples == 0
                error('No samples found in the JSON file.');
            end
            
            fprintf('Flattening %d samples (Dynamic Schema Discovery)...\n', numSamples);
            
            % 2. First Pass: Discover all unique schema fields
            flatSamples = cell(numSamples, 1);
            allFields = {};
            
            for i = 1:numSamples
                flatS = OBD_Importer.flattenStruct(samples{i}, '');
                flatSamples{i} = flatS;
                allFields = unique([allFields; fieldnames(flatS)], 'stable');
            end
            
            % Isolate the time variable
            timeIdx = strcmp(allFields, 'timestampMs');
            dataFields = allFields(~timeIdx);
            
            validDataFields = {};
            
            % 3. Preallocate the Data Matrix
            dataMatrix = NaN(numSamples, length(dataFields));
            timeArrayRaw = NaN(numSamples, 1); % Store raw ms to avoid TimeZone conflict
            
            % 4. Populate Matrix
            for i = 1:numSamples
                s = flatSamples{i};
                
                % Extract master time in raw milliseconds
                if isfield(s, 'timestampMs')
                    timeArrayRaw(i) = s.timestampMs;
                end
                
                % Extract variables
                for j = 1:length(dataFields)
                    fn = dataFields{j};
                    if isfield(s, fn) && ~isempty(s.(fn))
                        val = s.(fn);
                        if isnumeric(val) || islogical(val)
                            dataMatrix(i, j) = double(val(1));
                            
                            % Track that this field actually contains numeric data
                            if ~ismember(fn, validDataFields)
                                validDataFields{end+1} = fn; %#ok<AGROW>
                            end
                        end
                    end
                end
            end
            
            % Remove columns that ended up having absolutely no numeric data
            [~, validIdx] = ismember(validDataFields, dataFields);
            cleanedMatrix = dataMatrix(:, validIdx);
            
            % 5. Build the Timetable
            fprintf('Synchronizing Timetable...\n');
            
            % Vectorized Time Conversion (Fixes the TimeZone Array error)
            timeArray = datetime(timeArrayRaw / 1000, 'ConvertFrom', 'posixtime', 'TimeZone', 'local');
            
            T = array2timetable(cleanedMatrix, 'RowTimes', timeArray, 'VariableNames', validDataFields);
            
            % Sort by time just in case Bluetooth packets arrived out of order
            T = sortrows(T);
            
            fprintf('✓ Successfully imported %d variables.\n', length(validDataFields));
        end
        
        %% --- Internal Helper Functions ---
        
        function flatStruct = flattenStruct(s, prefix)
            % Recursively navigates nested structures (e.g., gps.lat -> gps_lat)
            flatStruct = struct();
            fields = fieldnames(s);
            
            for i = 1:length(fields)
                f = fields{i};
                val = s.(f);
                
                if isempty(prefix)
                    newKey = f;
                else
                    newKey = [prefix, '_', f];
                end
                
                if isstruct(val) && ~isempty(val)
                    % Recursion for nested objects
                    subFlat = OBD_Importer.flattenStruct(val, newKey);
                    subFields = fieldnames(subFlat);
                    for j = 1:length(subFields)
                        flatStruct.(subFields{j}) = subFlat.(subFields{j});
                    end
                else
                    % Base case: assign the value
                    flatStruct.(newKey) = val;
                end
            end
        end
        
    end
end