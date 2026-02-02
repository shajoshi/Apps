# Calibration Analysis Jupyter Notebook Implementation

Create a complete Jupyter notebook at `c:/Code/SJGpsUtil/calibration_analysis.ipynb` to analyze manually-labeled GPS track data and optimize detection thresholds.

## Notebook Structure

### Cell 1: Setup and Imports
```python
import json
import pandas as pd
import numpy as np
import matplotlib.pyplot as plt
import seaborn as sns
from sklearn.tree import DecisionTreeClassifier, plot_tree
from sklearn.model_selection import train_test_split, GridSearchCV
from sklearn.metrics import classification_report, confusion_matrix, f1_score, roc_curve, auc
from scipy import stats
import warnings
warnings.filterwarnings('ignore')

# Set visualization style
sns.set_style("whitegrid")
plt.rcParams['figure.figsize'] = (12, 6)
```

### Cell 2: Configuration
```python
# File paths
TRACK_FILE = "track_20260131_190120.json"  # Update with your file
OUTPUT_DIR = "calibration_results"

# Current default thresholds (for comparison)
DEFAULT_THRESHOLDS = {
    'rmsSmoothMax': 1.0,
    'rmsAverageMax': 2.0,
    'peakThresholdZ': 1.5,
    'symmetricBumpThreshold': 2.0,
    'potholeDipThreshold': -2.5,
    'bumpSpikeThreshold': 2.5,
    'peakCountSmoothMax': 5,
    'peakCountAverageMax': 15,
    'movingAverageWindow': 5
}

import os
os.makedirs(OUTPUT_DIR, exist_ok=True)
```

### Cell 3: Data Loading Function
```python
def load_track_data(filepath):
    """Load and parse GPS track JSON file."""
    with open(filepath, 'r') as f:
        data = json.load(f)
    
    samples = []
    for point in data.get('points', []):
        gps = point.get('gps', {})
        accel = point.get('accel', {})
        
        # Only include samples with manual labels
        if accel.get('manualLabel') or accel.get('manualFeatureLabel'):
            sample = {
                'timestamp': gps.get('ts'),
                'speed': gps.get('speed'),
                'rms': accel.get('rms'),
                'peakCount': accel.get('peakCount'),
                'stdDev': accel.get('stdDev'),
                'vertMean': accel.get('vertMean'),
                'magMax': accel.get('magMax'),
                'xMean': accel.get('xMean'),
                'yMean': accel.get('yMean'),
                'zMean': accel.get('zMean'),
                'manualLabel': accel.get('manualLabel'),
                'manualFeatureLabel': accel.get('manualFeatureLabel'),
                'detectedQuality': accel.get('roadQuality'),
                'detectedFeature': accel.get('featureDetected'),
                'rawData': accel.get('raw', [])
            }
            samples.append(sample)
    
    df = pd.DataFrame(samples)
    print(f"Loaded {len(df)} samples with manual labels")
    return df

# Load data
df = load_track_data(TRACK_FILE)
df.head()
```

### Cell 4: Data Quality Check
```python
print("=== Data Quality Report ===\n")
print(f"Total samples: {len(df)}")
print(f"\nSamples with manual quality labels: {df['manualLabel'].notna().sum()}")
print(f"Samples with manual feature labels: {df['manualFeatureLabel'].notna().sum()}")

print("\n--- Manual Quality Label Distribution ---")
print(df['manualLabel'].value_counts())

print("\n--- Manual Feature Label Distribution ---")
print(df['manualFeatureLabel'].value_counts())

print("\n--- Missing Values ---")
print(df.isnull().sum())

print("\n--- Basic Statistics ---")
print(df[['rms', 'peakCount', 'stdDev', 'magMax']].describe())
```

