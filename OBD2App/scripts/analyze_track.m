function analyze_track(filename, varargin)
    % analyze_track - Convert JSON trip logs to KML, with UI selection & saving options
    
    % 1. Prompt for file if not provided
    if nargin < 1 || isempty(filename)
        [file, path] = uigetfile('*.json', 'Select the OBD JSON log file');
        if isequal(file, 0)
            disp('File selection canceled.');
            return;
        end
        filename = fullfile(path, file);
    end
    
    p = inputParser;
    addParameter(p, 'Strategy', 'percentile', @ischar);
    parse(p, varargin{:});
    strategy = p.Results.Strategy;
    
    if ~isfile(filename)
        error('File ''%s'' not found', filename);
    end
    
    % 2. Load Data
    fprintf('Loading track data from %s...\n', filename);
    rawStr = fileread(filename);
    data = jsondecode(rawStr);
    
    header = data.header;
    samples = data.samples;
    if isstruct(samples)
        samples = num2cell(samples);
    end
    
    % 3. Discover all available metrics dynamically
    fprintf('Scanning JSON structure to discover available metrics...\n');
    allAvailableMetrics = discover_all_metrics(samples);
    ignoreList = {'timestampMs', 'sampleNo', 'gps.lat', 'gps.lon', 'gps.time'};
    allAvailableMetrics = setdiff(allAvailableMetrics, ignoreList, 'stable');
    
    if isempty(allAvailableMetrics)
        error('Could not find any numeric metrics in the JSON file.');
    end
    
    % 4. UI Multi-Select Dialog for Metrics
    [indx, tf] = listdlg('PromptString', {'Select metrics to analyze & plot:', ...
                                          '(Hold Ctrl/Cmd or Shift to select multiple)'}, ...
                         'SelectionMode', 'multiple', ...
                         'ListString', allAvailableMetrics, ...
                         'Name', 'Select OBD Metrics', ...
                         'ListSize', [300 450]);
                     
    if ~tf
        disp('Metric selection canceled.');
        return;
    end
    metrics = allAvailableMetrics(indx);
    
    % 5. ASK SAVE PREFERENCE
    saveChoice = questdlg('Do you want to save KMLs and Image files to your disk, or just view plots?', ...
                          'Output Preference', ...
                          'Save Everything', 'Just Plot in MATLAB', 'Save Everything');
    if isempty(saveChoice)
        disp('Analysis canceled.');
        return;
    end
    doSave = strcmp(saveChoice, 'Save Everything');
    
    % 6. Extract User Selected Data
    fprintf('\nExtracting data...\n');
    dataPoints = extract_metric_data(samples, metrics);
    
    validMetrics = {};
    for m = 1:length(metrics)
        metric = metrics{m};
        if ~isempty(dataPoints.(strrep(metric, '.', '_')))
            validMetrics{end+1} = metric;
        end
    end
    
    [folder, baseName, ~] = fileparts(filename);
    if isempty(folder), folder = pwd; end
    baseFilePath = fullfile(folder, baseName);
    
    % 7. Process Data (KMLs and Distributions)
    for m = 1:length(validMetrics)
        metric = validMetrics{m};
        metricSafe = strrep(metric, '.', '_');
        pts = dataPoints.(metricSafe);
        values = [pts.value];
        
        [lowThresh, highThresh] = calculate_color_ranges(values, strategy);
        kmlFile = sprintf('%s_%s.kml', baseFilePath, metricSafe);
        
        if doSave
            create_kml(pts, lowThresh, highThresh, metric, kmlFile, header);
        end
        create_distribution_plot(values, metric, kmlFile, lowThresh, highThresh, doSave);
    end
    
    % 8. Time-Series Plotting (Always pops up)
    plot_beautiful_timeseries(dataPoints, validMetrics, header);
    
    % 9. Advanced Dashboard (Always pops up)
    generate_advanced_dashboard(samples);
    
    % 10. Correlation Plots
    if length(validMetrics) >= 2
        fprintf('\nGenerating correlation plots for %d metrics...\n', length(validMetrics));
        create_correlation_plots(dataPoints, validMetrics, [baseFilePath, '_correlation.kml'], doSave);
    end
    
    if doSave
        fprintf('\nAnalysis complete! Files saved to your disk.\n');
    else
        fprintf('\nAnalysis complete! All plots generated in MATLAB.\n');
    end
