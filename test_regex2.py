import re
text = "Should I navigate you to a nearby gas station?"
regex = re.compile(r"^(.*?)([.!?]{2,}(?:\s+|$)|\n|(?<=[a-z])[.!?](?:\s+|$))")
print("Regex match:", regex.match(text))
