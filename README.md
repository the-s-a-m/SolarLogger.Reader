# SolarLogger.Reader

1. Reads data from SolarMax devices by creating a string containing requests parameters (Explained in details in  SolarLogger.Reader/src/ch/solarlogger/SMReq.java)
2. Converts the received data into a json file by replacing the parameters with the full names.
3. Creates a POST to a webserver where the data is stored and statistics is done (SolarLogger.Viewer)
    https://github.com/the-s-a-m/SolarLogger.Viewer

Installation
1. Install java and SSH
2. Create file remotedebug.xml from remotedebug_example.xml
  2.1. Update data (ip, username, password) in remotedebug.xml
3. Create file solarreader.properties from solarreader_example.properties
  3.1 Update Sent details and SolarMax details
4. Create Service for generated .jar file 