end

%% --- Analysis & Saving Plot Logic Updates ---

function create_distribution_plot(values, metric, kmlFile, lowT, highT, doSave)
    filtered = values(values ~= 0);
    if isempty(filtered), return; end
    
    % Toggle visibility based on save preference
    visState = 'on';
    if doSave, visState = 'off'; end
    
    fig = figure('Visible', visState, 'Position', [100, 100, 800, 400], 'Color', 'w'); hold on;
    h = histogram(filtered, 50, 'EdgeColor', 'black');
    binCenters = h.BinEdges(1:end-1) + h.BinWidth/2;
    for i = 1:length(binCenters)
        if binCenters(i) <= lowT, c = [1 0 0]; elseif binCenters(i) >= highT, c = [0 1 0]; else, c = [1 1 0]; end
        bar(binCenters(i), h.Values(i), h.BinWidth, 'FaceColor', c, 'EdgeColor', 'k', 'FaceAlpha', 0.7);
    end
    xline(mean(filtered), '--k', 'LineWidth', 2);
    xline(lowT, '-r', 'LineWidth', 2); xline(highT, '-g', 'LineWidth', 2);
    title(sprintf('Distribution of %s', strrep(metric, '_', '\_'))); xlabel(strrep(metric, '_', '\_'));
    
    if doSave
        plotFile = strrep(kmlFile, '.kml', '_distribution.png'); saveas(fig, plotFile); close(fig);
    end
end

function create_correlation_plots(dataPoints, metrics, outputFile, doSave)
    % Align data
    aligned = struct();
    for m = 1:length(metrics)
        mSafe = strrep(metrics{m}, '.', '_');
        pts = dataPoints.(mSafe);
        pts = pts([pts.value] ~= 0);
        map = containers.Map('KeyType', 'double', 'ValueType', 'double');
        for i = 1:length(pts), map(pts(i).timestamp) = pts(i).value; end
        aligned.(mSafe) = map;
    end
    
    commonTs = cell2mat(keys(aligned.(strrep(metrics{1}, '.', '_'))));
    for m = 2:length(metrics)
        mSafe = strrep(metrics{m}, '.', '_');
        commonTs = intersect(commonTs, cell2mat(keys(aligned.(mSafe))));
    end
    if isempty(commonTs), return; end
    
    n = length(metrics); matData = zeros(length(commonTs), n);
    for m = 1:n
        map = aligned.(strrep(metrics{m}, '.', '_'));
        for i = 1:length(commonTs), matData(i, m) = map(commonTs(i)); end
    end
    
    visState = 'on';
    if doSave, visState = 'off'; end
    
    % Correlation Heatmap
    cMatrix = corrcoef(matData);
    fig1 = figure('Visible', visState, 'Position', [100 100 800 600], 'Color', 'w');
    heatmap(metrics, metrics, cMatrix, 'Title', 'Correlation Matrix Between Metrics', 'Colormap', jet);
    
    % Scatter Matrix
    fig2 = figure('Visible', visState, 'Position', [100, 100, 1000, 1000], 'Color', 'w');
    try
        [~,ax] = plotmatrix(matData);
        for i = 1:n
            ax(i,1).YLabel.String = strrep(metrics{i},'_','\_'); ax(n,i).XLabel.String = strrep(metrics{i},'_','\_');
        end
        title('Correlation Scatter Matrix');
    catch
        disp('plotmatrix failed (requires Statistics and Machine Learning Toolbox).');
    end
    
    if doSave
        heatFile = strrep(outputFile, '.kml', '_matrix.png'); saveas(fig1, heatFile); close(fig1);
        scatterFile = strrep(outputFile, '.kml', '_scatter.png'); saveas(fig2, scatterFile); close(fig2);
    end
