#!/usr/bin/env python3
"""
Command-line interface for the enhanced generic CSV exporter.
Usage: python csv_exporter_cli.py [options] <json_file>
"""

import argparse
import sys
import os
from pathlib import Path

# Add the scripts directory to the path
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from csv_exporter import CSVExporter


def main():
    """Main command-line interface."""
    parser = argparse.ArgumentParser(
        description='Export OBD2 trip data from JSON to CSV with generic field detection',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Basic export
  python csv_exporter_cli.py trip.json
  
  # Export with verbose logging
  python csv_exporter_cli.py -v trip.json
  
  # Export to specific directory
  python csv_exporter_cli.py -o output trip.json
  
  # Discover fields only
  python csv_exporter_cli.py --discover trip.json
  
  # Export fuel summary only
  python csv_exporter_cli.py --fuel-only trip.json
        """
    )
    
    parser.add_argument('json_file', help='Path to trip JSON file')
    parser.add_argument('-o', '--output', default='.', 
                       help='Output directory for CSV files (default: current directory)')
    parser.add_argument('-v', '--verbose', action='store_true',
                       help='Enable verbose logging with detailed progress')
    parser.add_argument('--discover', action='store_true',
                       help='Only discover and display available fields, no export')
    parser.add_argument('--fuel-only', action='store_true',
                       help='Export only fuel summary CSV')
    parser.add_argument('--list-fields', action='store_true',
                       help='List all discovered fields by category')
    
    args = parser.parse_args()
    
    # Validate input file
    json_path = Path(args.json_file)
    if not json_path.exists():
        print(f"Error: JSON file not found: {args.json_file}")
        sys.exit(1)
    
    if not json_path.suffix.lower() == '.json':
        print(f"Error: File must be a JSON file: {args.json_file}")
        sys.exit(1)
    
    # Validate output directory
    output_dir = Path(args.output)
    try:
        output_dir.mkdir(parents=True, exist_ok=True)
    except Exception as e:
        print(f"Error: Cannot create output directory {args.output}: {e}")
        sys.exit(1)
    
    print(f"Processing: {json_path}")
    print(f"Output directory: {output_dir.absolute()}")
    print()
    
    if args.discover:
        # Field discovery mode
        print("=== Field Discovery ===")
        fields = CSVExporter.discover_fields_from_json(str(json_path))
        
        total_fields = 0
        for category, field_set in fields.items():
            if field_set:
                print(f"\n{category.upper()} ({len(field_set)} fields):")
                for field in sorted(field_set):
                    print(f"  - {field}")
                total_fields += len(field_set)
        
        print(f"\nTotal fields discovered: {total_fields}")
        
        if args.list_fields:
            print("\n=== Field Summary ===")
            for category, field_set in fields.items():
                if field_set:
                    print(f"{category}: {', '.join(sorted(field_set))}")
    
    elif args.fuel_only:
        # Fuel-only export mode
        print("=== Fuel-Only Export ===")
        
        # First discover if fuel data exists
        fields = CSVExporter.discover_fields_from_json(str(json_path))
        if not fields['fuel']:
            print("No fuel data found in JSON file")
            sys.exit(1)
        
        print(f"Found {len(fields['fuel'])} fuel metrics:")
        for field in sorted(fields['fuel']):
            print(f"  - fuel_{field}")
        
        # Load data and export fuel summary
        try:
            import json
            with open(json_path, 'r', encoding='utf-8') as f:
                data = json.load(f)
            
            if 'samples' not in data:
                print("No samples found in JSON file")
                sys.exit(1)
            
            samples = data['samples']
            track_name = json_path.stem
            
            # Create logger for verbose mode
            logger = None
            if args.verbose:
                import logging
                logging.basicConfig(
                    level=logging.INFO,
                    format='%(asctime)s - %(levelname)s - %(message)s',
                    datefmt='%H:%M:%S'
                )
                logger = logging.getLogger(__name__)
            
            CSVExporter._export_fuel_summary(samples, track_name, str(output_dir), logger)
            
        except Exception as e:
            print(f"Error during fuel export: {e}")
            sys.exit(1)
    
    else:
        # Normal export mode
        print("=== CSV Export ===")
        
        if args.verbose:
            print("Verbose mode enabled - showing detailed progress")
            print()
        
        try:
            CSVExporter.export_from_json(
                json_file_path=str(json_path),
                output_dir=str(output_dir),
                verbose=args.verbose
            )
            
            print()
            print("=== Export Complete ===")
            
            # List generated files
            track_name = json_path.stem
            expected_files = [
                output_dir / f"{track_name}_comprehensive.csv",
                output_dir / f"{track_name}_fuel_summary.csv"
            ]
            
            print("Generated files:")
            for file_path in expected_files:
                if file_path.exists():
                    size = file_path.stat().st_size
                    print(f"  ✓ {file_path.name} ({size:,} bytes)")
                else:
                    print(f"  - {file_path.name} (not generated - no data)")
            
        except Exception as e:
            print(f"Error during export: {e}")
            sys.exit(1)


if __name__ == "__main__":
    main()
