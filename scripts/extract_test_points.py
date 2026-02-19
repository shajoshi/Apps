#!/usr/bin/env python3
"""
Extract interesting GPS fix points from a recorded SJGpsUtil JSON tracking file
to produce a smaller test file suitable for automated testing.

Usage:
    python extract_test_points.py <input_file> <output_file> [--max-per-category N]

Categories selected:
  - Driver events: hard_brake, hard_accel, swerve, aggressive_corner
  - Road quality: rough, average
  - Features: pothole, bump
  - Low speed (< 6 km/h)
  - Smooth + normal baseline
  - First and last data points
"""

import json
import sys
import argparse
from collections import defaultdict


def classify_point(idx, point, total_count):
    """Return a list of category tags for a data point."""
    tags = []

    gps = point.get("gps", {})
    accel = point.get("accel", {})
    driver = point.get("driver", {})

    speed = gps.get("speed", 0)
    road_quality = accel.get("roadQuality")
    feature = accel.get("featureDetected")
    primary_event = driver.get("primaryEvent", "normal")
    events = driver.get("events", [])

    # Boundary points
    if idx == 0:
        tags.append("first_point")
    if idx == total_count - 1:
        tags.append("last_point")

    # Driver events (non-normal, non-low_speed)
    for evt in ["hard_brake", "hard_accel", "swerve", "aggressive_corner"]:
        if evt == primary_event or evt in events:
            tags.append(f"event:{evt}")

    # Road quality
    if road_quality == "rough":
        tags.append("road:rough")
    elif road_quality == "average":
        tags.append("road:average")

    # Features
    if feature:
        tags.append(f"feature:{feature}")

    # Low speed
    if speed < 6.0:
        tags.append("low_speed")

    # Smooth baseline (smooth road + normal driving + moving)
    if road_quality == "smooth" and primary_event == "normal" and speed >= 6.0:
        tags.append("baseline:smooth_normal")

    return tags


def extract_test_points(input_path, output_path, max_per_category=3):
    print(f"Reading {input_path}...")
    with open(input_path, "r", encoding="utf-8") as f:
        track = json.load(f)

    root = track.get("gpslogger2path", track)
    meta = root.get("meta", {})
    data = root.get("data", [])
    total = len(data)
    print(f"Total data points: {total}")

    # Classify every point
    category_indices = defaultdict(list)  # category -> [index, ...]
    point_tags = {}  # index -> [tags]

    for idx, point in enumerate(data):
        tags = classify_point(idx, point, total)
        point_tags[idx] = tags
        for tag in tags:
            category_indices[tag].append(idx)

    # Select points: up to max_per_category per category, avoiding duplicates
    selected_indices = set()
    selection_report = []

    # Define category priority order
    categories = [
        "first_point",
        "last_point",
        "event:hard_brake",
        "event:hard_accel",
        "event:swerve",
        "event:aggressive_corner",
        "road:rough",
        "road:average",
        "feature:pothole",
        "feature:bump",
        "low_speed",
        "baseline:smooth_normal",
    ]

    # Also pick up any categories we didn't anticipate
    for cat in sorted(category_indices.keys()):
        if cat not in categories:
            categories.append(cat)

    for cat in categories:
        indices = category_indices.get(cat, [])
        found = len(indices)
        # Prefer points not yet selected, but allow overlap
        new_indices = [i for i in indices if i not in selected_indices]
        # Pick from new first, then from already-selected if needed
        to_pick = new_indices[:max_per_category]
        if len(to_pick) < max_per_category:
            remaining = [i for i in indices if i not in set(to_pick)]
            to_pick.extend(remaining[: max_per_category - len(to_pick)])

        for i in to_pick:
            selected_indices.add(i)

        selection_report.append((cat, found, len(to_pick)))

    # Sort selected indices to preserve chronological order
    selected_sorted = sorted(selected_indices)

    # Build output JSON
    selected_data = [data[i] for i in selected_sorted]
    output = {
        "gpslogger2path": {
            "meta": meta,
            "data": selected_data,
        }
    }

    # Add summary if original had one
    if "summary" in root:
        output["gpslogger2path"]["summary"] = root["summary"]

    print(f"\nWriting {output_path}...")
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(output, f, indent=2)

    # Print report
    print(f"\n{'Category':<30} {'Found':>6} {'Selected':>9}")
    print("─" * 48)
    for cat, found, selected in selection_report:
        print(f"{cat:<30} {found:>6} {selected:>9}")
    print("─" * 48)
    print(f"{'Total selected':<30} {len(selected_sorted):>6} / {total}")

    # Print which tags each selected point has
    print(f"\nSelected point details:")
    print(f"{'Index':<8} {'Speed':>8} {'Event':<20} {'Quality':<10} {'Feature':<15} {'Tags'}")
    print("─" * 100)
    for idx in selected_sorted:
        pt = data[idx]
        gps = pt.get("gps", {})
        accel = pt.get("accel", {})
        driver = pt.get("driver", {})
        speed = gps.get("speed", 0)
        event = driver.get("primaryEvent", "?")
        quality = accel.get("roadQuality", "-")
        feature = accel.get("featureDetected", "-")
        tags = ", ".join(point_tags[idx]) if point_tags[idx] else "(none)"
        print(f"{idx:<8} {speed:>8.1f} {event:<20} {quality or '-':<10} {feature or '-':<15} {tags}")

    import os
    size_bytes = os.path.getsize(output_path)
    size_mb = size_bytes / (1024 * 1024)
    print(f"\nOutput file: {output_path} ({size_mb:.1f} MB)")


def main():
    parser = argparse.ArgumentParser(
        description="Extract interesting test points from a SJGpsUtil JSON tracking file"
    )
    parser.add_argument("input_file", help="Path to the input JSON tracking file")
    parser.add_argument("output_file", help="Path for the output filtered JSON file")
    parser.add_argument(
        "--max-per-category",
        type=int,
        default=3,
        help="Maximum points to select per category (default: 3)",
    )
    args = parser.parse_args()
    extract_test_points(args.input_file, args.output_file, args.max_per_category)


if __name__ == "__main__":
    main()