end

%% --- Unchanged Advanced Plotting & Helper Functions ---
% Note: The functions below remain identical to the previous version. Paste them here!

function generate_advanced_dashboard(samples)
    coreMetrics = {'obd.rpm', 'obd.engineLoadPct', 'obd.speedKmh'};
    coreData = extract_metric_data(samples, coreMetrics);
    
    fig = figure('Name', 'Advanced OBD Analytics', 'Color', 'w', 'Position', [150 150 1200 800]);
    t = tiledlayout(2, 2, 'TileSpacing', 'compact', 'Padding', 'compact');
    title(t, 'Advanced Trip Analytics', 'FontWeight', 'bold', 'FontSize', 16);
    
    % --- PLOT 1: 2D Heatmap (RPM vs Load) ---
    rpmPts = coreData.obd_rpm; loadPts = coreData.obd_engineLoadPct;
    if ~isempty(rpmPts) && ~isempty(loadPts)
        [alignedRpm, alignedLoad] = align_two_signals(rpmPts, loadPts);
        if ~isempty(alignedRpm)
            ax1 = nexttile;
            histogram2(ax1, alignedRpm, alignedLoad, [30 30], 'DisplayStyle', 'tile', 'ShowEmptyBins', 'off');
            colormap(ax1, jet); cb = colorbar(ax1); cb.Label.String = 'Frequency (Counts)';
            title(ax1, 'RPM vs Engine Load (2D Histogram)'); xlabel(ax1, 'RPM'); ylabel(ax1, 'Engine Load (%)');
        end
    end
    
    % --- PLOT 2: Time in Speed Range ---
    spdPts = coreData.obd_speedKmh;
    if ~isempty(spdPts)
        spd = [spdPts.value]; edges = 0:20:(max(spd)+20); ax2 = nexttile;
        histogram(ax2, spd, edges, 'Normalization', 'probability', 'FaceColor', [0.2 0.6 0.8]);
        title(ax2, 'Time Spent in Speed Range'); xlabel(ax2, 'Speed (km/h)'); ylabel(ax2, 'Fraction of Trip');
        grid(ax2, 'on'); box(ax2, 'off');
    end
    
    % --- PLOT 3: Frequency Domain (RPM Spectrogram / FFT) ---
    if ~isempty(rpmPts)
        ts = [rpmPts.timestamp] / 1000; rpm = [rpmPts.value]; fs = 10; 
        ti = ts(1):(1/fs):ts(end); rpm_interp = interp1(ts, rpm, ti, 'linear', 'extrap');
        rpm_ac = rpm_interp - mean(rpm_interp); ax3 = nexttile([1 2]);
        try
            spectrogram(rpm_ac, 256, 120, 256, fs, 'yaxis');
            title(ax3, 'RPM Frequency Domain (Spectrogram)'); colormap(ax3, parula);
        catch
            L = length(rpm_ac); Y = fft(rpm_ac); P2 = abs(Y/L); P1 = P2(1:floor(L/2)+1); P1(2:end-1) = 2*P1(2:end-1);
            f = fs*(0:(L/2))/L; plot(ax3, f, P1, 'LineWidth', 1.5, 'Color', [0.8 0.2 0.2]);
            title(ax3, 'RPM Frequency Spectrum (FFT Fallback)'); xlabel(ax3, 'Frequency (Hz)'); ylabel(ax3, 'Amplitude');
            grid(ax3, 'on'); box(ax3, 'off');
        end
    end
end

