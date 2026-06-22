import os
import re

directory = "app/src/main/java/com/example/gemininano/handlers"

def refactor_file(filepath):
    with open(filepath, 'r') as f:
        content = f.read()

    # Step 1: Replace return type in interface and classes
    # We already did this with sed, but let's be safe
    # We also need to change the return values inside the functions to be ToolExecutionResult(...)

    with open(filepath, 'w') as f:
        f.write(content)

for filename in os.listdir(directory):
    if filename.endswith(".kt"):
        refactor_file(os.path.join(directory, filename))
