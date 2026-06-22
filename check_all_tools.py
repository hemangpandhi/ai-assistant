import json

with open("app/src/main/assets/vehicle_skills_registry_v2.0.json", "r") as f:
    data = json.load(f)

print("Checking Tools...")
for tool in data.get("tools", []):
    htype = tool.get("handler_type")
    key = tool.get("handler_key")
    if not htype:
        print(f"ERROR: Tool {key} is missing handler_type!")
    
    if htype == "GENERIC_VHAL_WRITE":
        if "property_id" not in tool:
            print(f"ERROR: GENERIC_VHAL_WRITE tool {key} is missing property_id!")
        if "data_type" not in tool:
            print(f"ERROR: GENERIC_VHAL_WRITE tool {key} is missing data_type!")
        if "value_to_write" not in tool and "prompt_string" not in tool:
            print(f"ERROR: GENERIC_VHAL_WRITE tool {key} requires a value_to_write or prompt_string parameter!")

    if htype == "NATIVE_VHAL":
        print(f"WARNING: Unhandled NATIVE_VHAL tool found: {key}")

print("Check Complete.")
