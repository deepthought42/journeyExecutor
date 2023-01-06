package com.looksee.journeyExecutor;

/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// [START cloudrun_pubsub_handler]
// [START run_pubsub_handler]
import java.net.URL;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import org.openqa.selenium.By;
import org.openqa.selenium.ElementNotInteractableException;
import org.openqa.selenium.WebElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.looksee.browsing.ActionFactory;
import com.looksee.journeyExecutor.gcp.PubSubDiscardedJourneyPublisherImpl;
import com.looksee.journeyExecutor.gcp.PubSubErrorPublisherImpl;
import com.looksee.journeyExecutor.gcp.PubSubJourneyVerifiedPublisherImpl;
import com.looksee.journeyExecutor.gcp.PubSubPageBuiltPublisherImpl;
import com.looksee.journeyExecutor.mapper.Body;
import com.looksee.journeyExecutor.models.Browser;
import com.looksee.journeyExecutor.models.Domain;
import com.looksee.journeyExecutor.models.ElementState;
import com.looksee.journeyExecutor.models.PageState;
import com.looksee.journeyExecutor.models.enums.Action;
import com.looksee.journeyExecutor.models.enums.BrowserEnvironment;
import com.looksee.journeyExecutor.models.enums.BrowserType;
import com.looksee.journeyExecutor.models.enums.PathStatus;
import com.looksee.journeyExecutor.models.journeys.Journey;
import com.looksee.journeyExecutor.models.journeys.LoginStep;
import com.looksee.journeyExecutor.models.journeys.SimpleStep;
import com.looksee.journeyExecutor.models.journeys.Step;
import com.looksee.journeyExecutor.models.message.DiscardedJourneyMessage;
import com.looksee.journeyExecutor.models.message.JourneyMessage;
import com.looksee.journeyExecutor.models.message.VerifiedJourneyMessage;
import com.looksee.journeyExecutor.services.AuditRecordService;
import com.looksee.journeyExecutor.services.BrowserService;
import com.looksee.journeyExecutor.services.DomainService;
import com.looksee.journeyExecutor.services.PageStateService;
import com.looksee.journeyExecutor.services.StepService;
import com.looksee.utils.BrowserUtils;
import com.looksee.utils.ElementStateUtils;
import com.looksee.utils.PathUtils;

// PubsubController consumes a Pub/Sub message.
@RestController
public class AuditController {
	private static Logger log = LoggerFactory.getLogger(AuditController.class);

	@Autowired
	private BrowserService browser_service;
	
	@Autowired
	private PageStateService page_state_service;
	
	@Autowired
	private DomainService domain_service;
	
	@Autowired
	private StepService step_service;
	
	@Autowired
	private AuditRecordService audit_record_service;
	
	@Autowired
	private PubSubErrorPublisherImpl error_topic;
	
	@Autowired
	private PubSubJourneyVerifiedPublisherImpl journey_verified_topic;
	
	@Autowired
	private PubSubDiscardedJourneyPublisherImpl discarded_journey_topic;
	
	@Autowired
	private PubSubPageBuiltPublisherImpl page_built_topic;
	
	@RequestMapping(value = "/", method = RequestMethod.POST)
	public ResponseEntity receiveMessage(@RequestBody Body body) 
			throws JsonMappingException, JsonProcessingException, ExecutionException, InterruptedException 
	{	
		Body.Message message = body.getMessage();
		String data = message.getData();
	    String target = !data.isEmpty() ? new String(Base64.getDecoder().decode(data)) : "";
	    ObjectMapper input_mapper = new ObjectMapper();
	    JourneyMessage journey_msg = input_mapper.readValue(target, JourneyMessage.class);
        
	    log.warn("message " + journey_msg);
	    Journey journey = journey_msg.getJourney();
	    
	   	log.warn("Received journey :: "+journey.getId());
		List<Step> steps = new ArrayList<>(journey.getSteps());
		
		try {
			PageState page_state = iterateThroughJourneySteps(steps, 
															  journey_msg.getDomainId(), 
															  journey_msg.getAccountId(), 
															  journey_msg.getDomainAuditRecordId());
			Step final_step = steps.get(steps.size()-1);
			final_step.setEndPage(page_state);
			final_step.setKey(steps.get(steps.size()-1).generateKey());
			
			if(existsInJourney(steps, final_step)) {
				return null;
			}
			else {
				final_step = step_service.save(final_step);
				page_built_topic.publish(page_state.getUrl());
				steps.set(steps.size()-1, final_step);
			}

			return new ResponseEntity<String>("Successfully sent message to audit manager", HttpStatus.OK);
		}
		catch(Exception e) {
			log.error("Exception occurred during journey execution");
			//e.printStackTrace();
			
			//TODO send error message to error topic
			error_topic.publish("");
		}
		
		log.warn("done processing journey :: "+journey.getId());
		processIfStepsShouldBeExpanded(journey.getId(), 
										journey, 
										journey_msg.getDomainId(), 
										journey_msg.getAccountId(),
										journey_msg.getDomainAuditRecordId());

		return new ResponseEntity<String>("Error occurred while executing journey", HttpStatus.INTERNAL_SERVER_ERROR);
	}
	