### Cell 5: Exploratory Data Analysis - Road Quality
```python
# Filter samples with quality labels
df_quality = df[df['manualLabel'].notna()].copy()

fig, axes = plt.subplots(2, 2, figsize=(15, 10))

# RMS by manual label
sns.boxplot(data=df_quality, x='manualLabel', y='rms', ax=axes[0, 0])
axes[0, 0].set_title('RMS Distribution by Manual Road Quality Label')
axes[0, 0].set_xlabel('Manual Label')
axes[0, 0].set_ylabel('RMS')

# Peak Count by manual label
sns.boxplot(data=df_quality, x='manualLabel', y='peakCount', ax=axes[0, 1])
axes[0, 1].set_title('Peak Count Distribution by Manual Road Quality Label')
axes[0, 1].set_xlabel('Manual Label')
axes[0, 1].set_ylabel('Peak Count')

# Scatter: RMS vs Peak Count
for label in df_quality['manualLabel'].unique():
    subset = df_quality[df_quality['manualLabel'] == label]
    axes[1, 0].scatter(subset['rms'], subset['peakCount'], label=label, alpha=0.6)
axes[1, 0].set_xlabel('RMS')
axes[1, 0].set_ylabel('Peak Count')
axes[1, 0].set_title('RMS vs Peak Count (colored by manual label)')
axes[1, 0].legend()
axes[1, 0].grid(True)

# Std Dev by manual label
sns.boxplot(data=df_quality, x='manualLabel', y='stdDev', ax=axes[1, 1])
axes[1, 1].set_title('Std Dev Distribution by Manual Road Quality Label')
axes[1, 1].set_xlabel('Manual Label')
axes[1, 1].set_ylabel('Std Dev')

plt.tight_layout()
plt.savefig(f'{OUTPUT_DIR}/eda_road_quality.png', dpi=150)
plt.show()
```

### Cell 6: Exploratory Data Analysis - Features
```python
# Filter samples with feature labels
df_features = df[df['manualFeatureLabel'].notna()].copy()

if len(df_features) > 0:
    fig, axes = plt.subplots(1, 3, figsize=(18, 5))
    
    # Mag Max by feature
    sns.boxplot(data=df_features, x='manualFeatureLabel', y='magMax', ax=axes[0])
    axes[0].set_title('Max Magnitude by Manual Feature Label')
    axes[0].set_xticklabels(axes[0].get_xticklabels(), rotation=45)
    
    # Vert Mean by feature
    sns.boxplot(data=df_features, x='manualFeatureLabel', y='vertMean', ax=axes[1])
    axes[1].set_title('Vertical Mean by Manual Feature Label')
    axes[1].set_xticklabels(axes[1].get_xticklabels(), rotation=45)
    
    # Peak Count by feature
    sns.boxplot(data=df_features, x='manualFeatureLabel', y='peakCount', ax=axes[2])
    axes[2].set_title('Peak Count by Manual Feature Label')
    axes[2].set_xticklabels(axes[2].get_xticklabels(), rotation=45)
    
    plt.tight_layout()
    plt.savefig(f'{OUTPUT_DIR}/eda_features.png', dpi=150)
    plt.show()
else:
    print("No feature labels found in dataset")
```

### Cell 7: Method 1 - Percentile-Based Thresholds
```python
def calculate_percentile_thresholds(df_quality, percentile=75):
    """Calculate thresholds based on percentiles of each class."""
    results = {}
    
    # RMS thresholds
    smooth_rms = df_quality[df_quality['manualLabel'] == 'smooth']['rms']
    average_rms = df_quality[df_quality['manualLabel'] == 'average']['rms']
    
    if len(smooth_rms) > 0:
        results['rmsSmoothMax'] = np.percentile(smooth_rms, percentile)
    if len(average_rms) > 0:
        results['rmsAverageMax'] = np.percentile(average_rms, percentile)
    
    # Peak count thresholds
    smooth_peaks = df_quality[df_quality['manualLabel'] == 'smooth']['peakCount']
    average_peaks = df_quality[df_quality['manualLabel'] == 'average']['peakCount']
    
    if len(smooth_peaks) > 0:
        results['peakCountSmoothMax'] = int(np.percentile(smooth_peaks, percentile))
    if len(average_peaks) > 0:
        results['peakCountAverageMax'] = int(np.percentile(average_peaks, percentile))
    
    return results

percentile_thresholds = calculate_percentile_thresholds(df_quality)
print("=== Percentile-Based Thresholds (75th percentile) ===")
for key, value in percentile_thresholds.items():
    default = DEFAULT_THRESHOLDS.get(key, 'N/A')
    print(f"{key}: {value:.3f} (default: {default})")
```

