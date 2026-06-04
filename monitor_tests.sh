#!/bin/bash
echo "Monitoring automated tests..."
end=$((SECONDS+600))
while [ $SECONDS -lt $end ]; do
    adb logcat -d | grep -iE "Test 200/200|Comprehensive Automotive AI Test Results" > /tmp/test_finish.txt
    if [ -s /tmp/test_finish.txt ]; then
        echo "Tests finished!"
        adb logcat -d | grep -iE "Test [0-9]+/200|FAIL" | tail -n 20
        exit 0
    fi
    sleep 5
done
echo "Timeout waiting for tests"
