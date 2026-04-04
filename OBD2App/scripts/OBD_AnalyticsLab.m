classdef OBD_AnalyticsLab
    % OBD_AnalyticsLab: The master orchestrator for OBD2 Deep Analytics.
    
    methods (Static)
        
        function run(filename)
            if nargin < 1
                filename = '';
            end
            
            %% 1. The Automated Pipeline
            fprintf('=== Starting OBD Deep Analytics Pipeline ===\n');
            [TripData, Profile] = OBD_Importer.read(filename);
            TripData = OBD_Calculators.applyAll(TripData, Profile);
            TripData = OBD_TimeAligner.regularize(TripData, 5); % Linearized to 5 Hz
            fprintf('=== Pipeline Complete. Launching Dashboards ===\n');
            
            %% 2. Analytics Menu
            % Let the user choose what kind of deep analysis to run
            analysisType = questdlg('Which Deep Analytics module would you like to run?', ...
                               'Analytics Lab', ...
                               'Time-Domain Correlations', 'Frequency Domain (FFT/PSD)', 'Engine Heatmap', 'Time-Domain Correlations');
                           
            if isempty(analysisType)
                return;
            end
            
            allVars = TripData.Properties.VariableNames;
            validVars = allVars(~strcmp(allVars, 'RelativeTime_s') & ~strcmp(allVars, 'Time'));
            
            %% 3. Execute Selected Module
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
                        else
                            errordlg('Please select exactly 1 or 2 signals for Frequency Analysis.');
                        end
                    end
            end
        end
        
        %% --- FFT & Frequency Analytics ---
        
        function plotFrequencyAnalysis(T, signalName)
            % Performs standard FFT and Power Spectral Density (PSD) on a single signal
            
            % 1. Extract and clean the signal
            y = T.(signalName);
            y = fillmissing(y, 'linear'); % FFT hates NaNs
            
            % Remove DC Offset (Mean) so the 0 Hz bin doesn't blow out the chart
            y_ac = y - mean(y);
            
            % 2. Calculate dynamic Sample Rate from the linearized time channel
            dt = mean(diff(T.RelativeTime_s));
            Fs = 1 / dt;
            L = length(y_ac);
            
            % 3. Calculate FFT (Single-Sided Amplitude Spectrum)
            Y = fft(y_ac);
            P2 = abs(Y/L);
            P1 = P2(1:floor(L/2)+1);
            P1(2:end-1) = 2*P1(2:end-1);
            f_fft = Fs*(0:(L/2))/L;
            
            % 4. Create the Dashboard
            cleanName = strrep(signalName, '_', ' ');
            fig = figure('Name', sprintf('Frequency Analysis: %s', cleanName), 'Color', 'w', 'Position', [100 100 1000 600]);
            tiledlayout(2, 1, 'TileSpacing', 'compact');
            
            % Plot 1: Raw FFT Amplitude
            ax1 = nexttile;
            plot(ax1, f_fft, P1, 'b', 'LineWidth', 1.5);
            title(ax1, sprintf('Single-Sided FFT Amplitude Spectrum: %s', cleanName), 'FontWeight', 'bold');
            xlabel(ax1, 'Frequency (Hz)');
            ylabel(ax1, 'Amplitude');
            grid(ax1, 'on');
            
            % Plot 2: Welch's Power Spectral Density (Requires Signal Processing Toolbox)
            ax2 = nexttile;
            try
                % Use pwelch to smooth out the noise and find true energy peaks
                [pxx, f_psd] = pwelch(y_ac, [], [], [], Fs);
                plot(ax2, f_psd, 10*log10(pxx), 'r', 'LineWidth', 1.5);
                title(ax2, 'Welch''s Power Spectral Density (PSD)', 'FontWeight', 'bold');
                xlabel(ax2, 'Frequency (Hz)');
                ylabel(ax2, 'Power/Frequency (dB/Hz)');
                grid(ax2, 'on');
            catch
                % Fallback if toolbox is missing
                title(ax2, '(Signal Processing Toolbox required for PSD plot)');
                axis(ax2, 'off');
            end
        end
        
        function plotCoherence(T, sig1, sig2)
            % Magnitude-Squared Coherence (Cross-Spectrum)
            % Finds if oscillations in Signal 1 cause oscillations in Signal 2
            
            y1 = fillmissing(T.(sig1), 'linear');
            y2 = fillmissing(T.(sig2), 'linear');
            
            y1 = y1 - mean(y1);
            y2 = y2 - mean(y2);
            
            Fs = 1 / mean(diff(T.RelativeTime_s));
            
            clean1 = strrep(sig1, '_', ' ');
            clean2 = strrep(sig2, '_', ' ');
            
            fig = figure('Name', 'Cross-Spectral Coherence', 'Color', 'w', 'Position', [150 150 900 500]);
            
            try
                % mscohere calculates how well sig1 and sig2 correlate at EACH specific frequency
                [Cxy, f] = mscohere(y1, y2, [], [], [], Fs);
                
                plot(f, Cxy, 'k', 'LineWidth', 2);
                title(sprintf('Signal Coherence: %s vs. %s', clean1, clean2), 'FontSize', 14, 'FontWeight', 'bold');
                xlabel('Frequency (Hz)');
                ylabel('Coherence (0 to 1)');
                grid on;
                
                % Add an interpretation line
                yline(0.5, 'r--', 'Significant Correlation Threshold', 'LabelHorizontalAlignment', 'left');
            catch
                text(0.1, 0.5, 'Signal Processing Toolbox required for Coherence Analysis.', 'FontSize', 12);
                axis off;
            end
        end
        
        %% --- Existing Plotting Functions (Kept Intact) ---
        
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
            if ~ismember('obd_rpm', T.Properties.VariableNames) || ~ismember('obd_engineLoadPct', T.Properties.VariableNames), return; end
            fig = figure('Name', 'Engine Operating Range', 'Color', 'w', 'Position', [150 150 800 600]);
            rpm = T.obd_rpm; load = T.obd_engineLoadPct;
            valid = ~isnan(rpm) & ~isnan(load);
            histogram2(rpm(valid), load(valid), [40 40], 'DisplayStyle', 'tile', 'ShowEmptyBins', 'off');
            colormap(jet); cb = colorbar; cb.Label.String = 'Samples';
            title('Engine Heatmap (RPM vs. Load)', 'FontSize', 14, 'FontWeight', 'bold'); xlabel('RPM'); ylabel('Load (%)');
        end
    end
end