### Cell 8: Method 2 - Decision Tree Optimization
```python
def optimize_with_decision_tree(df_quality):
    """Use decision tree to find optimal split points."""
    # Prepare data
    X = df_quality[['rms', 'peakCount']].values
    y = df_quality['manualLabel'].values
    
    # Train shallow decision tree
    dt = DecisionTreeClassifier(max_depth=2, random_state=42)
    dt.fit(X, y)
    
    # Visualize tree
    plt.figure(figsize=(15, 8))
    plot_tree(dt, feature_names=['RMS', 'Peak Count'], 
              class_names=dt.classes_, filled=True, rounded=True)
    plt.title('Decision Tree for Road Quality Classification')
    plt.savefig(f'{OUTPUT_DIR}/decision_tree.png', dpi=150, bbox_inches='tight')
    plt.show()
    
    # Extract thresholds from tree structure
    tree = dt.tree_
    feature_names = ['rms', 'peakCount']
    thresholds = {}
    
    def extract_thresholds(node=0, depth=0):
        if tree.feature[node] != -2:  # Not a leaf
            feature = feature_names[tree.feature[node]]
            threshold = tree.threshold[node]
            print(f"{'  ' * depth}Split on {feature} <= {threshold:.3f}")
            extract_thresholds(tree.children_left[node], depth + 1)
            extract_thresholds(tree.children_right[node], depth + 1)
    
    print("\n=== Decision Tree Splits ===")
    extract_thresholds()
    
    # Evaluate
    y_pred = dt.predict(X)
    print("\n=== Decision Tree Performance ===")
    print(classification_report(y, y_pred))
    
    return dt

dt_model = optimize_with_decision_tree(df_quality)
```

### Cell 9: Method 3 - Grid Search Optimization
```python
def grid_search_thresholds(df_quality):
    """Grid search to find optimal thresholds maximizing F1 score."""
    # Define search space
    rms_smooth_range = np.linspace(0.5, 2.0, 10)
    rms_average_range = np.linspace(1.5, 4.0, 10)
    peak_smooth_range = range(3, 15, 2)
    peak_average_range = range(10, 30, 3)
    
    best_f1 = 0
    best_params = {}
    
    results = []
    
    for rms_s in rms_smooth_range:
        for rms_a in rms_average_range:
            if rms_a <= rms_s:
                continue
            for peak_s in peak_smooth_range:
                for peak_a in peak_average_range:
                    if peak_a <= peak_s:
                        continue
                    
                    # Classify using these thresholds
                    def classify(row):
                        if row['rms'] < rms_s and row['peakCount'] < peak_s:
                            return 'smooth'
                        elif row['rms'] < rms_a and row['peakCount'] < peak_a:
                            return 'average'
                        else:
                            return 'rough'
                    
                    predictions = df_quality.apply(classify, axis=1)
                    f1 = f1_score(df_quality['manualLabel'], predictions, average='weighted')
                    
                    results.append({
                        'rmsSmoothMax': rms_s,
                        'rmsAverageMax': rms_a,
                        'peakCountSmoothMax': peak_s,
                        'peakCountAverageMax': peak_a,
                        'f1_score': f1
                    })
                    
                    if f1 > best_f1:
                        best_f1 = f1
                        best_params = {
                            'rmsSmoothMax': rms_s,
                            'rmsAverageMax': rms_a,
                            'peakCountSmoothMax': peak_s,
                            'peakCountAverageMax': peak_a
                        }
    
    print("=== Grid Search Results ===")
    print(f"Best F1 Score: {best_f1:.4f}")
    print("\nOptimal Thresholds:")
    for key, value in best_params.items():
        default = DEFAULT_THRESHOLDS.get(key, 'N/A')
        print(f"{key}: {value:.3f} (default: {default})")
    
    # Show top 10 configurations
    results_df = pd.DataFrame(results).sort_values('f1_score', ascending=False)
    print("\nTop 10 Configurations:")
    print(results_df.head(10))
    
    return best_params, best_f1

grid_thresholds, grid_f1 = grid_search_thresholds(df_quality)
```

