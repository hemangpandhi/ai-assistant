import subprocess

def run_cmd(cmd):
    return subprocess.check_output(cmd, shell=True, text=True)

# Let's set fan speed using adb cmd car_service inject-vhal-event
# Wait, let's just trigger the intent or use dumpsys.
