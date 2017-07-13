package ch.solarlogger;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.UnknownHostException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Properties;
import java.util.logging.Logger;

public class SolarMaxReader {
	static DateFormat dateFormat = new SimpleDateFormat("yyyy.MM.dd HH:mm:ss");
	private static Logger LOGGER = Logger.getLogger("SolarErrors");
	
	public static String requestSource = "FB";
	//static String[] destAddresses = {"01", "02"};
	public static String encoding = "64:";
	//public static String requestBody = "DD00;DM00;DY00;KDY;KHR";
	//public static String requestBody = "{FB;02;29|64:DD00;DM00;DY00;KDY;KHR|08F6}";
	//public static String requestBody = "KDY;KMT;KYR;KT0;TKK;PAC;UL1;IL1;UL2;IL2;UL3;IL3";

	public static void main(String[] args) throws IOException {

		String fileName = "solarreader.properties";
		Properties properties = new Properties();
		try {
			File jarFile = new File(SolarMaxReader.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath());
	        String inputFilePath = jarFile.getParent() + File.separator + fileName;         
	        FileInputStream inStream = new FileInputStream(new File(inputFilePath));
			properties.load(inStream);
			inStream.close();
		} catch (IOException | URISyntaxException e) {
			LOGGER.warning("Could not load config file " + fileName + " " + e.getMessage());
			return;
		}
		
		//Read properties
		//Request sleep time in seconds
		int sleeptime = Integer.parseInt(properties.get("sleeptime").toString());
		//SolarMax data (request data from solar device)
		String solarmaxip = properties.get("solarmaxip").toString();
		int solarmaxport = Integer.parseInt(properties.get("solarmaxport").toString());
		String[] solarmaxdevices = properties.get("solarmaxdevices").toString().split(",");
		String solarmaxrequestbody = properties.get("solarmaxrequestbody").toString();
		//Sendinformation to external server
		String webserverurl = properties.get("webserverurl").toString();
		String solarinstallationcode = properties.get("solarinstallationcode").toString();
		
		LOGGER.info("From Config " + fileName);
		LOGGER.info("sleeptime " + sleeptime);
		LOGGER.info("solarmaxip "+ solarmaxip);
		LOGGER.info("solarmaxport "+ solarmaxport);
		LOGGER.info("solarmaxdevices0 "+ solarmaxdevices[0]);
		LOGGER.info("solarmaxrequestbody "+ solarmaxrequestbody);
		LOGGER.info("webserverurl "+ webserverurl);
		LOGGER.info("solarinstallationcode "+ solarinstallationcode);
		
		//always repeat (as a service)
		while(true) {
			try {
				boolean couldSendData = true;
				StringBuilder jsonResult = new StringBuilder(2000);
				jsonResult.append("{ \"solarinstallationcode\": \"");
				jsonResult.append(solarinstallationcode);
				jsonResult.append("\", ");
				jsonResult.append("\"requesttime\": \"");
				jsonResult.append(dateFormat.format(new Date()));
				jsonResult.append("\", \"devices\": [");
				
				
				for(int i = 0; i < solarmaxdevices.length && couldSendData; i++) {
					String response = requestData(solarmaxip, solarmaxport, solarmaxdevices[i], solarmaxrequestbody);
					if(response.length() == 0) {
						LOGGER.warning(dateFormat.format(new Date()) + " Coult not connect to solar device "+ solarmaxdevices[i]);
						couldSendData = false;
						continue;
					}
					LOGGER.info("solarmax response " + response);
					response = response.substring(response.indexOf("|") + 4, response.lastIndexOf("|"));
					String[] splittedResponse = response.split(";");
					
					jsonResult.append("{\"device\": \"" + solarmaxdevices[i] + "\", ");
					
					for(int j = 0; j < splittedResponse.length; j++) {
						String[] keyValue = splittedResponse[j].split("=");
						String key = keyValue[0];
						key = SMReq.getEnumByString(key);
						System.out.println(key);
						String valueHex = keyValue[1];
						//fix error (crash)
						if(valueHex == null || valueHex.contains(",")) {
							couldSendData = false;
							continue;
						}
						float value = (float)Integer.parseInt(valueHex, 16);
						//Result on device for example 39.1 -> result in request = 391 
						//therefore the last digit removed
						if("ENERGY_DAY".equals(key)){
							value = value / 10f;
						} else if(key.contains("U_AC")){
							value = value / 10f;
						} else if(key.contains("I_AC")) {
							value = value / 100f;
						} else if(key.contains("U_DC")) {
							value = value / 10f;
						} else if(key.contains("I_DC")) {
							value = value / 100f;
						}
						String valueFormatted = (value % 1.0 != 0) ? String.format("%s", value) : String.format("%.00f",value);
						jsonResult.append("\"" + key + "\": " + valueFormatted + "");
						if(j != splittedResponse.length - 1) {
							jsonResult.append(", ");
						}
					}
		
					jsonResult.append("}");
					if(i != solarmaxdevices.length - 1) {
						jsonResult.append(", ");
					}
				}
				if(couldSendData) {
					jsonResult.append("]}");
					LOGGER.info("sending " + jsonResult.toString());
					sendPost(webserverurl, jsonResult.toString());
				}
				
			} catch(Exception e) {
				LOGGER.warning("Error in process " + e.getMessage());
			}
			long endtime= System.currentTimeMillis() + (sleeptime * 1000);
			while(System.currentTimeMillis() < endtime) {
				try {
					Thread.sleep(endtime - System.currentTimeMillis());
				} catch (InterruptedException e) {
					LOGGER.warning("Interrupted during sleep " + e.getMessage());
				}
			}
		}
	}
	