### Cell 10: Feature Detection - Raw Signal Analysis
```python
def analyze_feature_signals(df_features):
    """Analyze raw accelerometer signals for feature detection."""
    results = {}
    
    for feature_type in df_features['manualFeatureLabel'].unique():
        if pd.isna(feature_type):
            continue
            
        subset = df_features[df_features['manualFeatureLabel'] == feature_type]
        print(f"\n=== Analyzing {feature_type} ({len(subset)} samples) ===")
        
        vert_peaks = []
        vert_mins = []
        
        for idx, row in subset.iterrows():
            raw = row['rawData']
            if not raw or len(raw) < 5:
                continue
            
            # Extract vertical component (assuming gravity vector available)
            # For simplicity, use Z-axis as proxy
            z_values = [point[2] for point in raw]
            
            # Remove bias
            z_mean = np.mean(z_values)
            z_detrended = [z - z_mean for z in z_values]
            
            vert_peaks.append(max(z_detrended))
            vert_mins.append(min(z_detrended))
        
        if vert_peaks:
            results[feature_type] = {
                'peak_mean': np.mean(vert_peaks),
                'peak_std': np.std(vert_peaks),
                'peak_75th': np.percentile(vert_peaks, 75),
                'min_mean': np.mean(vert_mins),
                'min_std': np.std(vert_mins),
                'min_25th': np.percentile(vert_mins, 25)
            }
            
            print(f"Peak amplitude: {np.mean(vert_peaks):.2f} ± {np.std(vert_peaks):.2f}")
            print(f"Min amplitude: {np.mean(vert_mins):.2f} ± {np.std(vert_mins):.2f}")
    
    # Recommend thresholds
    recommendations = {}
    if 'speed_bump' in results:
        recommendations['symmetricBumpThreshold'] = results['speed_bump']['peak_75th']
    if 'pothole' in results:
        recommendations['potholeDipThreshold'] = results['pothole']['min_25th']
    if 'bump' in results:
        recommendations['bumpSpikeThreshold'] = results['bump']['peak_75th']
    
    print("\n=== Recommended Feature Detection Thresholds ===")
    for key, value in recommendations.items():
        default = DEFAULT_THRESHOLDS.get(key, 'N/A')
        print(f"{key}: {value:.3f} (default: {default})")
    
    return recommendations

if len(df_features) > 0:
    feature_thresholds = analyze_feature_signals(df_features)
else:
    feature_thresholds = {}
    print("No feature labels available for analysis")
```

