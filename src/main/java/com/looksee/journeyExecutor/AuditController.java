package com.looksee.journeyExecutor;

import java.net.URL;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import org.openqa.grid.common.exception.GridException;
import org.openqa.selenium.WebDriverException;
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
import com.looksee.journeyExecutor.gcp.PubSubDiscardedJourneyPublisherImpl;
import com.looksee.journeyExecutor.gcp.PubSubErrorPublisherImpl;
import com.looksee.journeyExecutor.gcp.PubSubJourneyVerifiedPublisherImpl;
import com.looksee.journeyExecutor.gcp.PubSubPageBuiltPublisherImpl;
import com.looksee.journeyExecutor.mapper.Body;
import com.looksee.journeyExecutor.models.Browser;
import com.looksee.journeyExecutor.models.ElementState;
import com.looksee.journeyExecutor.models.PageState;
import com.looksee.journeyExecutor.models.enums.BrowserEnvironment;
import com.looksee.journeyExecutor.models.enums.BrowserType;
import com.looksee.journeyExecutor.models.enums.PathStatus;
import com.looksee.journeyExecutor.models.journeys.Journey;
import com.looksee.journeyExecutor.models.journeys.DomainMap;
import com.looksee.journeyExecutor.models.journeys.Step;
import com.looksee.journeyExecutor.models.message.DiscardedJourneyMessage;
import com.looksee.journeyExecutor.models.message.JourneyCandidateMessage;
import com.looksee.journeyExecutor.models.message.PageBuiltMessage;
import com.looksee.journeyExecutor.models.message.VerifiedJourneyMessage;
import com.looksee.journeyExecutor.services.AuditRecordService;
import com.looksee.journeyExecutor.services.BrowserService;
import com.looksee.journeyExecutor.services.DomainMapService;
import com.looksee.journeyExecutor.services.ElementStateService;
import com.looksee.journeyExecutor.services.JourneyService;
import com.looksee.journeyExecutor.services.PageStateService;
import com.looksee.journeyExecutor.services.StepService;
import com.looksee.journeyExecutor.services.StepExecutor;
import com.looksee.journeyExecutor.models.enums.AuditCategory;
import com.looksee.journeyExecutor.models.message.AuditError;
import com.looksee.utils.BrowserUtils;
import com.looksee.utils.ElementStateUtils;
import com.looksee.utils.JourneyUtils;
import com.looksee.utils.PathUtils;

import io.github.resilience4j.retry.annotation.Retry;


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
// PubsubController consumes a Pub/Sub message.
@RestController
public class AuditController {
	private static Logger log = LoggerFactory.getLogger(AuditController.class);

	@Autowired
	private BrowserService browser_service;
	
	@Autowired
	private JourneyService journey_service;
	
	@Autowired
	private PageStateService page_state_service;
	
	@Autowired
	private StepService step_service;
	
	@Autowired
	private ElementStateService element_state_service;
	
	@Autowired
	private AuditRecordService audit_record_service;
	
	@Autowired
	private PubSubErrorPublisherImpl error_topic;
	
	@Autowired
	private PubSubJourneyVerifiedPublisherImpl journey_verified_topic;
	
	@Autowired
	private PubSubDiscardedJourneyPublisherImpl discarded_journey_topic;
	
	@Autowired
	private DomainMapService domain_map_service;
	
	@Autowired
	private StepExecutor step_executor;
	
	@Autowired
	private PubSubPageBuiltPublisherImpl page_built_topic;
	