function [val1, val2] = align_two_signals(pts1, pts2)
    map = containers.Map('KeyType', 'double', 'ValueType', 'double');
    for i = 1:length(pts1), map(pts1(i).timestamp) = pts1(i).value; end
    val1 = []; val2 = [];
    for i = 1:length(pts2)
        ts = pts2(i).timestamp;
        if isKey(map, ts), val1(end+1) = map(ts); val2(end+1) = pts2(i).value; end %#ok<AGROW>
    end
end

function plot_beautiful_timeseries(dataPoints, metrics, header)
    fig = figure('Name', 'OBD Trip Time-Series', 'Color', 'w', 'Position', [100 100 1200 800]);
    t = tiledlayout(length(metrics), 1, 'TileSpacing', 'compact', 'Padding', 'compact');
    tripName = 'OBD2 Trip Data';
    if isfield(header, 'vehicleProfile') && isfield(header.vehicleProfile, 'name')
        tripName = sprintf('%s Trip Data', header.vehicleProfile.name);
    end
    title(t, tripName, 'FontWeight', 'bold', 'FontSize', 16);
    colors = lines(length(metrics)); 
    
    for m = 1:length(metrics)
        metric = metrics{m}; metricSafe = strrep(metric, '.', '_'); pts = dataPoints.(metricSafe);
        timeArr = datetime([pts.timestamp]/1000, 'ConvertFrom', 'posixtime', 'TimeZone', 'local'); valArr = [pts.value];
        ax = nexttile; hold(ax, 'on');
        plot(ax, timeArr, valArr, 'Color', [colors(m,:), 0.3], 'LineWidth', 1, 'DisplayName', 'Raw');
        windowSize = max(5, floor(length(valArr)*0.01)); valSmooth = movmean(valArr, windowSize);
        plot(ax, timeArr, valSmooth, 'Color', colors(m,:), 'LineWidth', 2, 'DisplayName', 'Averaged');
        grid(ax, 'on'); box(ax, 'off'); ylabel(ax, strrep(metric, '.', ' '), 'Interpreter', 'none', 'FontWeight', 'bold');
        legend(ax, 'Location', 'northeast');
        if m < length(metrics), xticklabels(ax, {}); end
    end
    xlabel(t, 'Time', 'FontWeight', 'bold');
    axesHandles = findobj(fig, 'Type', 'axes'); linkaxes(axesHandles, 'x');
end

function metricsList = discover_all_metrics(samples)
    metricsList = {}; scanLimit = min(100, length(samples));
    for i = 1:scanLimit, paths = get_struct_paths(samples{i}, ''); metricsList = [metricsList; paths]; end %#ok<AGROW>
    metricsList = unique(metricsList, 'stable');
end

function paths = get_struct_paths(s, prefix)
    paths = {};
    if isstruct(s)
        fnames = fieldnames(s);
        for i = 1:length(fnames), newPref = fnames{i}; if ~isempty(prefix), newPref = [prefix, '.', fnames{i}]; end
            paths = [paths; get_struct_paths(s.(fnames{i}), newPref)]; %#ok<AGROW>
        end
    elseif isnumeric(s) || islogical(s), paths = {prefix}; end
end

function dataPoints = extract_metric_data(samples, metrics)
    dataPoints = struct();
    for m = 1:length(metrics), dataPoints.(strrep(metrics{m}, '.', '_')) = []; end
    for i = 1:length(samples)
        sample = samples{i};
        if ~isfield(sample, 'gps') || isempty(sample.gps) || ~isfield(sample.gps, 'lat') || ~isfield(sample.gps, 'lon'), continue; end
        lat = sample.gps.lat; lon = sample.gps.lon; ts = sample.timestampMs;
        for m = 1:length(metrics)
            metric = metrics{m}; metricSafe = strrep(metric, '.', '_'); val = get_nested_value(sample, metric);
            if isnan(val) && strcmp(metric, 'fuel.instantKpl')
                l_per_100km = get_nested_value(sample, 'fuel.instantLper100km');
                if ~isnan(l_per_100km) && l_per_100km > 0, val = 100.0 / l_per_100km; end
            elseif isnan(val) && strcmp(metric, 'fuel.instantLper100km')
                kpl = get_nested_value(sample, 'fuel.instantKpl');
                if ~isnan(kpl) && kpl > 0, val = 100.0 / kpl; end
            end
            if ~isnan(val)
                pt = struct('lat', lat, 'lon', lon, 'value', val, 'timestamp', ts);
                dataPoints.(metricSafe) = [dataPoints.(metricSafe); pt];
            end
        end
    end