	/**
	 * Checks if the last step in the list of steps has matching start and end page states
	 * 
	 * @param steps
	 * @param domain_id
	 * @param account_id
	 * @param audit_record_id
	 * @throws JsonProcessingException 
	 * @throws InterruptedException 
	 * @throws ExecutionException 
	 */
	private void processIfStepsShouldBeExpanded(long journey_id, 
												Journey journey, 
												long domain_id, 
												long account_id, 
												long audit_record_id 
			) throws JsonProcessingException, ExecutionException, InterruptedException 
	{
		try {
			PageState second_to_last_page = PathUtils.getSecondToLastPageState(journey.getSteps());
			PageState final_page = PathUtils.getLastPageState(journey.getSteps());
			//is end_page PageState different from second to last PageState
			if(final_page == null || final_page.equals(second_to_last_page)) {
				//tell parent that we processed a journey that is being discarded
				DiscardedJourneyMessage journey_message = new DiscardedJourneyMessage(	journey_id, 
																						BrowserType.CHROME, 
																						domain_id,
																						account_id, 
																						audit_record_id);
				
			   	JsonMapper mapper = new JsonMapper().builder().addModule(new JavaTimeModule()).build();
				String audit_record_json = mapper.writeValueAsString(journey_message);
				log.warn("audit progress update = "+audit_record_json);
				//TODO: SEND PUB SUB MESSAGE THAT AUDIT RECORD NOT FOUND WITH PAGE DATA EXTRACTION MESSAGE
			    discarded_journey_topic.publish(audit_record_json);
			}
			else {
				VerifiedJourneyMessage journey_message = new VerifiedJourneyMessage(journey_id,
																					journey,
																					PathStatus.EXAMINED, 
																					BrowserType.CHROME, 
																					domain_id,
																					account_id, 
																					audit_record_id);
				
				JsonMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
				String audit_record_json = mapper.writeValueAsString(journey_message);
				log.warn("audit progress update = "+audit_record_json);
				//TODO: SEND PUB SUB MESSAGE THAT AUDIT RECORD NOT FOUND WITH PAGE DATA EXTRACTION MESSAGE
			    journey_verified_topic.publish(audit_record_json);
			}
		}catch(Exception e) {
			e.printStackTrace();
			DiscardedJourneyMessage journey_message = new DiscardedJourneyMessage(	journey_id, 
																					BrowserType.CHROME, 
																					domain_id,
																					account_id, 
																					audit_record_id);

			JsonMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
			String audit_record_json = mapper.writeValueAsString(journey_message);
			log.warn("audit progress update = "+audit_record_json);
			//TODO: SEND PUB SUB MESSAGE THAT AUDIT RECORD NOT FOUND WITH PAGE DATA EXTRACTION MESSAGE
			discarded_journey_topic.publish(audit_record_json);		
	    }
	}

	/**
	 * Constructs a {@link PageState page} including all {@link ElementState elements} on the page as a {@linkplain List}
	 * 
	 * @param audit_record_id
	 * @param browser
	 * @return
	 * @throws Exception
	 * 
	 * @pre browser != null
	 */
	private PageState buildPage(long audit_record_id, Browser browser) throws Exception {
		assert browser != null;
		
		URL current_url = new URL(browser.getDriver().getCurrentUrl());
		String url_without_protocol = BrowserUtils.getPageUrl(current_url.toString());
	
		PageState page_state = audit_record_service.findPageWithUrl(audit_record_id, url_without_protocol);
		if(page_state == null) {
			page_state = browser_service.performBuildPageProcess(browser);
			page_state = page_state_service.save(page_state);
			audit_record_service.addPageToAuditRecord(audit_record_id, page_state.getId());
		}
		
		List<String> xpaths = browser_service.extractAllUniqueElementXpaths(page_state.getSrc());
		List<ElementState> element_states = browser_service.buildPageElementsWithoutNavigation( page_state, 
																								xpaths,
																								audit_record_id,
																								page_state.getFullPageHeight(),
																								browser);

		element_states = ElementStateUtils.enrichBackgroundColor(element_states).collect(Collectors.toList());
		page_state.setElements(element_states);
		
		return page_state;
	}
	
