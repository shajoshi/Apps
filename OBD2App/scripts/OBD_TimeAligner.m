classdef OBD_TimeAligner
    % OBD_TimeAligner: Cleans Bluetooth jitter, resamples data to a fixed 
    % frequency, and creates a clean relative time channel starting from 0.
    
    methods (Static)
        
        function T_out = regularize(T, sampleRateHz)
            % REGULARIZE Resamples a timetable to a perfectly linear time grid.
            %
            % Usage:
            %   TripData = OBD_TimeAligner.regularize(TripData, 5); % 5 Hz (0.2s)
            
            if nargin < 2
                sampleRateHz = 5; % Default to 5 Hz (5 samples per second)
            end
            
            fprintf('Linearizing Timetable to perfectly spaced %d Hz steps...\n', sampleRateHz);
            
            % 1. Resolve Duplicate Timestamps
            % Bluetooth often sends multiple packets in the exact same millisecond.
            % We must ensure timestamps are strictly unique for linear interpolation.
            [~, uniqueIdx] = unique(T.Time, 'stable');
            T_unique = T(uniqueIdx, :);
            
            % 2. Generate the perfect, linear time grid
            dt = seconds(1 / sampleRateHz);
            newTimeVector = T_unique.Time(1) : dt : T_unique.Time(end);
            
            % 3. Resample the data using Linear Interpolation
            % This smoothly connects the dots between irregular Bluetooth reads
            T_out = retime(T_unique, newTimeVector, 'linear');
            
            % 4. Generate the perfect 'Time From Start' Channel
            % Calculates seconds elapsed since the very first row
            T_out.RelativeTime_s = seconds(T_out.Time - T_out.Time(1));
            
            % Move the new RelativeTime_s column to be the first variable for easy access
            T_out = movevars(T_out, 'RelativeTime_s', 'Before', 1);
            
            fprintf('✓ Time linearisation complete. Added "RelativeTime_s" channel.\n');
        end
        
    end
end