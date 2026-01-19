import os
import glob
import xml.etree.ElementTree as ET
from datetime import datetime

root_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
reports_dir = os.path.join(root_dir, 'target', 'surefire-reports')
output_dir = os.path.join(root_dir, 'test', 'report')
output_file = os.path.join(output_dir, 'TransactionServicePilotIntegrationTest_20260119.xml')

os.makedirs(output_dir, exist_ok=True)

files = sorted(glob.glob(os.path.join(reports_dir, 'TEST-org.pilot.transactionservicepilot.integration.*.xml')))

total = 0
failures = 0
errors = 0
cases = []

for f in files:
    try:
        tree = ET.parse(f)
        ts = tree.getroot()
        try:
            file_tests = int(ts.attrib.get('tests', '0'))
            file_failures = int(ts.attrib.get('failures', '0'))
            file_errors = int(ts.attrib.get('errors', '0'))
        except Exception:
            file_tests = 0
            file_failures = 0
            file_errors = 0
        total += file_tests
        failures += file_failures
        errors += file_errors

        for tc in ts.findall('.//testcase'):
            name = tc.attrib.get('name')
            classname = tc.attrib.get('classname')
            time = tc.attrib.get('time')
            status = 'passed'
            message = ''
            fail = tc.find('failure')
            err = tc.find('error')
            if fail is not None:
                status = 'failed'
                message = (fail.attrib.get('message', '') or '') + '\n' + (fail.text or '')
            elif err is not None:
                status = 'error'
                message = (err.attrib.get('message', '') or '') + '\n' + (err.text or '')

            sout = tc.find('system-out')
            sout_text = (sout.text or '').strip() if sout is not None else ''

            cases.append({
                'classname': classname,
                'name': name,
                'time': time,
                'status': status,
                'message': message.strip(),
                'system-out': sout_text,
                'source': os.path.basename(f)
            })
    except Exception as e:
        cases.append({
            'classname': '', 'name': os.path.basename(f), 'time': '0', 'status': 'error', 'message': 'Failed to parse report: '+str(e), 'system-out': '', 'source': os.path.basename(f)
        })

passed = total - failures - errors

coverage_pct = 'N/A'
jacoco_xml = os.path.join(root_dir, 'target', 'site', 'jacoco', 'jacoco.xml')
if os.path.exists(jacoco_xml):
    try:
        jt = ET.parse(jacoco_xml).getroot()
        counters = jt.findall('.//counter')
        instr = None
        for c in counters:
            if c.attrib.get('type') == 'INSTRUCTION':
                instr = c
                break
        if instr is None:
            for c in counters:
                if c.attrib.get('type') == 'LINE':
                    instr = c
                    break
        if instr is not None:
            covered = int(instr.attrib.get('covered', '0'))
            missed = int(instr.attrib.get('missed', '0'))
            total_instr = covered + missed
            coverage_pct = f"{(covered/total_instr*100):.2f}%" if total_instr>0 else '0%'
    except Exception:
        coverage_pct = 'N/A'

r = ET.Element('integrationTestReport')
r.set('generatedAt', datetime.utcnow().strftime('%Y-%m-%dT%H:%M:%SZ'))
r.set('date', '2026-01-19')
summary = ET.SubElement(r, 'summary')
ET.SubElement(summary, 'totalTests').text = str(total)
ET.SubElement(summary, 'passed').text = str(passed)
ET.SubElement(summary, 'failures').text = str(failures)
ET.SubElement(summary, 'errors').text = str(errors)
ET.SubElement(summary, 'coverage').text = coverage_pct

cases_el = ET.SubElement(r, 'testcases')
for c in cases:
    tc = ET.SubElement(cases_el, 'testcase')
    tc.set('classname', c.get('classname',''))
    tc.set('name', c.get('name',''))
    if c.get('time') is not None:
        tc.set('time', c.get('time','0'))
    ET.SubElement(tc, 'description').text = ''
    ET.SubElement(tc, 'status').text = c.get('status','')
    ET.SubElement(tc, 'sourceReport').text = c.get('source','')
    ET.SubElement(tc, 'message').text = c.get('message','')
    ET.SubElement(tc, 'systemOut').text = c.get('system-out','')

ET.indent(r, space="  ")
Tree = ET.ElementTree(r)
Tree.write(output_file, encoding='utf-8', xml_declaration=True)
print('Wrote', output_file)