	@RequestMapping(value = "/", method = RequestMethod.POST)
	public ResponseEntity<String> receiveMessage(@RequestBody Body body) 
			throws JsonMappingException, JsonProcessingException, ExecutionException, InterruptedException 
	{	
		Body.Message message = body.getMessage();
		String data = message.getData();
	    String target = !data.isEmpty() ? new String(Base64.getDecoder().decode(data)) : "";
        log.warn("verify journey msg received = "+target);

	    ObjectMapper input_mapper = new ObjectMapper();
	    JourneyCandidateMessage journey_msg = input_mapper.readValue(target, JourneyCandidateMessage.class);
	    
	    JsonMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();

		List<Step> steps = new ArrayList<>(journey_msg.getJourney().getSteps());
		Browser browser = null;

		try {		
			PageState page_state = iterateThroughJourneySteps(steps, 
															  journey_msg.getDomainId(), 
															  journey_msg.getAccountId(), 
															  journey_msg.getDomainAuditRecordId(),
															  browser);
			
			Step final_step = steps.get(steps.size()-1).clone();
			
			final_step.setEndPage(page_state);
			final_step.setKey(final_step.generateKey());
			
			if(existsInJourney(steps, final_step)) {
				log.warn("step already exists in journey :: "+steps+"  =  "+final_step);
				return null;
			}
			
			if(!final_step.getStartPage().equals(page_state)) {
				log.warn("saving final step");
				final_step = step_service.save(final_step);
					
				if(JourneyUtils.hasLoginStep(steps)) {
					log.warn("journey has login step");
					steps.set(steps.size()-1, final_step);
				}
				else {					
					steps = new ArrayList<>();
					
					PageState page_state_record = page_state_service.findByDomainAudit(journey_msg.getDomainAuditRecordId(), page_state.getId());

					// if end page for final step doesn't already exist for domain then create a page load step
					if(page_state_record == null) {
						PageBuiltMessage page_built_msg = new PageBuiltMessage(journey_msg.getAccountId(),
																				journey_msg.getDomainAuditRecordId(),
																				journey_msg.getDomainId(),
																				page_state.getId());
						
						String page_built_str = mapper.writeValueAsString(page_built_msg);
						log.warn("sending page built message ...");
						page_built_topic.publish(page_built_str);
					}
					
					steps.add(final_step);
				}
				
				log.warn("saving journey");
				Journey journey = new Journey(steps);
				Journey journey_record = journey_service.save(journey);
				journey.setId(journey_record.getId());
				
				log.warn("adding journey to domain map");
				DomainMap domain_map = domain_map_service.findByDomainAuditId(journey_msg.getDomainAuditRecordId());
				if(domain_map == null) {
					domain_map = domain_map_service.save(new DomainMap());
					log.warn("adding domain map to audit record = " + journey_msg.getDomainAuditRecordId());
					audit_record_service.addDomainMap(journey_msg.getDomainAuditRecordId(), domain_map.getId());
				}
				domain_map_service.addJourneyToDomainMap(journey.getId(), domain_map.getId());
				
				log.warn("done processing journey = "+journey);
				processIfStepsShouldBeExpanded(journey, 
												journey_msg.getDomainId(), 
												journey_msg.getAccountId(), 
												journey_msg.getDomainAuditRecordId());
				
			}
			
			return new ResponseEntity<String>("Successfully expanded journey", HttpStatus.OK);
		}
		catch(WebDriverException | GridException e) {
			log.warn("Selenium exception occurred = "+e.getMessage());
			//e.printStackTrace();
		}
		catch(Exception e) {
			log.error("Exception occurred during journey execution");
			e.printStackTrace();
			
			double progress = 1.0;
			//TODO send error message to error topic
			AuditError err = new AuditError(journey_msg.getAccountId(), 
											journey_msg.getDomainAuditRecordId(), 
											"Exception occurred during journey execution",
											AuditCategory.ACCESSIBILITY,
											progress,
											journey_msg.getDomainId());
			
			String err_json = mapper.writeValueAsString(err);
			error_topic.publish(err_json);
		}
		finally {
			if(browser != null) {
				browser.close();
			}
		}

		return new ResponseEntity<String>("Error occurred while executing journey", HttpStatus.INTERNAL_SERVER_ERROR);
	}

