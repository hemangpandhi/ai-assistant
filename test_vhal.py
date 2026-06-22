import subprocess
import json

def run_cmd(cmd):
    return subprocess.check_output(cmd, shell=True, text=True)

# we can use 'dumpsys car_service' to get properties
try:
    print(run_cmd("adb -s 0.0.0.0:6526 shell dumpsys car_service --hal"))
except Exception as e:
    print(e)