### Cell 11: Validation - Confusion Matrix
```python
def validate_thresholds(df_quality, thresholds):
    """Validate optimized thresholds and generate confusion matrix."""
    def classify(row):
        if row['rms'] < thresholds['rmsSmoothMax'] and row['peakCount'] < thresholds['peakCountSmoothMax']:
            return 'smooth'
        elif row['rms'] < thresholds['rmsAverageMax'] and row['peakCount'] < thresholds['peakCountAverageMax']:
            return 'average'
        else:
            return 'rough'
    
    predictions = df_quality.apply(classify, axis=1)
    
    # Confusion matrix
    cm = confusion_matrix(df_quality['manualLabel'], predictions, 
                          labels=['smooth', 'average', 'rough'])
    
    plt.figure(figsize=(8, 6))
    sns.heatmap(cm, annot=True, fmt='d', cmap='Blues',
                xticklabels=['smooth', 'average', 'rough'],
                yticklabels=['smooth', 'average', 'rough'])
    plt.title('Confusion Matrix - Optimized Thresholds')
    plt.ylabel('True Label')
    plt.xlabel('Predicted Label')
    plt.savefig(f'{OUTPUT_DIR}/confusion_matrix.png', dpi=150)
    plt.show()
    
    # Classification report
    print("=== Classification Report ===")
    print(classification_report(df_quality['manualLabel'], predictions))
    
    # Accuracy by class
    for label in ['smooth', 'average', 'rough']:
        mask = df_quality['manualLabel'] == label
        if mask.sum() > 0:
            accuracy = (predictions[mask] == label).mean()
            print(f"{label} accuracy: {accuracy:.2%}")

validate_thresholds(df_quality, grid_thresholds)
```

### Cell 12: Cross-Validation
```python
def cross_validate_thresholds(df_quality, n_splits=5):
    """Perform k-fold cross-validation."""
    from sklearn.model_selection import KFold
    
    kf = KFold(n_splits=n_splits, shuffle=True, random_state=42)
    fold_scores = []
    
    for fold, (train_idx, test_idx) in enumerate(kf.split(df_quality)):
        train_df = df_quality.iloc[train_idx]
        test_df = df_quality.iloc[test_idx]
        
        # Optimize on train set
        train_thresholds, _ = grid_search_thresholds(train_df)
        
        # Evaluate on test set
        def classify(row):
            if row['rms'] < train_thresholds['rmsSmoothMax'] and row['peakCount'] < train_thresholds['peakCountSmoothMax']:
                return 'smooth'
            elif row['rms'] < train_thresholds['rmsAverageMax'] and row['peakCount'] < train_thresholds['peakCountAverageMax']:
                return 'average'
            else:
                return 'rough'
        
        predictions = test_df.apply(classify, axis=1)
        f1 = f1_score(test_df['manualLabel'], predictions, average='weighted')
        fold_scores.append(f1)
        print(f"Fold {fold + 1} F1 Score: {f1:.4f}")
    
    print(f"\n=== Cross-Validation Results ===")
    print(f"Mean F1 Score: {np.mean(fold_scores):.4f} ± {np.std(fold_scores):.4f}")
    
    return fold_scores

cv_scores = cross_validate_thresholds(df_quality)
```

### Cell 13: Generate Final Recommendations
```python
# Combine all methods
final_recommendations = {
    'rmsSmoothMax': grid_thresholds['rmsSmoothMax'],
    'rmsAverageMax': grid_thresholds['rmsAverageMax'],
    'peakThresholdZ': DEFAULT_THRESHOLDS['peakThresholdZ'],  # Keep default
    'symmetricBumpThreshold': feature_thresholds.get('symmetricBumpThreshold', DEFAULT_THRESHOLDS['symmetricBumpThreshold']),
    'potholeDipThreshold': feature_thresholds.get('potholeDipThreshold', DEFAULT_THRESHOLDS['potholeDipThreshold']),
    'bumpSpikeThreshold': feature_thresholds.get('bumpSpikeThreshold', DEFAULT_THRESHOLDS['bumpSpikeThreshold']),
    'peakCountSmoothMax': grid_thresholds['peakCountSmoothMax'],
    'peakCountAverageMax': grid_thresholds['peakCountAverageMax'],
    'movingAverageWindow': DEFAULT_THRESHOLDS['movingAverageWindow']  # Keep default
}

print("=== FINAL RECOMMENDED THRESHOLDS ===\n")
print("Parameter                    | Optimized | Default  | Change")
print("-" * 65)
for key in DEFAULT_THRESHOLDS.keys():
    optimized = final_recommendations[key]
    default = DEFAULT_THRESHOLDS[key]
    change = ((optimized - default) / default * 100) if default != 0 else 0
    print(f"{key:28} | {optimized:9.3f} | {default:8.3f} | {change:+6.1f}%")
```