	/**
	 * Checks if the last step in the list of steps has matching start and end page states
	 * @param domain_id
	 * @param account_id
	 * @param audit_record_id
	 * @param steps
	 * 
	 * @throws JsonProcessingException 
	 * @throws InterruptedException 
	 * @throws ExecutionException 
	 */
	private void processIfStepsShouldBeExpanded(Journey journey, 
												long domain_id, 
												long account_id, 
												long audit_record_id 
			) throws JsonProcessingException, ExecutionException, InterruptedException 
	{
		try {
			PageState second_to_last_page = PathUtils.getSecondToLastPageState(journey.getSteps());
			PageState final_page = PathUtils.getLastPageState(journey.getSteps());
			//is end_page PageState different from second to last PageState
			if(final_page == null || (final_page.equals(second_to_last_page) && journey.getSteps().size() > 1)) {
				log.warn("publishing discarded journey...");
				//tell parent that we processed a journey that is being discarded
				DiscardedJourneyMessage journey_message = new DiscardedJourneyMessage(	journey, 
																						BrowserType.CHROME, 
																						domain_id,
																						account_id, 
																						audit_record_id);
				
			   	JsonMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
				String discarded_journey_json = mapper.writeValueAsString(journey_message);
				log.warn("audit progress update = "+discarded_journey_json);
			    discarded_journey_topic.publish(discarded_journey_json);
			}
			else {
				log.warn("publishing journey verified message...");
				VerifiedJourneyMessage journey_message = new VerifiedJourneyMessage(journey,
																					PathStatus.EXAMINED, 
																					BrowserType.CHROME, 
																					domain_id,
																					account_id, 
																					audit_record_id);
				
				JsonMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
				String journey_json = mapper.writeValueAsString(journey_message);
				log.warn("audit progress update = "+journey_json);
			    journey_verified_topic.publish(journey_json);
			}
		}catch(Exception e) {
			e.printStackTrace();
			DiscardedJourneyMessage journey_message = new DiscardedJourneyMessage(	journey, 
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
			log.warn("performing build page process");
			page_state = browser_service.performBuildPageProcess(browser);
			page_state = page_state_service.save(page_state);
			//audit_record_service.addPageToAuditRecord(audit_record_id, page_state.getId());
		}

		List<String> xpaths = browser_service.extractAllUniqueElementXpaths(page_state.getSrc());
		log.warn("Extracting elements for page state = "+page_state.getKey());
		List<ElementState> element_states = browser_service.buildPageElementsWithoutNavigation( page_state, 
																								xpaths,
																								audit_record_id,
																								page_state.getFullPageHeight(),
																								browser);

		element_states = ElementStateUtils.enrichBackgroundColor(element_states).collect(Collectors.toList());
		page_state.setElements(element_states);
		
		
		List<ElementState> saved_elements = element_state_service.saveAll(element_states);
		List<Long> element_ids = saved_elements.parallelStream()
											   .map(element -> element.getId())
											   .collect(Collectors.toList());

		page_state_service.addAllElements(page_state.getId(), element_ids);
		
		return page_state;
	}
	
	/**
	 * Executes steps in sequence and builds the resulting page state
	 * 
	 * @param steps
	 * @param domain_id
	 * @param account_id
	 * @param audit_record_id
	 * @param browser TODO
	 * @return {@link PageState} or null if final page is an external page
	 * @throws Exception
	 * 
	 * @pre steps != null
	 * @pre !steps.isEmpty()
	 */
	@Retry(name="webdriver")
	private PageState iterateThroughJourneySteps( List<Step> steps, 
												  long domain_id, 
												  long account_id, 
												  long audit_record_id, 
												  Browser browser
	) throws Exception {
		assert steps != null;
		assert !steps.isEmpty();

		log.warn("iterating over steps");
		browser = browser_service.getConnection(BrowserType.CHROME, BrowserEnvironment.DISCOVERY);
		performJourneyStepsInBrowser(steps, browser);

		return buildPage(audit_record_id, browser);
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
				
		/*
		PageState initial_page = steps.get(0).getStartPage();
		String sanitized_url = BrowserUtils.sanitizeUrl(initial_page.getUrl(), initial_page.isSecured());

		browser.navigateTo(sanitized_url);
		*/
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
		for(Step step: steps) {
			step_executor.execute(browser, step);
			browser.waitForPageToLoad();
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
	

	/**
	 * 
	 * @param journey
	 * @param browser
	 */
	private void executeJourney(Journey journey, Browser browser) {
		assert journey != null;
		assert browser != null;
		
		List<Step> ordered_steps = new ArrayList<>();
		//execute journey steps
		for(long step_id : journey.getOrderedIds()) {
			
			for(Step step: journey.getSteps()) {
				if(step.getId() == step_id) {
					ordered_steps.add(step);
					break;
				}
			}
		}

		for(Step step : ordered_steps) {
			
			log.warn("step :: "+step);
			//execute step
			step_executor.execute(browser, step);
		}
	}	
}
// [END run_pubsub_handler]
// [END cloudrun_pubsub_handler]
// [END run_pubsub_handler]
// [END cloudrun_pubsub_handler]