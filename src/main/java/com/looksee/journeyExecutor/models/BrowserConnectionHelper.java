package com.looksee.journeyExecutor.models;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Random;

import org.openqa.selenium.WebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.annotation.Retryable;

import com.looksee.journeyExecutor.models.enums.BrowserEnvironment;
import com.looksee.journeyExecutor.models.enums.BrowserType;

//@Retry(name="webdriver")
public class BrowserConnectionHelper {
	@SuppressWarnings("unused")
	private static Logger log = LoggerFactory.getLogger(BrowserConnectionHelper.class);

	private static int SELENIUM_HUB_IDX = 0;
	//GOOGLE CLOUD CLUSTER
	//private static final String[] CHROME_DISCOVERY_HUB_IP_ADDRESS = {"35.239.77.58:4444", "23.251.149.198:4444"};
	//private static final String[] FIREFOX_DISCOVERY_HUB_IP_ADDRESS = {"35.239.245.6:4444", "173.255.118.118:4444"};

	//private static final String[] RESOURCE_HEAVY_REQUEST_HUB_IP_ADDRESS = {"34.121.191.15:4444"};
	private static final String[] RESOURCE_HEAVY_REQUEST_HUB_IP_ADDRESS = {"selenium-chrome-uydih6tjpa-uc.a.run.app", 
																			"selenium-standalone-2-uydih6tjpa-uc.a.run.app", 
																			"selenium-standalone-3-uydih6tjpa-uc.a.run.app",
																			"selenium-standalone-4-uydih6tjpa-uc.a.run.app",
																			"selenium-standalone-5-uydih6tjpa-uc.a.run.app",
																			"selenium-standalone-6-uydih6tjpa-uc.a.run.app",
																			"selenium-standalone-7-uydih6tjpa-uc.a.run.app",
																			"selenium-standalone-8-uydih6tjpa-uc.a.run.app",
																			"selenium-standalone-9-uydih6tjpa-uc.a.run.app",
																			"selenium-standalone-10-uydih6tjpa-uc.a.run.app",
																			"selenium-standalone-11-uydih6tjpa-uc.a.run.app",
																			"selenium-standalone-12-uydih6tjpa-uc.a.run.app",
																			"selenium-standalone-13-uydih6tjpa-uc.a.run.app",
																			"selenium-standalone-14-uydih6tjpa-uc.a.run.app",
																			"selenium-standalone-15-uydih6tjpa-uc.a.run.app"};

	/*
	private static final String[] RESOURCE_HEAVY_REQUEST_HUB_IP_ADDRESS = {"35.224.152.230:4444",
    																	   "34.121.191.15:4444",
    																	   "34.70.80.131:4444"};
    */

	// PRODUCTION HUB ADDRESS
	//private static final String HUB_IP_ADDRESS= "142.93.192.184:4444";

	//STAGING HUB ADDRESS
	//private static final String HUB_IP_ADDRESS="159.89.226.116:4444";

	/**
	 * Creates a {@linkplain WebDriver} connection
	 * 
	 * @param browser
	 * @param environment
	 * 
	 * @return
	 * 
	 * @pre browser != null
	 * @pre environment != null
	 * 
	 * @throws MalformedURLException
	 */
    @Retryable
	public static Browser getConnection(BrowserType browser, BrowserEnvironment environment) throws Exception {
		assert browser != null;
		assert environment != null;
		
		URL hub_url = null;
		if(environment.equals(BrowserEnvironment.DISCOVERY) && "chrome".equalsIgnoreCase(browser.toString())){
			Random randomGenerator = new Random();
			//int randomInt = randomGenerator.nextInt(RESOURCE_HEAVY_REQUEST_HUB_IP_ADDRESS.length);
			hub_url = new URL( "https://"+RESOURCE_HEAVY_REQUEST_HUB_IP_ADDRESS[SELENIUM_HUB_IDX]+"/wd/hub");
		}
		else if(environment.equals(BrowserEnvironment.DISCOVERY) && "firefox".equalsIgnoreCase(browser.toString())){
			Random randomGenerator = new Random();
			//int randomInt = randomGenerator.nextInt(RESOURCE_HEAVY_REQUEST_HUB_IP_ADDRESS.length);
			hub_url = new URL( "https://"+RESOURCE_HEAVY_REQUEST_HUB_IP_ADDRESS[SELENIUM_HUB_IDX]+"/wd/hub");
		}
		SELENIUM_HUB_IDX++;
		if(SELENIUM_HUB_IDX >= RESOURCE_HEAVY_REQUEST_HUB_IP_ADDRESS.length){
			SELENIUM_HUB_IDX=0;
		}
		return new Browser(browser.toString(), hub_url);
	}
}
