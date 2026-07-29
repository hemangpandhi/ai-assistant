cp /data/local/tmp/Llama-3.2-3B-Instruct-LiteRT.litertlm files/
mkdir -p shared_prefs
echo '<?xml version="1.0" encoding="utf-8" standalone="yes" ?><map><string name="selected_model">/data/user/10/com.tcs.vehicleassistant/files/Llama-3.2-3B-Instruct-LiteRT.litertlm</string></map>' > shared_prefs/app_prefs.xml
