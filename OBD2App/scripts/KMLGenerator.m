classdef KMLGenerator
    % KMLGenerator Generates KML files for track visualization.
    % Features: True 3D Altitude, Google Earth Time Slider, and Interactive UI.
    
    methods (Static)
        
        %% --- NEW INTERACTIVE UI "FRONT DOOR" ---
        function run(filename)
            % RUN Interactive wrapper. Prompts for a file if none is provided.
            % Usage: KMLGenerator.run() 
            
            % 1. Prompt for file if not provided
            if nargin < 1 || isempty(filename)
                [file, path] = uigetfile('*.json', 'Select the OBD JSON log file');
                if isequal(file, 0)
                    disp('File selection canceled.');
                    return;
                end
                filename = fullfile(path, file);
            else
                [path, name, ext] = fileparts(filename);
                file = [name, ext];
                if isempty(path), path = pwd; end
            end
            
            % 2. Read and decode the JSON file
            fprintf('Loading data from %s...\n', file);
            try
                rawStr = fileread(filename);
                data = jsondecode(rawStr);
                samples = data.samples;
                if isstruct(samples)
                    samples = num2cell(samples);
                end
            catch ME
                error('Failed to parse JSON file. Ensure it is formatted correctly.\n%s', ME.message);
            end
            
            % 3. Determine a clean track name (from filename)
            [~, track_name, ~] = fileparts(file);
            
            % 4. Ask the user what kind of Map they want to generate
            mapType = questdlg('What type of KML map would you like to generate?', ...
                               'KML Generator Options', ...
                               'Metric Color Track', 'Drive Modes Map', 'Cancel', 'Metric Color Track');
                           
            if isempty(mapType) || strcmp(mapType, 'Cancel')
                disp('KML Generation canceled.');
                return;
            end
            
            % 5. Execute based on choice
            if strcmp(mapType, 'Drive Modes Map')
                KMLGenerator.create_drive_mode_kml(samples, track_name, path);
                
            elseif strcmp(mapType, 'Metric Color Track')
                % If they want a metric track, ask which metric!
                metrics = {'speed', 'fuel_efficiency', 'rpm'};
                [idx, tf] = listdlg('PromptString', 'Select the metric to map:', ...
                                    'SelectionMode', 'single', ...
                                    'ListString', metrics, ...
                                    'Name', 'Metric Selection', ...
                                    'ListSize', [200 100]);
                if ~tf
                    disp('KML Generation canceled.');
                    return;
                end
                
                selected_metric = metrics{idx};
                KMLGenerator.create_track_kml(samples, track_name, selected_metric, path);
            end
        end

        %% --- CORE KML LOGIC ---
        
        function create_track_kml(samples, track_name, metric, output_dir)
            % CREATE_TRACK_KML Creates KML file with color-coded track segments.
            if nargin < 4, output_dir = pwd; end
            if nargin < 3, metric = 'speed'; end
            
            % Filter samples with valid GPS data
            validIdx = cellfun(@(s) isfield(s, 'gps') && ~isempty(s.gps) && ...
                                    isfield(s.gps, 'lat') && isfield(s.gps, 'lon'), samples);
            gps_samples = samples(validIdx);
            
            if length(gps_samples) < 2
                fprintf('Warning: Not enough GPS data for KML generation in %s\n', track_name);
                return;
            end
            
            % Extract metric values
            metric_values = KMLGenerator.extract_metric_values(gps_samples, metric);
            
            % Filter out NaNs to calculate thresholds
            valid_vals = metric_values(~isnan(metric_values));
            if isempty(valid_vals)
                fprintf('Warning: No valid %s data available for KML generation in %s\n', metric, track_name);
                return;
            end
            
            % Calculate color ranges (33rd and 67th percentiles)
            low_threshold = prctile(valid_vals, 33);
            high_threshold = prctile(valid_vals, 67);
            
            output_path = fullfile(output_dir, sprintf('%s_%s.kml', track_name, metric));
            fid = fopen(output_path, 'w', 'n', 'utf-8');
            if fid == -1, error('Cannot open file for writing: %s', output_path); end
            
            % Write KML Header
            fprintf(fid, '<?xml version="1.0" encoding="UTF-8"?>\n');
            fprintf(fid, '<kml xmlns="http://www.opengis.net/kml/2.2">\n<Document>\n');
            fprintf(fid, '<name>%s_%s</name>\n', track_name, metric);
            
            % Description
            desc = sprintf('%s ranges: Red <= %.2f, Yellow %.2f-%.2f, Green >= %.2f', ...
                           upper(metric(1)), low_threshold, low_threshold, high_threshold, high_threshold);
            fprintf(fid, '<description>%s</description>\n', desc);
            
            % Styles
            KMLGenerator.create_styles(fid);
            
            % Track Segments
            fprintf(fid, '<Folder><name>Track Segments</name>\n');
            
            current_color = '';
            current_coords = {};
            current_value = NaN;
            start_time = '';
            
            for i = 1:length(gps_samples)
                val = metric_values(i);
                if isnan(val), continue; end
                
                % Determine color
                if val <= low_threshold
                    color = 'red'; style_id = '#redStyle';
                elseif val <= high_threshold
                    color = 'yellow'; style_id = '#yellowStyle';
                else
                    color = 'green'; style_id = '#greenStyle';
                end
                
                % Coordinate string with 3D altitude if available
                coordStr = KMLGenerator.get_coord_string(gps_samples{i});
                timestampIso = KMLGenerator.get_iso_time(gps_samples{i});
                
                % Segment change logic
                if ~strcmp(color, current_color) || i == length(gps_samples)
                    if ~isempty(current_coords) && ~isempty(current_color)
                        segName = sprintf('%s (%.2f)', current_color, current_value);
                        KMLGenerator.create_placemark(fid, current_coords, segName, prev_style_id, start_time, timestampIso);
                    end
                    current_color = color;
                    current_value = val;
                    current_coords = {};
                    start_time = timestampIso;
                    prev_style_id = style_id;
                end
                
                current_coords{end+1} = coordStr; %#ok<AGROW>
                
                % Save last segment boundary
                if i == length(gps_samples) && ~isempty(current_coords)
                    segName = sprintf('%s (%.2f)', current_color, current_value);
                    KMLGenerator.create_placemark(fid, current_coords, segName, prev_style_id, start_time, timestampIso);
                end
            end
            
            fprintf(fid, '</Folder>\n</Document>\n</kml>\n');
            fclose(fid);
            fprintf('✓ Generated KML: %s\n', output_path);
        end
        
        function create_drive_mode_kml(samples, track_name, output_dir)
            % CREATE_DRIVE_MODE_KML Creates KML file colored by drive mode (idle/city/highway).
            if nargin < 3, output_dir = pwd; end
            
            validIdx = cellfun(@(s) isfield(s, 'gps') && ~isempty(s.gps) && ...
                                    isfield(s.gps, 'lat') && isfield(s.gps, 'lon'), samples);
            gps_samples = samples(validIdx);
            
            if length(gps_samples) < 2
                fprintf('Warning: Not enough GPS data for drive mode KML in %s\n', track_name);
                return;
            end
            
            output_path = fullfile(output_dir, sprintf('%s_drive_mode.kml', track_name));
            fid = fopen(output_path, 'w', 'n', 'utf-8');
            
            fprintf(fid, '<?xml version="1.0" encoding="UTF-8"?>\n');
            fprintf(fid, '<kml xmlns="http://www.opengis.net/kml/2.2">\n<Document>\n');
            fprintf(fid, '<name>%s_drive_mode</name>\n', track_name);
            fprintf(fid, '<description>Drive modes: Red = Idle (&lt;=2 km/h), Yellow = City (2-60 km/h), Green = Highway (&gt;60 km/h)</description>\n');
            
            % Use same style generator
            KMLGenerator.create_styles(fid);
            fprintf(fid, '<Folder><name>Drive Mode Segments</name>\n');
            
            current_mode = '';
            current_coords = {};
            start_time = '';
            
            for i = 1:length(gps_samples)
                sample = gps_samples{i};
                speed = KMLGenerator.get_hybrid_speed(sample);
                
                if speed <= 2.0
                    mode = 'Idle'; style_id = '#redStyle';
                elseif speed <= 60.0
                    mode = 'City'; style_id = '#yellowStyle';
                else
                    mode = 'Highway'; style_id = '#greenStyle';
                end
                
                coordStr = KMLGenerator.get_coord_string(sample);
                timestampIso = KMLGenerator.get_iso_time(sample);
                
                if ~strcmp(mode, current_mode) || i == length(gps_samples)
                    if ~isempty(current_coords) && ~isempty(current_mode)
                        KMLGenerator.create_placemark(fid, current_coords, current_mode, prev_style_id, start_time, timestampIso);
                    end
                    current_mode = mode;
                    current_coords = {};
                    start_time = timestampIso;
                    prev_style_id = style_id;
                end
                
                current_coords{end+1} = coordStr; %#ok<AGROW>
                
                if i == length(gps_samples) && ~isempty(current_coords)
                    KMLGenerator.create_placemark(fid, current_coords, current_mode, prev_style_id, start_time, timestampIso);
                end
            end
            
            fprintf(fid, '</Folder>\n</Document>\n</kml>\n');
            fclose(fid);
            fprintf('✓ Generated drive mode KML: %s\n', output_path);
        end
        
        %% --- Helper Methods ---
        
        function vals = extract_metric_values(samples, metric)
            vals = NaN(length(samples), 1);
            for i = 1:length(samples)
                sample = samples{i};
                if strcmp(metric, 'speed')
                    vals(i) = KMLGenerator.get_hybrid_speed(sample);
                elseif strcmp(metric, 'fuel_efficiency')
                    if isfield(sample, 'obd') && isfield(sample.obd, 'fuelRateEffectiveLh')
                        speed = KMLGenerator.get_hybrid_speed(sample);
                        if speed > 0
                            % L/100km = (L/h / km/h) * 100
                            vals(i) = (sample.obd.fuelRateEffectiveLh / speed) * 100.0;
                        end
                    end
                elseif strcmp(metric, 'rpm')
                    if isfield(sample, 'obd') && isfield(sample.obd, 'rpm')
                        vals(i) = sample.obd.rpm;
                    end
                end
            end
        end
        
        function spd = get_hybrid_speed(sample)
            % GET_HYBRID_SPEED Hybrid logic: OBD <= 20 km/h, GPS > 20 km/h
            gps_spd = NaN; obd_spd = NaN;
            if isfield(sample.gps, 'speedKmh'), gps_spd = sample.gps.speedKmh; end
            if isfield(sample, 'obd') && isfield(sample.obd, 'speedKmh'), obd_spd = sample.obd.speedKmh; end
            
            if ~isnan(obd_spd) && obd_spd <= 20.0
                spd = obd_spd;
            elseif ~isnan(gps_spd)
                spd = gps_spd;
            elseif ~isnan(obd_spd)
                spd = obd_spd;
            else
                spd = 0.0;
            end
        end
        
        function coordStr = get_coord_string(sample)
            % Gets Lon,Lat,Alt. Uses altMsl for 3D extrusion if available.
            alt = 0;
            if isfield(sample.gps, 'altMsl')
                alt = sample.gps.altMsl;
            end
            coordStr = sprintf('%.6f,%.6f,%.1f', sample.gps.lon, sample.gps.lat, alt);
        end
        
        function isoTime = get_iso_time(sample)
            % Converts timestampMs to ISO 8601 string for Google Earth
            if isfield(sample, 'timestampMs')
                dt = datetime(sample.timestampMs/1000, 'ConvertFrom', 'posixtime', 'TimeZone', 'UTC');
                isoTime = char(datetime(dt, 'Format', 'yyyy-MM-dd''T''HH:mm:ss''Z'''));
            else
                isoTime = '';
            end
        end
        
        function create_styles(fid)
            styles = {'redStyle', 'ff0000ff'; 'yellowStyle', 'ff00ffff'; 'greenStyle', 'ff00ff00'};
            for i = 1:size(styles, 1)
                fprintf(fid, '<Style id="%s">\n<LineStyle>\n<color>%s</color>\n<width>4</width>\n</LineStyle>\n</Style>\n', styles{i,1}, styles{i,2});
            end
        end
        
        function create_placemark(fid, coords, name, style_id, start_time, end_time)
            fprintf(fid, '<Placemark>\n<name>%s</name>\n<styleUrl>%s</styleUrl>\n', name, style_id);
            
            % Add TimeSpan for Google Earth Slider
            if ~isempty(start_time) && ~isempty(end_time)
                fprintf(fid, '<TimeSpan>\n<begin>%s</begin>\n<end>%s</end>\n</TimeSpan>\n', start_time, end_time);
            end
            
            % Add 3D Extrusion settings
            fprintf(fid, '<LineString>\n<extrude>1</extrude>\n<tessellate>1</tessellate>\n<altitudeMode>absolute</altitudeMode>\n<coordinates>\n');
            fprintf(fid, '%s ', coords{:});
            fprintf(fid, '\n</coordinates>\n</LineString>\n</Placemark>\n');
        end
        
    end
end