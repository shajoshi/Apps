# Complete Scripts Command-Line Usage Guide

## Overview

This guide covers all command-line scripts available in the OBD2App scripts directory, their arguments, and usage examples.

## Quick Start

```bash
# Navigate to scripts directory
cd c:\Users\ShaileshJoshi\AndroidStudioProjects\Apps\OBD2App\scripts

# List all available scripts
python --help 2>&1 | grep -E "\.py" || ls *.py
```

## Script Categories

### 📊 **Data Export & Conversion**

#### 1. csv_exporter_cli.py - Enhanced CSV Exporter
**Purpose**: Export OBD2 trip data from JSON to CSV with generic field detection and fuel metrics support

```bash
# Basic usage
python csv_exporter_cli.py trip.json

# Export with verbose logging
python csv_exporter_cli.py -v trip.json

# Export to specific directory
python csv_exporter_cli.py -o output_folder trip.json

# Field discovery only
python csv_exporter_cli.py --discover trip.json

# Export fuel summary only
python csv_exporter_cli.py --fuel-only trip.json

# List all fields by category
python csv_exporter_cli.py --discover --list-fields trip.json
```

**Arguments**:
- `json_file` - Path to trip JSON file (required)
- `-v, --verbose` - Enable detailed logging
- `-o, --output` - Output directory (default: current directory)
- `--discover` - Show available fields only
- `--fuel-only` - Export only fuel summary CSV
- `--list-fields` - List fields by category

**Output Files**:
- `{track_name}_comprehensive.csv` - All metrics (66+ columns)
- `{track_name}_fuel_summary.csv` - Fuel-specific metrics (10+ columns)

---

#### 2. track_to_csv.py - Basic CSV Converter
**Purpose**: Convert OBD2 JSON track files to basic CSV format

```bash
# Basic conversion
python track_to_csv.py track.json

# Specify output file
python track_to_csv.py track.json -o output.csv

# Include all possible fields
python track_to_csv.py track.json --all-fields
```

**Arguments**:
- `input_file` - Input JSON track file (required)
- `-o, --output` - Output CSV file (default: input_file.csv)
- `--all-fields` - Include all possible fields, even if not present in data

---

### 🔧 **Data Processing & Analysis**

#### 3. recalculate_metrics.py - Metrics Recalculation
**Purpose**: Recalculate OBD2 track metrics from raw data with validation

```bash
# Basic recalculation
python recalculate_metrics.py -p profile.json -t track1.json track2.json

# Generate all outputs
python recalculate_metrics.py -p profile.json -t track.json --all

# Generate specific outputs
python recalculate_metrics.py -p profile.json -t track.json --kml --plots --csv

# Validate with custom tolerance
python recalculate_metrics.py -p profile.json -t track.json --validate --tolerance 2.5
```

**Arguments**:
- `-p, --profile` - Vehicle profile JSON file (required)
- `-t, --tracks` - One or more track JSON files (required)
- `-o, --output` - Output directory (default: current directory)
- `--kml` - Generate KML files for visualization
- `--plots` - Generate distribution plots and charts
- `--csv` - Export data to CSV files
- `--validate` - Validate recalculated metrics
- `--tolerance` - Tolerance percentage for validation (default: 5.0)
- `--power` - Include power calculations
- `--all` - Enable all optional features

---

#### 4. analyze_track.py - Track Analysis
**Purpose**: Analyze OBD2 track data and generate KML with color-coded metrics

```bash
# Analyze specific metric
python analyze_track.py -m obd.rpm track.json

# Analyze multiple metrics
python analyze_track.py -m obd.rpm,obd.speedKmh,obd.mafGs track.json

# Different segmentation strategies
python analyze_track.py -m obd.speedKmh -s std_dev track.json
python analyze_track.py -m obd.speedKmh -s kmeans track.json
```

**Arguments**:
- `-m, --metric` - Metric(s) to analyze (required, comma-separated)
- `-s, --strategy` - Color segmentation strategy (default: percentile)
  - Choices: `percentile`, `std_dev`, `iqr`, `kmeans`, `natural`