end

function val = get_nested_value(data, pathStr)
    keys = split(pathStr, '.'); curr = data; val = NaN;
    try, for k = 1:length(keys), curr = curr.(keys{k}); end
        if isnumeric(curr) || islogical(curr), val = double(curr(1)); end
    catch, val = NaN; end
end

function [lowThresh, highThresh] = calculate_color_ranges(values, strategy)
    if isempty(values), lowThresh=0; highThresh=0; return; end
    filtered = values(values ~= 0); if isempty(filtered), lowThresh=0; highThresh=0; return; end
    sortedValues = sort(filtered); n = length(sortedValues);
    switch strategy
        case 'std_dev', m = mean(filtered); s = std(filtered); lowThresh = m - s; highThresh = m + s;
        case 'iqr', lowThresh = prctile(filtered, 25); highThresh = prctile(filtered, 75);
        otherwise, lowThresh = prctile(filtered, 33.33); highThresh = prctile(filtered, 66.67);
    end
end

function create_kml(points, lowT, highT, metric, outputFile, header)
    fid = fopen(outputFile, 'w');
    fprintf(fid, '<?xml version="1.0" encoding="UTF-8"?>\n<kml xmlns="http://www.opengis.net/kml/2.2">\n<Document>\n');
    fprintf(fid, '<name>OBD2 Track - %s</name>\n<description>\nMetric: %s\nLow threshold: %.1f\nHigh threshold: %.1f\n</description>\n', metric, metric, lowT, highT);
    styles = {'lowStyle', 'ff0000ff'; 'mediumStyle', 'ff00ffff'; 'highStyle', 'ff00ff00'};
    for i = 1:3, fprintf(fid, '<Style id="%s"><LineStyle><color>%s</color><width>4</width></LineStyle></Style>\n', styles{i,1}, styles{i,2}); end
    currentColor = ''; segmentPts = [];
    for i = 1:length(points)
        pt = points(i); 
        if pt.value <= lowT, pColor = 'ff0000ff'; elseif pt.value >= highT, pColor = 'ff00ff00'; else, pColor = 'ff00ffff'; end
        if strcmp(pColor, 'ff0000ff'), styleId = 'lowStyle'; elseif strcmp(pColor, 'ff00ffff'), styleId = 'mediumStyle'; else, styleId = 'highStyle'; end
        if ~strcmp(pColor, currentColor) && ~isempty(segmentPts)
            fprintf(fid, '<Placemark><styleUrl>#%s</styleUrl><LineString><coordinates>\n', prevStyle);
            for j = 1:size(segmentPts, 1), fprintf(fid, '%.6f,%.6f,0\n', segmentPts(j, 2), segmentPts(j, 1)); end
            fprintf(fid, '</coordinates></LineString></Placemark>\n');
            segmentPts = [];
        end
        if isempty(segmentPts), currentColor = pColor; prevStyle = styleId; end
        segmentPts = [segmentPts; pt.lat, pt.lon];
    end
    if ~isempty(segmentPts)
        fprintf(fid, '<Placemark><styleUrl>#%s</styleUrl><LineString><coordinates>\n', prevStyle);
        for j = 1:size(segmentPts, 1), fprintf(fid, '%.6f,%.6f,0\n', segmentPts(j, 2), segmentPts(j, 1)); end
        fprintf(fid, '</coordinates></LineString></Placemark>\n');
    end
    fprintf(fid, '</Document></kml>\n'); fclose(fid);
end