"""
JSON repair utilities for corrupted track files.
Attempts to fix common JSON formatting issues.
"""

import json
import re
from typing import Optional, Tuple


class JSONRepair:
    """Repairs common JSON formatting issues in track files."""
    
    @staticmethod
    def attempt_repair(track_path: str) -> Tuple[bool, str]:
        """
        Attempt to repair a corrupted JSON file.
        
        Args:
            track_path: Path to the corrupted JSON file
            
        Returns:
            Tuple of (success: bool, message: str)
        """
        try:
            with open(track_path, 'r', encoding='utf-8') as f:
                content = f.read()
            
            original_content = content
            
            # Try common repairs in order
            repairs = [
                ("Fix trailing commas", JSONRepair._fix_trailing_commas),
                ("Fix missing quotes", JSONRepair._fix_missing_quotes),
                ("Fix control characters", JSONRepair._fix_control_characters),
                ("Fix duplicate commas", JSONRepair._fix_duplicate_commas),
                ("Fix bracket mismatches", JSONRepair._fix_bracket_mismatches)
            ]
            
            for repair_name, repair_func in repairs:
                try:
                    content = repair_func(content)
                    # Test if the repair worked
                    json.loads(content)
                    
                    # If repair succeeded, save the fixed file
                    backup_path = track_path + ".backup"
                    with open(backup_path, 'w', encoding='utf-8') as f:
                        f.write(original_content)
                    
                    with open(track_path, 'w', encoding='utf-8') as f:
                        f.write(content)
                    
                    return True, f"Successfully repaired JSON using: {repair_name}"
                    
                except json.JSONDecodeError:
                    # This repair didn't work, try the next one
                    content = original_content
                    continue
            
            return False, "Could not repair JSON with common fixes"
            
        except Exception as e:
            return False, f"Error during repair: {e}"
    
    @staticmethod
    def _fix_trailing_commas(content: str) -> str:
        """Fix trailing commas in JSON objects/arrays."""
        # Remove trailing commas before closing brackets
        content = re.sub(r',(\s*[}\]])', r'\1', content)
        return content
    
    @staticmethod
    def _fix_missing_quotes(content: str) -> str:
        """Fix missing quotes around property names."""
        # This is a simplified fix - may not catch all cases
        # Fix unquoted property names (basic pattern)
        content = re.sub(r'(\s*)([a-zA-Z_][a-zA-Z0-9_]*)\s*:', r'\1"\2":', content)
        return content
    
    @staticmethod
    def _fix_control_characters(content: str) -> str:
        """Fix control characters in JSON strings."""
        # Remove or escape control characters
        content = re.sub(r'[\x00-\x1f\x7f-\x9f]', '', content)
        return content
    
    @staticmethod
    def _fix_duplicate_commas(content: str) -> str:
        """Fix duplicate commas in JSON."""
        content = re.sub(r',\s*,', ',', content)
        return content
    
    @staticmethod
    def _fix_bracket_mismatches(content: str) -> str:
        """Attempt to fix bracket mismatches (basic fix)."""
        # Count brackets
        open_braces = content.count('{')
        close_braces = content.count('}')
        open_brackets = content.count('[')
        close_brackets = content.count(']')
        
        # Add missing closing brackets at the end
        if open_braces > close_braces:
            content += '}' * (open_braces - close_braces)
        
        if open_brackets > close_brackets:
            content += ']' * (open_brackets - close_brackets)
        
        return content
    
    @staticmethod
    def validate_json_structure(content: str) -> Tuple[bool, Optional[str]]:
        """
        Validate JSON structure for track files.
        
        Args:
            content: JSON content as string
            
        Returns:
            Tuple of (is_valid: bool, error_message: Optional[str])
        """
        try:
            data = json.loads(content)
            
            # Check for required structure
            if not isinstance(data, dict):
                return False, "Root element must be an object"
            
            # Check for samples array
            if 'samples' not in data:
                return False, "Missing 'samples' array"
            
            if not isinstance(data['samples'], list):
                return False, "'samples' must be an array"
            
            # Check sample structure for first few items
            for i, sample in enumerate(data['samples'][:5]):
                if not isinstance(sample, dict):
                    return False, f"Sample {i} must be an object"
            
            return True, None
            
        except json.JSONDecodeError as e:
            return False, f"JSON parsing error: {e.msg}"
        except Exception as e:
            return False, f"Validation error: {e}"
    
    @staticmethod
    def extract_partial_samples(content: str) -> Optional[list]:
        """
        Attempt to extract partial samples from corrupted JSON.
        
        Args:
            content: JSON content as string
            
        Returns:
            List of samples if extraction successful, None otherwise
        """
        try:
            # Try to find samples array using regex
            samples_match = re.search(r'"samples"\s*:\s*\[(.*?)\]', content, re.DOTALL)
            if samples_match:
                samples_content = '[' + samples_match.group(1) + ']'
                try:
                    return json.loads(samples_content)
                except json.JSONDecodeError:
                    pass
            
            # Try to extract individual sample objects
            sample_pattern = r'\{[^{}]*\}'
            samples = re.findall(sample_pattern, content)
            
            valid_samples = []
            for sample_str in samples:
                try:
                    sample = json.loads(sample_str)
                    valid_samples.append(sample)
                except json.JSONDecodeError:
                    continue
            
            return valid_samples if valid_samples else None
            
        except Exception:
            return None
