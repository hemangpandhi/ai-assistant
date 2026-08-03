import subprocess

out = subprocess.check_output(['adb', 'shell', 'dumpsys', 'user']).decode()
for line in out.split('\n'):
    if "UserInfo" in line:
        print(line.strip())