	/**
	 * Executes steps in sequence and builds the resulting page state
	 * 
	 * @param steps
	 * @param domain_id
	 * @param account_id
	 * @param audit_record_id
	 * @return {@link PageState} or null if final page is an external page
	 * @throws Exception
	 * 
	 * @pre steps != null
	 * @pre !steps.isEmpty()
	 */
	private PageState iterateThroughJourneySteps( List<Step> steps, 
												  long domain_id, 
												  long account_id, 
												  long audit_record_id
	) throws Exception {
		assert steps != null;
		assert !steps.isEmpty();
		
		boolean complete = false;
		int count = 0;
		PageState page = null;
		do {
			Browser browser = null;
			try {
				browser = browser_service.getConnection(BrowserType.CHROME, BrowserEnvironment.DISCOVERY);
				
				performJourneyStepsInBrowser(steps, browser);
				Domain domain = domain_service.findById(domain_id).get();
				//if page url already exists for domain audit record then load that page state instead of performing a build
				//NOTE: This patch is meant to reduce duplication of page builds and will not catch A/B tests
				String current_url = BrowserUtils.getPageUrl(browser.getDriver().getCurrentUrl());
				if(BrowserUtils.isExternalLink(domain.getUrl(), current_url)) {
					log.warn("current url is external : "+current_url);
					return null;
				}
				
				page = buildPage(audit_record_id, browser);				
				complete = true;
			}
			catch(ElementNotInteractableException e ) {
				log.error("Element not interactable exception occurred!");
				//e.printStackTrace();
				complete=true;
			}
			catch(Exception e) {
				log.error("Error occurred while iterating through journey steps.");
				//e.printStackTrace();
			}
			finally {
				if(browser != null) {
					browser.close();
				}
			}
			count++;
		}while(!complete && count < 20);
		
		return page;
	}

	/**
	 * Creates {@link Browser} connection and performs journey steps
	 * 
	 * @param steps
	 * @param browser TODO
	 * 
	 * @return
	 * @throws Exception
	 * 
	 * @pre steps != null
	 * @pre !steps.isEmpty()
	 */
	private void performJourneyStepsInBrowser(List<Step> steps, Browser browser) throws Exception  {
		assert steps != null;
		assert !steps.isEmpty();
				
		PageState initial_page = steps.get(0).getStartPage();
		String sanitized_url = BrowserUtils.sanitizeUrl(initial_page.getUrl(), initial_page.isSecure());

		browser.navigateTo(sanitized_url);
		//execute all steps sequentially in the journey
		executeAllStepsInJourney(steps, browser);
	}

	/**
	 * Executes all {@link Step steps} within a browser
	 * 
	 * @param steps
	 * @param browser
	 */
	private void executeAllStepsInJourney(List<Step> steps, Browser browser) throws Exception{
		ActionFactory action_factory = new ActionFactory(browser.getDriver());
		for(Step step: steps) {
			if(step instanceof SimpleStep) {
				ElementState element = ((SimpleStep)step).getElementState();
				WebElement web_element = browser.getDriver().findElement(By.xpath(element.getXpath()));
				action_factory.execAction(web_element, "", ((SimpleStep)step).getAction());
			}
			else if(step instanceof LoginStep) {
				LoginStep login_step = (LoginStep)step;
				WebElement username_element = browser.getDriver().findElement(By.xpath(login_step.getUsernameElement().getXpath()));
				action_factory.execAction(username_element, login_step.getTestUser().getUsername(), Action.SEND_KEYS);
				
				WebElement password_element = browser.getDriver().findElement(By.xpath(login_step.getPasswordElement().getXpath()));
				action_factory.execAction(password_element, login_step.getTestUser().getPassword(), Action.SEND_KEYS);

				WebElement submit_element = browser.getDriver().findElement(By.xpath(login_step.getSubmitElement().getXpath()));
				action_factory.execAction(submit_element, "", Action.CLICK);
			}
			browser.waitForPageToLoad();
			//TimingUtils.pauseThread(2000L);
		}
	}
	
	private boolean existsInJourney(List<Step> steps, Step step) {
		for(Step journey_step : steps) {
			if(journey_step.getKey().contentEquals(step.getKey())) {
				return true;
			}
		}
		return false;
	}
}
// [END run_pubsub_handler]
// [END cloudrun_pubsub_handler]
// [END run_pubsub_handler]
// [END cloudrun_pubsub_handler]