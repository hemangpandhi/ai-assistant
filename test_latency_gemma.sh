#!/bin/bash
adb -s 3417105H805UGQ shell logcat -c
adb -s 3417105H805UGQ shell am force-stop com.example.gemininano

cat << 'XML' > app_prefs.xml
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="selected_model">/data/local/tmp/gemma-4-E2B-it.litertlm</string>
    <string name="backend_choice">GPU</string>
</map>
XML

adb -s 3417105H805UGQ push app_prefs.xml /data/local/tmp/app_prefs.xml
adb -s 3417105H805UGQ shell "run-as com.example.gemininano mkdir -p shared_prefs"
adb -s 3417105H805UGQ shell "run-as com.example.gemininano cp /data/local/tmp/app_prefs.xml shared_prefs/app_prefs.xml"

adb -s 3417105H805UGQ shell am start -n com.example.gemininano/.LocalLLMActivity
sleep 6
adb -s 3417105H805UGQ shell am start -n com.example.gemininano/.LocalLLMActivity --es test_prompt "increase temperature"
timeout 15 adb -s 3417105H805UGQ shell logcat | grep -i -E "TTFT|error|Attempting fallback"
