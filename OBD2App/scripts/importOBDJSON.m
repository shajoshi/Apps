function T = importOBDJSON(filename)
    % importOBDJSON Imports OBD log JSON file into a flattened MATLAB Table.
    % If no filename is provided, it opens a UI window to select one.
    
    % 1. Prompt for file if not provided
    if nargin < 1 || isempty(filename)
        [file, path] = uigetfile('*.json', 'Select the OBD JSON log file');
        if isequal(file, 0)
            disp('File selection cancelled.');
            T = table(); % Return empty table
            return;
        end
        filename = fullfile(path, file);
    end
    
    % 2. Read and decode the JSON file
    rawStr = fileread(filename);
    data = jsondecode(rawStr);
    
    % Extract the samples array
    samples = data.samples;
    if isstruct(samples)
        samples = num2cell(samples);
    end
    
    % 3. Flatten nested structures for each sample
    numSamples = numel(samples);
    flattenedData = cell(numSamples, 1);
    allFields = {};
    
    for i = 1:numSamples
        flatS = flattenStruct(samples{i}, '');
        flattenedData{i} = flatS;
        allFields = unique([allFields; fieldnames(flatS)], 'stable');
    end
    
    % 4. Preallocate a struct array with NaNs to handle varying lengths/fields
    assembledStruct = struct();
    for f = 1:numel(allFields)
        fn = allFields{f};
        assembledStruct.(fn) = NaN(numSamples, 1);
    end
    
    % 5. Populate the assembled struct
    for i = 1:numSamples
        currentFields = fieldnames(flattenedData{i});
        for f = 1:numel(currentFields)
            fn = currentFields{f};
            val = flattenedData{i}.(fn);
            
            if isempty(val)
                assembledStruct.(fn)(i) = NaN;
            elseif ischar(val) || isstring(val)
                if isnumeric(assembledStruct.(fn)) 
                    assembledStruct.(fn) = strings(numSamples, 1);
                end
                assembledStruct.(fn)(i) = string(val);
            else
                assembledStruct.(fn)(i) = double(val(1)); 
            end
        end
    end
    
    % 6. Convert to table
    T = struct2table(assembledStruct);
    
    % 7. Generate a proper Time Channel
    % Check if 'timestampMs' exists in the imported fields
    if ismember('timestampMs', T.Properties.VariableNames)
        % Convert milliseconds since Unix epoch to MATLAB datetime
        T.Time = datetime(T.timestampMs / 1000, 'ConvertFrom', 'posixtime', 'TimeZone', 'local');
        
        % Move the new 'Time' column to be the very first column in the table
        T = movevars(T, 'Time', 'Before', 1);
    end
end

%% Helper Function to recursively flatten nested structs
function flatStruct = flattenStruct(s, prefix)
    flatStruct = struct();
    fields = fieldnames(s);
    
    for i = 1:numel(fields)
        fn = fields{i};
        val = s.(fn);
        
        if isempty(prefix)
            newFn = fn;
        else
            newFn = [prefix, '_', fn];
        end
        
        if isstruct(val)
            nestedFlat = flattenStruct(val, newFn);
            nestedFields = fieldnames(nestedFlat);
            for j = 1:numel(nestedFields)
                flatStruct.(nestedFields{j}) = nestedFlat.(nestedFields{j});
            end
        else
            flatStruct.(newFn) = val;
        end
    end
end