### Cell 14: Export Optimized Profile
```python
def export_profile(thresholds, profile_name="Optimized"):
    """Export thresholds as vehicle profile JSON."""
    import time
    
    profile = {
        "name": profile_name,
        "calibration": {
            "rmsSmoothMax": float(thresholds['rmsSmoothMax']),
            "rmsAverageMax": float(thresholds['rmsAverageMax']),
            "peakThresholdZ": float(thresholds['peakThresholdZ']),
            "symmetricBumpThreshold": float(thresholds['symmetricBumpThreshold']),
            "potholeDipThreshold": float(thresholds['potholeDipThreshold']),
            "bumpSpikeThreshold": float(thresholds['bumpSpikeThreshold']),
            "peakCountSmoothMax": int(thresholds['peakCountSmoothMax']),
            "peakCountAverageMax": int(thresholds['peakCountAverageMax']),
            "movingAverageWindow": int(thresholds['movingAverageWindow'])
        },
        "createdAt": int(time.time() * 1000),
        "lastModified": int(time.time() * 1000)
    }
    
    filename = f"{OUTPUT_DIR}/{profile_name}.profile.json"
    with open(filename, 'w') as f:
        json.dump(profile, f, indent=2)
    
    print(f"Profile exported to: {filename}")
    return filename

# Export optimized profile
profile_file = export_profile(final_recommendations, "Optimized_Motorcycle")
print(f"\nYou can now load this profile in your app!")
```

### Cell 15: Summary Report
```python
# Generate summary report
report = f"""
=== CALIBRATION ANALYSIS SUMMARY ===

Dataset: {TRACK_FILE}
Total Samples: {len(df)}
Samples with Quality Labels: {len(df_quality)}
Samples with Feature Labels: {len(df_features)}

OPTIMIZED THRESHOLDS:
- rmsSmoothMax: {final_recommendations['rmsSmoothMax']:.3f}
- rmsAverageMax: {final_recommendations['rmsAverageMax']:.3f}
- peakCountSmoothMax: {final_recommendations['peakCountSmoothMax']}
- peakCountAverageMax: {final_recommendations['peakCountAverageMax']}
- symmetricBumpThreshold: {final_recommendations['symmetricBumpThreshold']:.3f}
- potholeDipThreshold: {final_recommendations['potholeDipThreshold']:.3f}
- bumpSpikeThreshold: {final_recommendations['bumpSpikeThreshold']:.3f}

PERFORMANCE:
- Grid Search F1 Score: {grid_f1:.4f}
- Cross-Validation F1: {np.mean(cv_scores):.4f} ± {np.std(cv_scores):.4f}

OUTPUT FILES:
- Optimized Profile: {profile_file}
- Visualizations: {OUTPUT_DIR}/
"""

print(report)

# Save report
with open(f'{OUTPUT_DIR}/analysis_report.txt', 'w') as f:
    f.write(report)

print(f"\nAnalysis complete! Check {OUTPUT_DIR}/ for all outputs.")
```

## Implementation Instructions

1. **Create the notebook**: Copy all cells above into a new Jupyter notebook at `c:/Code/SJGpsUtil/calibration_analysis.ipynb`

2. **Update configuration**: In Cell 2, set `TRACK_FILE` to your actual JSON track file path

3. **Run sequentially**: Execute cells in order from top to bottom

4. **Review outputs**: Check `calibration_results/` folder for:
   - Visualizations (PNG files)
   - Optimized profile JSON
   - Analysis report

5. **Load profile in app**: Copy the generated `.profile.json` file to your device's Downloads folder and use the Load button in the Calibration dialog

## Expected Runtime

- Small dataset (<500 samples): ~30 seconds
- Medium dataset (500-2000 samples): ~2-5 minutes
- Large dataset (>2000 samples): ~5-15 minutes

Grid search is the most time-consuming step; reduce search ranges if needed for faster results.
