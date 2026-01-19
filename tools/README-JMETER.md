JMeter test plan for TransactionServicePilot

This folder contains a basic JMeter test plan: `TransactionServicePilotTestPlan.jmx` which posts transactions to the running service endpoint `/v1/transactions`.

Prerequisites
- JMeter installed locally (>= 5.4.1). Download: https://jmeter.apache.org/
- The Spring Boot service running locally and reachable (default: http://localhost:8080)

Example command (CLI) to run the plan and write JTL results:

On Windows (PowerShell):

jmeter -n -t tools\TransactionServicePilotTestPlan.jmx -Jserver.host=localhost -Jserver.port=8080 -Jthreads=50 -Jduration=60 -Jtarget.tps=100 -JaccountId=1 -Jamount=1.00 -Jresult.jtl=target\perf-results\jmeter-run-100.jtl

Explanation of properties:
- server.host / server.port / server.protocol: target server for requests
- threads: number of JMeter threads (virtual users)
- duration: duration in seconds
- target.tps: target transactions per second (converted to TPM inside the JMX as TPS*60)
- accountId: account to use in the transaction payload
- amount: transaction amount
- result.jtl: output JTL file path

After run: process JTL(s) into XML report using the provided script:

python tools/process_jmeter_results.py --inputs target\perf-results\jmeter-run-100.jtl ... --targets 100 ... --duration 60 --output test\report\TransactionServicePilotPerftest_20260119.xml

Notes
- The provided JMX uses a Constant Throughput Timer shaped by the `target.tps` property. For high TPS (>=1000) you likely need multiple load generators or a powerful machine, and may need to tune `threads` and `ramp_time`.
- If JMeter is not available on this machine, save the `.jmx` and run it on your local environment with the CLI command above.