- `filename` - JSON track file to analyze (required)

---

### 🧪 **Testing & Examples**

#### 5. test_generic_export.py - Generic Export Testing
**Purpose**: Test the enhanced CSV exporter functionality

```bash
# Run basic test
python test_generic_export.py

# Field discovery test
python test_generic_export.py --discover
```

**Arguments**:
- `--discover` - Show field discovery analysis instead of export

---

#### 6. usage_examples.py - Usage Examples
**Purpose**: Display usage examples for all scripts (demonstration only)

```bash
# Show all usage examples
python usage_examples.py
```

**Arguments**: None (demonstration script)

---

## Utility Scripts (No Command-Line Interface)

The following scripts are utility modules and don't have direct command-line interfaces:

- `obd_types.py` - Data type definitions
- `obd_calculators.py` - OBD2 calculation utilities
- `track_loader.py` - Track data loading utilities
- `metrics_validator.py` - Metrics validation utilities
- `plot_generator.py` - Plot generation utilities
- `kml_generator.py` - KML generation utilities
- `report_generator.py` - Report generation utilities
- `csv_exporter.py` - Core CSV export functionality
- `json_repair.py` - JSON repair utilities

## Common Usage Patterns

### Data Analysis Workflow
```bash
# 1. Discover available data
python csv_exporter_cli.py --discover track.json

# 2. Export comprehensive data
python csv_exporter_cli.py -v track.json -o analysis

# 3. Analyze specific metrics
python analyze_track.py -m obd.rpm,obd.speedKmh track.json

# 4. Recalculate with validation
python recalculate_metrics.py -p profile.json -t track.json --validate --plots
```

### Batch Processing
```bash
# Process multiple tracks (PowerShell)
Get-ChildItem "data\*.json" | ForEach-Object {
    python csv_exporter_cli.py $_.FullName -o "exports"
}

# Process multiple tracks (Windows CMD)
for %f in (data\*.json) do python csv_exporter_cli.py "%f" -o "exports"
```

### Fuel Analysis
```bash
# Export fuel-specific data
python csv_exporter_cli.py --fuel-only track.json -o fuel_analysis

# Comprehensive fuel analysis
python csv_exporter_cli.py -v track.json --discover --list-fields
```

### Data Validation
```bash
# Validate metrics integrity
python recalculate_metrics.py -p profile.json -t track.json --validate --tolerance 1.0

# Generate validation reports
python recalculate_metrics.py -p profile.json -t track.json --all --csv
```

## Error Handling

All scripts provide helpful error messages:

```bash
# File not found
Error: JSON file not found: missing.json

# Invalid arguments
usage: csv_exporter_cli.py [-h] [-o OUTPUT] [-v] [--discover] [--fuel-only] [--list-fields] json_file
csv_exporter_cli.py: error: the following arguments are required: json_file

# Missing dependencies
Warning: numpy, pandas, or seaborn not available. Skipping correlation plots.
```

## Dependencies

Some scripts require optional dependencies:

- **matplotlib** - For plotting (`recalculate_metrics.py --plots`)
- **numpy** - For statistical calculations (`analyze_track.py`)
- **pandas** - For data analysis (`analyze_track.py`)
- **seaborn** - For advanced plots (`recalculate_metrics.py`)

Install with:
```bash
pip install matplotlib numpy pandas seaborn
```

## Output Files by Script

| Script | Output Files | Description |
|--------|--------------|-------------|
| `csv_exporter_cli.py` | `*_comprehensive.csv`, `*_fuel_summary.csv` | Full and fuel-specific CSV exports |
| `track_to_csv.py` | `*.csv` | Basic CSV conversion |
| `recalculate_metrics.py` | `*.kml`, `*.png`, `*.csv`, `*.json` | KML, plots, CSV, recalculated data |
| `analyze_track.py` | `*.kml` | Color-coded track visualization |

This comprehensive guide covers all command-line scripts in the OBD2App toolkit with their arguments, usage examples, and output formats.
