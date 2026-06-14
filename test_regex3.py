import re
text = "Your fuel level is low. S"
regex = re.compile(r"^(.*?)([.!?]{2,}(?:\s+|$)|\n|(?<=[a-z])[.!?](?:\s+|$))")
print("Regex match:", regex.match(text))
