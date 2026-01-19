#!/usr/bin/env python3
"""
Process JMeter JTL CSV result files and produce a consolidated XML report for the perf run.
Generates: test/report/TransactionServicePilotPerftest_20260119.xml

Usage: python tools/process_jmeter_results.py --inputs results1.jtl results2.jtl ... --output test/report/TransactionServicePilotPerftest_20260119.xml
"""
import argparse
import csv
import os
import sys
import xml.etree.ElementTree as ET
import statistics
import platform

PERCENTILES = [30,50,70,90,95,99]


def parse_jtl(path):
    latencies = []
    successes = 0
    failures = 0
    with open(path, 'r', encoding='utf-8') as f:
        reader = csv.DictReader(f)
        for row in reader:
            try:
                lat = float(row.get('latency') or row.get('time') or row.get('elapsed') or 0)
            except:
                lat = 0.0
            latencies.append(lat)
            succ = row.get('success')
            if succ is None:
                code = row.get('responseCode')
                if code is None or code.startswith('2'):
                    successes += 1
                else:
                    failures += 1
            else:
                if succ.lower() in ('true','1'):
                    successes += 1
                else:
                    failures += 1
    return {
        'samples': len(latencies),
        'latencies': latencies,
        'successes': successes,
        'failures': failures,
        'avg': statistics.mean(latencies) if latencies else 0.0
    }


def compute_percentiles(latencies):
    if not latencies:
        return {p: 0.0 for p in PERCENTILES}
    sorted_lat = sorted(latencies)
    out = {}
    n = len(sorted_lat)
    for p in PERCENTILES:
        k = (p/100.0) * (n-1)
        f = int(k)
        c = min(f+1, n-1)
        if f == c:
            val = sorted_lat[int(k)]
        else:
            d0 = sorted_lat[f] * (c-k)
            d1 = sorted_lat[c] * (k-f)
            val = d0 + d1
        out[p] = val
    return out


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


def generate_report(entries, outpath):
    root = ET.Element('PerformanceReport')
    summary = ET.SubElement(root, 'Summary')
    total_samples = sum(e['samples'] for e in entries)
    total_success = sum(e['successes'] for e in entries)
    total_fail = sum(e['failures'] for e in entries)
    ET.SubElement(summary, 'TotalSamples').text = str(total_samples)
    ET.SubElement(summary, 'TotalSuccess').text = str(total_success)
    ET.SubElement(summary, 'TotalFailure').text = str(total_fail)

    hw = ET.SubElement(root, 'Hardware')
    for k,v in hardware_info().items():
        ET.SubElement(hw, k).text = str(v)

    runs = ET.SubElement(root, 'Runs')
    for e in entries:
        run = ET.SubElement(runs, 'Run')
        ET.SubElement(run, 'TargetTPS').text = str(e['target_tps'])
        ET.SubElement(run, 'DurationSec').text = str(e['duration'])
        ET.SubElement(run, 'Samples').text = str(e['samples'])
        ET.SubElement(run, 'Successes').text = str(e['successes'])
        ET.SubElement(run, 'Failures').text = str(e['failures'])
        ET.SubElement(run, 'AvgLatencyMs').text = f"{e['avg']:.2f}"
        pers = compute_percentiles(e['latencies'])
        perNode = ET.SubElement(run, 'Percentiles')
        for p in PERCENTILES:
            ET.SubElement(perNode, f'p{p}').text = f"{pers[p]:.2f}"

    tree = ET.ElementTree(root)
    os.makedirs(os.path.dirname(outpath), exist_ok=True)
    tree.write(outpath, encoding='utf-8', xml_declaration=True)
    print('Wrote report to', outpath)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--inputs', nargs='+', required=True)
    parser.add_argument('--targets', nargs='+', required=True, help='matching target TPS for each input file')
    parser.add_argument('--duration', type=int, default=60)
    parser.add_argument('--output', default='test/report/TransactionServicePilotPerftest_20260119.xml')
    args = parser.parse_args()

    entries = []
    for inp, t in zip(args.inputs, args.targets):
        if not os.path.exists(inp):
            print('Missing input', inp, file=sys.stderr)
            sys.exit(2)
        data = parse_jtl(inp)
        data['target_tps'] = int(t)
        data['duration'] = args.duration
        entries.append(data)

    generate_report(entries, args.output)

if __name__ == '__main__':
    main()
