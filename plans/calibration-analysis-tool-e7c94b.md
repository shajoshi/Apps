# Calibration Analysis Tool for Road Quality Detection

Build a Python/Jupyter analysis tool to process manually-labeled GPS track JSON files and recommend optimal calibration thresholds using statistical analysis and machine learning techniques.

## Problem Statement

You have collected ground-truth data with manual labels (`manualLabel`: smooth/average/rough, `manualFeatureLabel`: speed_bump/pothole/bump) alongside raw accelerometer readings and computed metrics (RMS, peakCount, stdDev, etc.). The goal is to analyze this data to find optimal threshold values for the 10 calibration parameters that maximize detection accuracy.

## Current Calibration Parameters

From `CalibrationSettings`:
1. `rmsSmoothMax` (1.0) - RMS threshold for smooth roads
2. `rmsAverageMax` (2.0) - RMS threshold for average roads
3. `peakThresholdZ` (1.5) - Vertical acceleration peak detection
4. `symmetricBumpThreshold` (2.0) - Speed bump detection threshold
5. `potholeDipThreshold` (-2.5) - Pothole dip detection threshold
6. `bumpSpikeThreshold` (2.5) - Single bump spike threshold
7. `peakCountSmoothMax` (5) - Peak count for smooth classification
8. `peakCountAverageMax` (15) - Peak count for average classification
9. `movingAverageWindow` (5) - Smoothing window size
10. `baseGravityVector` - Mount orientation (already calibrated)

## Recommended Analysis Approach

### Phase 1: Data Preparation & Exploration

**1. Load and Parse JSON Data**
- Read the track JSON file
- Extract samples with both manual labels and computed metrics
- Create a pandas DataFrame with columns:
  - Manual labels: `manualLabel`, `manualFeatureLabel`
  - Computed metrics: `rms`, `peakCount`, `stdDev`, `vertMean`, `magMax`
  - Raw data: `raw` accelerometer readings for re-computation if needed
  - Metadata: `timestamp`, `speed`, `location`

**2. Data Quality Checks**
- Count samples per label category
- Check for class imbalance (e.g., too few "smooth" samples)
- Identify outliers or anomalous readings
- Visualize distribution of metrics per manual label

**3. Exploratory Data Analysis (EDA)**
- Box plots: RMS vs manual road quality labels
- Scatter plots: RMS vs peakCount, colored by manual label
- Histograms: Distribution of metrics for each label
- Correlation matrix: Which metrics best separate classes?

### Phase 2: Threshold Optimization

**Road Quality Classification (3-class problem: smooth/average/rough)**

**Method 1: Statistical Percentile Analysis**
- For each manual label, compute percentile ranges of RMS and peakCount
- Find thresholds that minimize overlap between classes
- Example: 
  - `rmsSmoothMax` = 75th percentile of "smooth" RMS values
  - `rmsAverageMax` = 75th percentile of "average" RMS values
  - Similar for peakCount thresholds

**Method 2: Decision Tree Analysis**
- Train a decision tree classifier (max_depth=2) on (RMS, peakCount) → manual label
- Extract threshold splits from the tree structure
- These splits represent optimal decision boundaries

**Method 3: Grid Search with F1 Score**
- Define candidate ranges for each threshold
- Iterate through combinations
- For each combination, classify samples and compute F1 score vs manual labels
- Select thresholds that maximize weighted F1 score

**Feature Detection (multi-label: speed_bump/pothole/bump/none)**

**Method 1: Raw Signal Analysis**
- For samples with `manualFeatureLabel`, extract the raw accelerometer data
- Compute vertical component using gravity vector
- Analyze signal patterns:
  - Speed bumps: Symmetric rise/fall, measure peak amplitude
  - Potholes: Asymmetric dip, measure minimum amplitude
  - Bumps: Single spike, measure max absolute value
- Determine thresholds as percentiles of these amplitudes

**Method 2: Confusion Matrix Optimization**
- Use current detection algorithm on raw data with varying thresholds
- Compare detected features vs manual labels
- Build confusion matrix for each threshold combination
- Optimize for precision/recall balance

**Method 3: ROC Curve Analysis**
- For each feature type, treat as binary classification (feature present/absent)
- Vary threshold, compute TPR/FPR
- Find optimal threshold at ROC curve elbow (max Youden's J statistic)

### Phase 3: Validation & Refinement

**Cross-Validation**
- Split data into train/test sets (80/20)
- Optimize thresholds on training set
- Validate accuracy on test set
- Check for overfitting

**Sensitivity Analysis**
- Test threshold robustness by adding ±10% noise
- Ensure thresholds generalize across different speeds/conditions
- Analyze false positives/negatives to understand failure modes

**Vehicle-Specific Profiles**
- If data includes multiple vehicle types, segment analysis by vehicle
- Generate separate threshold recommendations for Motorcycle/Car/Bicycle
- Compare with existing default profiles

### Phase 4: Implementation

**Output Format**
Generate a report with:
1. **Recommended Thresholds** - Exact values for all 10 parameters
2. **Confidence Metrics** - F1 scores, precision, recall for each class
3. **Confusion Matrices** - Predicted vs actual labels
4. **Visualizations** - Decision boundaries, ROC curves, distribution plots
5. **Profile Comparison** - Current vs recommended thresholds side-by-side

**Export to App**
- Generate JSON profile files with optimized thresholds
- Create vehicle-specific profiles (e.g., `Motorcycle_Optimized.profile.json`)
- Include metadata: training sample count, accuracy metrics, date

## Recommended Tools & Libraries

**Python Stack:**
- `pandas` - Data manipulation
- `numpy` - Numerical operations
- `matplotlib`/`seaborn` - Visualization
- `scikit-learn` - ML algorithms (DecisionTree, GridSearchCV, metrics)
- `scipy` - Statistical functions (percentile, signal processing)
- `json` - Parse track files

**Jupyter Notebook Structure:**
```
1. Setup & Imports
2. Data Loading & Parsing
3. Exploratory Data Analysis
4. Road Quality Threshold Optimization
5. Feature Detection Threshold Optimization
6. Validation & Testing
7. Results Summary & Export
```

## Expected Outcomes

1. **Optimized Thresholds** - Data-driven values replacing current heuristics
2. **Accuracy Metrics** - Quantified improvement over default settings
3. **Vehicle Profiles** - Separate calibrations for different vehicle types
4. **Insights** - Understanding which metrics best predict road quality/features
5. **Reusable Pipeline** - Script to re-run analysis on new labeled data

## Alternative: Supervised ML Approach

Instead of threshold optimization, train ML models directly:
- **Random Forest** or **XGBoost** for road quality classification
- **Neural Network** for feature detection from raw signals
- Export model as ONNX and integrate into Android app
- Trade-off: More accurate but requires model inference in app

## Next Steps

1. Confirm you have sufficient labeled samples (recommend 100+ per class)
2. Share sample JSON file structure for parsing verification
3. Choose analysis method: Statistical thresholds (simpler) vs ML models (more complex)
4. Decide on tool: Jupyter notebook (interactive) vs standalone Python script (automated)
5. Implement Phase 1 (EDA) to understand data characteristics before optimization
