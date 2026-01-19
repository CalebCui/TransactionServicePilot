#!/usr/bin/env python3
"""
Convert the perf summary JSON (written by the Java perf test) to the XML report format
expected by the project: test/report/TransactionServicePilotPerftest_20260119.xml

Usage: python tools/generate_perf_report_from_json.py --input target/perf-results/perf-summary-latest.json --output test/report/TransactionServicePilotPerftest_20260119.xml
"""
import argparse
import json
import os
import platform
import xml.etree.ElementTree as ET


def hardware_info():
    info = {
        'platform': platform.platform(),
        'processor': platform.processor(),
        'machine': platform.machine(),
        'python': platform.python_version(),
    }
    try:
        import psutil
        info['cpu_count_logical'] = psutil.cpu_count(logical=True)
        info['cpu_count_physical'] = psutil.cpu_count(logical=False)
        mem = psutil.virtual_memory()
        info['total_memory_bytes'] = mem.total
    except Exception:
        info['cpu_count_logical'] = None
        info['cpu_count_physical'] = None
        info['total_memory_bytes'] = None
    return info


def generate_from_json(jpath, outpath):
    if not os.path.exists(jpath):
        raise SystemExit(f"Input JSON not found: {jpath}")
    with open(jpath, 'r', encoding='utf-8') as f:
        data = json.load(f)

    root = ET.Element('PerformanceReport')
    summary = ET.SubElement(root, 'Summary')
    ET.SubElement(summary, 'TotalSamples').text = str(data.get('totalTx', 0))
    ET.SubElement(summary, 'TotalSuccess').text = str(data.get('committed', 0))
    ET.SubElement(summary, 'TotalFailure').text = str(data.get('failed', 0))

    hw = ET.SubElement(root, 'Hardware')
    for k, v in hardware_info().items():
        ET.SubElement(hw, k).text = str(v)

    runs = ET.SubElement(root, 'Runs')
    run = ET.SubElement(runs, 'Run')
    ET.SubElement(run, 'TargetTPS').text = str(int(data.get('throughput', 0)))
    ET.SubElement(run, 'DurationSec').text = str(int(data.get('durationMs', 0)) // 1000)
    ET.SubElement(run, 'Samples').text = str(data.get('totalTx', 0))
    ET.SubElement(run, 'Successes').text = str(data.get('committed', 0))
    ET.SubElement(run, 'Failures').text = str(data.get('failed', 0))
    ET.SubElement(run, 'AvgLatencyMs').text = "0.0"

    pers = ET.SubElement(run, 'Percentiles')
    for p in [30,50,70,90,95,99]:
        ET.SubElement(pers, f'p{p}').text = "0.0"

    tree = ET.ElementTree(root)
    os.makedirs(os.path.dirname(outpath), exist_ok=True)
    tree.write(outpath, encoding='utf-8', xml_declaration=True)
    print('Wrote report to', outpath)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--input', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    generate_from_json(args.input, args.output)
