import re
text = "Your fuel level is low. Should I navigate you to a nearby gas station?"
regex = re.compile(r"^(.*?)([.!?]{2,}(?:\s+|$)|\n|(?<=[a-z])[.!?](?:\s+|$))")
print("Regex match:", regex.match(text))