	public static String checksum(byte[] buf) {
        int crc = 0;
        for (byte b : buf) {
            crc += (int)b;
        }
        return Long.toHexString(crc).toUpperCase();
	}
	
	/**
	 * 
	 * @param destinationNr destination configured in solarmax device (01, 02, ... , 99)
	 * @return Response String from solarmax device
	 */
	public static String requestData(String solarmaxip, int solarmaxport, 
			String destinationNr, String requestBody) {
		/* request body length  
		 * {(1) + Src(3) + dest(3) + len(2) + |(1) + enconding(3) + body + |(1) + checksum(4) + }(1)
		 */
		String requestBodyLength = Integer.toHexString(13 + requestBody.length() + 6).toUpperCase();
		//bodyLength must be 2
		while(requestBodyLength.length() < 2) {
			requestBodyLength = "0" + requestBodyLength;
		}
		String completeBody = requestSource + ";" + destinationNr + ";" 
								+ requestBodyLength + "|" + encoding + requestBody + "|";
		//Create request from body
		String checksum = checksum(completeBody.getBytes());
		//checksum Length must be 4
		while(checksum.length() < 4) {
			checksum = "0" + checksum;
		}
		String request = "{" + completeBody + checksum + "}";
		
		LOGGER.info("Solarmax request to " + solarmaxip + ":" + solarmaxport + " request " + request);
		
		String response = "";
		try {
			Socket socket = new Socket();
			socket.connect(new InetSocketAddress(solarmaxip, solarmaxport), 1000);
			PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
			BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
			out.write(request);
			out.flush();
			//increased length because of missing data
			char[] charbuffer = new char[512];
			in.read(charbuffer);
			response = new String(charbuffer);
			socket.close();
		} catch (SocketTimeoutException e) {
			//timeout is ok (solar max is not longer available
			response = "";
		} catch (SecurityException e) {
			LOGGER.warning("Could not request data from solarmax (SecurityException): " + e.getMessage());
		} catch (UnknownHostException e) {
			LOGGER.warning("Could not request data from solarmax (UnknownHostException): " + e.getMessage());
		} catch (IOException e) {
			LOGGER.warning("Could not request data from solarmax (IOException): " + e.getMessage());
		}
		return response;
	}
	
	public static void sendPost(String url, String message) {
		final String USER_AGENT = "Mozilla/5.0";
		try {
			URL obj = new URL(url);
			HttpURLConnection con = (HttpURLConnection)obj.openConnection();
	
			//add reuqest header
			con.setRequestMethod("POST");
			con.setRequestProperty("User-Agent", USER_AGENT);
			con.setRequestProperty("Accept-Language", "en-US,en;q=0.5");
			con.setRequestProperty("Content-Type", "application/json");
	
			String urlParameters = message;
			
			// Send post request
			con.setDoOutput(true);
			DataOutputStream wr = new DataOutputStream(con.getOutputStream());
			wr.writeBytes(urlParameters);
			wr.flush();
			wr.close();
	
			int responseCode = con.getResponseCode();
			LOGGER.info("POST request to " + url + " with response " + responseCode);
	
			BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
			String inputLine;
			StringBuffer response = new StringBuffer();
	
			while ((inputLine = in.readLine()) != null) {
				response.append(inputLine);
			}
			in.close();
		} catch (Exception e) {
			LOGGER.warning("Could not send data: " + e.getMessage());
		}
	}
}
