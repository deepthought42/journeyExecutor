package com.looksee.journeyExecutor;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

import org.openqa.selenium.JavascriptException;
import org.openqa.selenium.NoSuchSessionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.looksee.journeyExecutor.gcp.PubSubDiscardedJourneyPublisherImpl;
import com.looksee.journeyExecutor.gcp.PubSubJourneyVerifiedPublisherImpl;
import com.looksee.journeyExecutor.gcp.PubSubPageBuiltPublisherImpl;
import com.looksee.journeyExecutor.mapper.Body;
import com.looksee.journeyExecutor.models.Browser;
import com.looksee.journeyExecutor.models.ElementState;
import com.looksee.journeyExecutor.models.PageState;
import com.looksee.journeyExecutor.models.enums.BrowserEnvironment;
import com.looksee.journeyExecutor.models.enums.BrowserType;
import com.looksee.journeyExecutor.models.enums.JourneyStatus;
import com.looksee.journeyExecutor.models.enums.PathStatus;
import com.looksee.journeyExecutor.models.journeys.Journey;
import com.looksee.journeyExecutor.models.journeys.LandingStep;
import com.looksee.journeyExecutor.models.journeys.Step;
import com.looksee.journeyExecutor.models.message.DiscardedJourneyMessage;
import com.looksee.journeyExecutor.models.message.JourneyCandidateMessage;
import com.looksee.journeyExecutor.models.message.PageBuiltMessage;
import com.looksee.journeyExecutor.models.message.VerifiedJourneyMessage;
import com.looksee.journeyExecutor.services.AuditRecordService;
import com.looksee.journeyExecutor.services.BrowserService;
import com.looksee.journeyExecutor.services.JourneyService;
import com.looksee.journeyExecutor.services.PageStateService;
import com.looksee.journeyExecutor.services.StepService;
import com.looksee.journeyExecutor.services.StepExecutor;
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
	private PageStateService page_state_service;
	
	@Autowired
	private StepService step_service;
	
	@Autowired
	private JourneyService journey_service;
	
	@Autowired
	private PubSubJourneyVerifiedPublisherImpl journey_verified_topic;
	
	@Autowired
	private PubSubDiscardedJourneyPublisherImpl discarded_journey_topic;
	
	@Autowired
	private AuditRecordService audit_record_service;
	
	@Autowired
	private StepExecutor step_executor;
	
	@Autowired
	private PubSubPageBuiltPublisherImpl page_built_topic;
	
	@RequestMapping(value = "/", method = RequestMethod.POST)
	public ResponseEntity<String> receiveMessage(@RequestBody Body body) 
			throws Exception 
	{	
		Body.Message message = body.getMessage();
		String data = message.getData();
	    String target = !data.isEmpty() ? new String(Base64.getDecoder().decode(data)) : "";
        log.warn("verify journey msg received = "+target);

	    ObjectMapper input_mapper = new ObjectMapper();
	    JourneyCandidateMessage journey_msg = input_mapper.readValue(target, JourneyCandidateMessage.class);
	    
	    JsonMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();

		Journey journey = journey_msg.getJourney();
		List<Step> steps = new ArrayList<>(journey.getSteps());
		long domain_audit_id = journey_msg.getDomainAuditRecordId();
		//if journey with same candidate key exists that also has a status of VERIFIED or DISCARDED then don't iterate
		Journey journey_record = journey_service.findByCandidateKey(journey_msg.getMapId(), 
																	journey_msg.getJourney().getCandidateKey());
		
		if(journey_record != null && !JourneyStatus.CANDIDATE.equals(journey_record.getStatus())) {
			return new ResponseEntity<String>("Journey has already been expanded", HttpStatus.OK);
		}
			
		PageState final_page = null;
		Browser browser = null;
		try {
			browser = browser_service.getConnection(BrowserType.CHROME, BrowserEnvironment.DISCOVERY);
			performJourneyStepsInBrowser(steps, browser);
			
			//if current url is different than second to last page then try to lookup page in database before building page
			String current_url = BrowserUtils.getPageUrl(
									BrowserUtils.sanitizeUserUrl(
											browser.getDriver().getCurrentUrl()));
			//log.warn("looking up url = "+current_url);
			//final_page = page_state_service.findByDomainAudit(journey_msg.getDomainAuditRecordId(), current_url);
			//log.warn("final page found = "+final_page);
			
			log.warn("building page");
			final_page = buildPage(browser);
			log.warn("saving page");
			
			//check if page state with key already exists for domain audit
			PageState page_record = audit_record_service.findPageWithKey(domain_audit_id, final_page.getKey());
			
			if(page_record == null) {
				final_page = page_state_service.save(final_page);
			}
			else { 
				final_page = page_record;
			}
			
			log.warn("loading element states for page = "+final_page.getId());
			List<ElementState> elements = page_state_service.getElementStates(final_page.getId());
			final_page.setElements(elements);					
			log.warn("total elements for final page = "+final_page.getElements().size());
/*
		}
			else {
				long element_count = page_state_service.getElementStateCount(final_page.getId());
				log.warn("total elements found = "+element_count + "   for page state = "+final_page.getId());
				if(element_count == 0) {
					log.warn("NO ELEMENTS WERE FOUND!!!   SAVING ELEMENTS NOW....");
					List<String> xpaths = browser_service.extractAllUniqueElementXpaths(final_page.getSrc());
					log.warn("XPATH extracted = "+xpaths.size());
					List<ElementState> element_states = browser_service.buildPageElementsWithoutNavigation( final_page, 
																											xpaths,
																											final_page.getFullPageHeight(),
																											browser);
					
					element_states = ElementStateUtils.enrichBackgroundColor(element_states).collect(Collectors.toList());
					log.warn("total elements built = "+final_page.getElements().size());
					List<ElementState> elements = element_state_service.saveAll(element_states);
					final_page.setElements(elements);
					
					List<Long> element_ids = elements.parallelStream().map(e -> e.getId()).collect(Collectors.toList());
					page_state_service.addAllElements(final_page.getId(), element_ids);
					//final_page = page_state_service.save(final_page);
					log.warn("final page element count after save = "+final_page.getElements().size());
				}
				else {
					log.warn("ELEMENTS ALREADY EXIST FOR PAGE = "+final_page.getUrl());
					List<ElementState> elements = page_state_service.getElementStates(final_page.getId());
					final_page.setElements(elements);
				}
			}
			*/
		}
		catch(JavascriptException e) {
			log.warn("Javascript Exception for steps = " + steps);
			//e.printStackTrace();
			return new ResponseEntity<String>("Error occured while validating journey with id = "+journey.getId(), HttpStatus.INTERNAL_SERVER_ERROR);
		}
		catch(NoSuchSessionException e) {
			log.warn("Failed to acquire browser session = "+e.getLocalizedMessage());
			//e.printStackTrace();
			return new ResponseEntity<String>("Failed to acquire browser connection", HttpStatus.INTERNAL_SERVER_ERROR);
		}
		catch(Exception e) {
			log.warn("Exception occurred! Returning FAILURE;  message = "+e.getMessage());
			//e.printStackTrace();
			return new ResponseEntity<String>("Error occured while validating journey with id = "+journey.getId(), HttpStatus.INTERNAL_SERVER_ERROR);
		}
		finally {			
			if(browser != null) {
				browser.close();
			}
		}
		
		//STEP AND JOURNEY SETUP	
		Step final_step = steps.get(steps.size()-1);
		
		if(final_step.getId() == null) {
			log.warn("Final step start page has id = "+final_step.getStartPage().getId());
			PageState start_page = final_step.getStartPage();
			start_page.setElements(page_state_service.getElementStates(start_page.getId()));
			
			final_step.setStartPage(final_page);
			final_step.setEndPage(final_page);
			final_step.setKey(final_step.generateKey());			
			
			log.warn("final step " + final_step.getId());
			log.warn("final page = " + final_page.getId());
			log.warn("saving final step");
			Step final_step_record = step_service.save(final_step);
			final_step.setId(final_step_record.getId());
			steps.set(steps.size()-1, final_step);
			journey_service.addStep(domain_audit_id, final_step_record.getId());

		}
		else {
			log.warn("adding final page to final step and updating key");
			step_service.addEndPage(final_step.getId(), final_page.getId());
			step_service.updateKey(final_step.getId(), final_step.getKey());
		}
		
		journey.setSteps(steps);
		journey.setKey(journey.generateKey());
		JourneyStatus status = getVerifiedOrDiscarded(journey);
		log.warn("journey "+journey.getId()+"   status =  "+status);
		journey = journey_service.updateFields(journey.getId(), 
											   status, 
											   journey.getKey());
		
		//journey_service.save(journey);								   
	    journey.setSteps(steps);
		
		//update journey with latest journey details
		if(existsInJourney(steps.subList(0,  steps.size()-1), final_step)) {
			log.warn("step already exists in journey :: "+final_step);
			return new ResponseEntity<String>("Step already exists in Journey", HttpStatus.OK);
		}
		
		if(JourneyStatus.DISCARDED.equals(journey.getStatus())) {
			DiscardedJourneyMessage journey_message = new DiscardedJourneyMessage(	journey, 
																					BrowserType.CHROME, 
																					journey_msg.getDomainId(),
																					journey_msg.getAccountId(), 
																					journey_msg.getDomainAuditRecordId());

			String discarded_journey_json = mapper.writeValueAsString(journey_message);
			discarded_journey_topic.publish(discarded_journey_json);
		}
		else if(!JourneyUtils.hasLoginStep(steps)
					&& !final_step.getStartPage().getUrl().equals(final_step.getEndPage().getUrl())) 
		{
			//if page state isn't associated with domain audit then send pageBuilt message
			log.warn("Looking up page state with id = "+final_page.getId() + ";  Audit record id = "+journey_msg.getDomainAuditRecordId());

			log.warn("page state record is null");
			PageBuiltMessage page_built_msg = new PageBuiltMessage(journey_msg.getAccountId(),
																	journey_msg.getDomainAuditRecordId(),
																	journey_msg.getDomainId(),
																	final_page.getId());
			
			String page_built_str = mapper.writeValueAsString(page_built_msg);
			log.warn("sending page built message ...");
			page_built_topic.publish(page_built_str);
			
			//create landing step and make it the first record in a new list of steps
			LandingStep landing_step = new LandingStep(final_step.getEndPage());
			steps = new ArrayList<>();
			steps.add(landing_step);
			
			Journey new_journey = new Journey(steps, JourneyStatus.VERIFIED);
			new_journey = journey_service.save(new_journey);
			
			//send candidate message with new landing step journey
			VerifiedJourneyMessage journey_message = new VerifiedJourneyMessage(new_journey,
																				PathStatus.EXAMINED, 
																				BrowserType.CHROME, 
																				journey_msg.getDomainId(),
																				journey_msg.getAccountId(), 
																				journey_msg.getDomainAuditRecordId());
			
			String journey_json = mapper.writeValueAsString(journey_message);
		    journey_verified_topic.publish(journey_json);
		}
		else {
			VerifiedJourneyMessage journey_message = new VerifiedJourneyMessage(journey,
																	PathStatus.EXAMINED, 
																	BrowserType.CHROME, 
																	journey_msg.getDomainId(),
																	journey_msg.getAccountId(), 
																	journey_msg.getDomainAuditRecordId());
			
			String journey_json = mapper.writeValueAsString(journey_message);
			journey_verified_topic.publish(journey_json);
			
		}
		
		return new ResponseEntity<String>("Successfully expanded journey", HttpStatus.OK);
		
	}

	/**
	 * Evaluates {@link Journey} and returns Verified or discarded status. A discarded status is returned if the 
	 * journey has more than 1 step, it doesn't end in a {@link LandingStep} and the start and end {@link PageState}
	 * do not match for the last step
	 * 
	 * @param journey
	 * @return
	 */
	private JourneyStatus getVerifiedOrDiscarded(Journey journey) {
		//get last step
		Step last_step = journey.getSteps().get(journey.getSteps().size()-1);
		
		PageState second_to_last_page = PathUtils.getSecondToLastPageState(journey.getSteps());
		PageState final_page = PathUtils.getLastPageState(journey.getSteps());
		if(((journey.getSteps().size() > 1 && !(last_step instanceof LandingStep)) 
				&& final_page.equals(second_to_last_page))) 
		{
			return JourneyStatus.DISCARDED;
		}
		else {
			return JourneyStatus.VERIFIED;
		}
	}

	/**
	 * Constructs a {@link PageState page} including all {@link ElementState elements} on the page as a {@linkplain List}
	 * @param browser
	 * 
	 * @return
	 * @throws Exception
	 * 
	 * @pre browser != null
	 */
	private PageState buildPage(Browser browser) throws Exception {
		assert browser != null;
		
		PageState page_state = browser_service.performBuildPageProcess(browser);
		log.warn("Extracting XPATHS");
		List<String> xpaths = browser_service.extractAllUniqueElementXpaths(page_state.getSrc());
		log.warn("Building page elements");
		List<ElementState> element_states = browser_service.buildPageElementsWithoutNavigation( page_state, 
																								xpaths,
																								page_state.getFullPageHeight(),
																								browser);

		log.warn("enriching background colors");
		element_states = ElementStateUtils.enrichBackgroundColor(element_states).collect(Collectors.toList());
		page_state.setElements(element_states);

		return page_state;

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
		
		//execute all steps sequentially in the journey
		for(Step step: steps) {
			step_executor.execute(browser, step);
			browser.waitForPageToLoad();
		}
	}

	/**
	 * Checks if step is already present within the {@link Journey journey}
	 * 
	 * @param steps
	 * @param step
	 * 
	 * @return
	 */
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
			//execute step
			step_executor.execute(browser, step);
		}
	}	
}
// [END run_pubsub_handler]
// [END cloudrun_pubsub_handler]
// [END run_pubsub_handler]
// [END cloudrun_pubsub_handler]