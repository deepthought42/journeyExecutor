package com.looksee.journeyExecutor;

import java.net.MalformedURLException;
import java.net.URL;
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
import com.looksee.journeyExecutor.models.Domain;
import com.looksee.journeyExecutor.models.ElementState;
import com.looksee.journeyExecutor.models.PageState;
import com.looksee.journeyExecutor.models.enums.BrowserEnvironment;
import com.looksee.journeyExecutor.models.enums.BrowserType;
import com.looksee.journeyExecutor.models.enums.JourneyStatus;
import com.looksee.journeyExecutor.models.enums.PathStatus;
import com.looksee.journeyExecutor.models.journeys.DomainMap;
import com.looksee.journeyExecutor.models.journeys.Journey;
import com.looksee.journeyExecutor.models.journeys.LandingStep;
import com.looksee.journeyExecutor.models.journeys.SimpleStep;
import com.looksee.journeyExecutor.models.journeys.Step;
import com.looksee.journeyExecutor.models.message.DiscardedJourneyMessage;
import com.looksee.journeyExecutor.models.message.JourneyCandidateMessage;
import com.looksee.journeyExecutor.models.message.PageBuiltMessage;
import com.looksee.journeyExecutor.models.message.VerifiedJourneyMessage;
import com.looksee.journeyExecutor.services.AuditRecordService;
import com.looksee.journeyExecutor.services.BrowserService;
import com.looksee.journeyExecutor.services.DomainMapService;
import com.looksee.journeyExecutor.services.DomainService;
import com.looksee.journeyExecutor.services.ElementStateService;
import com.looksee.journeyExecutor.services.JourneyService;
import com.looksee.journeyExecutor.services.PageStateService;
import com.looksee.journeyExecutor.services.StepService;
import com.looksee.journeyExecutor.services.StepExecutor;
import com.looksee.utils.BrowserUtils;
import com.looksee.utils.ElementStateUtils;
import com.looksee.utils.JourneyUtils;
import com.looksee.utils.PathUtils;
import com.looksee.utils.TimingUtils;


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
	private DomainService domain_service;
	
	@Autowired
	private DomainMapService domain_map_service;
	
	@Autowired
	private ElementStateService element_state_service;
	
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
        log.debug("verify journey msg received = "+target);

	    ObjectMapper input_mapper = new ObjectMapper();
	    JourneyCandidateMessage journey_msg = input_mapper.readValue(target, JourneyCandidateMessage.class);
	    
	    JsonMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();

		Journey journey = journey_msg.getJourney();
		
	    //CHECK IF JOURNEY WITH CANDIDATE KEY HAS ALREADY BEEN EVALUATED
	    if(JourneyStatus.DISCARDED.equals(journey.getStatus()) || JourneyStatus.VERIFIED.equals(journey.getStatus())) {
	    	log.warn("Journey has already been verified or discarded with status = "+journey.getStatus());
	    	return new ResponseEntity<String>("Successfully generated journey expansions", HttpStatus.OK);
	    }
		
		List<Step> steps = new ArrayList<>(journey.getSteps());
		long domain_audit_id = journey_msg.getDomainAuditRecordId();
		//if journey with same candidate key exists that also has a status of VERIFIED or DISCARDED then don't iterate
		Journey journey_record = journey_service.findByCandidateKey(journey_msg.getMapId(), 
																	journey_msg.getJourney().getCandidateKey());
		
		if(journey_record != null && !JourneyStatus.CANDIDATE.equals(journey_record.getStatus())) {
			log.warn("Not a CANDIDATE journey. Returning");
			return new ResponseEntity<String>("Journey has already been expanded", HttpStatus.OK);
		}
			
		PageState final_page = null;
		Browser browser = null;
		try {
			Domain domain = domain_service.findById(journey_msg.getDomainId()).get();
			URL domain_url = new URL(BrowserUtils.sanitizeUserUrl(domain.getUrl()));
			browser = browser_service.getConnection(BrowserType.CHROME, BrowserEnvironment.DISCOVERY);
			
			performJourneyStepsInBrowser(steps, browser);
			
			String sanitized_url = BrowserUtils.sanitizeUserUrl(browser.getDriver().getCurrentUrl());
			String current_url = BrowserUtils.getPageUrl(sanitized_url);
			//if current url is external URL then create ExternalPageState
			log.warn("current url = "+current_url);
			if(BrowserUtils.isExternalLink(domain_url.getHost(), new URL(sanitized_url).getHost())) {
				log.warn("EXTERNAL URL detected = "+current_url);
				final_page = new PageState();
				final_page.setUrl(current_url);	
				final_page.setKey(final_page.generateKey());
				final_page.setSrc("");
				final_page.setBrowser(BrowserType.CHROME);
				final_page = page_state_service.save(domain_audit_id, final_page);
			}
			else {
				log.warn("INTERNAL URL detected = "+current_url);
				//if current url is different than second to last page then try to lookup page in database before building page				
				final_page = buildPage(browser, journey_msg.getDomainAuditRecordId());
				
				List<ElementState> elements = page_state_service.getElementStates(final_page.getId());
				final_page.setElements(elements);					
			}
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
		catch(org.openqa.selenium.interactions.MoveTargetOutOfBoundsException e) {
			log.warn("MOVE TO TARGET EXCEPTION FOR ELEMENT = "+e.getMessage());
			//e.printStackTrace();
		}
		catch(Exception e) {
			log.warn("Exception occurred! Returning FAILURE;  message = "+e.getMessage());
			e.printStackTrace();
			return new ResponseEntity<String>("Error occured while validating journey with id = "+journey.getId(), HttpStatus.INTERNAL_SERVER_ERROR);
		}
		finally {			
			if(browser != null) {
				browser.close();
			}
		}
		
		if(final_page == null) {
			log.warn("FINAL PAGE IS NULL! RETURNING ERROR");
			return new ResponseEntity<String>("Error occured building page while validating journey with id = "+journey.getId(), HttpStatus.INTERNAL_SERVER_ERROR);
		}
		
		//STEP AND JOURNEY SETUP	
		Step final_step = steps.get(steps.size()-1);
		final_step.setEndPage(final_page);

		if(final_step.getId() == null) {
			log.warn("Final step start page has id = "+final_step.getStartPage().getId());
			//PageState start_page = final_step.getStartPage();
			
			//final_step.setStartPage(start_page);
			final_step.setKey(final_step.generateKey());			
			Step last_step = new SimpleStep(((SimpleStep)final_step).getAction(), "");
			
			last_step.setKey(final_step.getKey());
			last_step = step_service.save(last_step);
			step_service.addEndPage(last_step.getId(), final_page.getId());
			step_service.setStartPage(last_step.getId(),  final_step.getStartPage().getId());
			step_service.setElementState(last_step.getId(), ((SimpleStep)final_step).getElementState().getId());
			final_step.setId(last_step.getId());
				
			steps.set(steps.size()-1, final_step);
		}
		else {
			step_service.addEndPage(final_step.getId(), final_page.getId());
			step_service.updateKey(final_step.getId(), final_step.getKey());
		}
		
		
		//UPDATE JOURNEY
		journey.setSteps(steps);
		journey.setKey(journey.generateKey());
		JourneyStatus status = getVerifiedOrDiscarded(journey);
		
		journey = journey_service.updateFields(journey.getId(), 
											   status, 
											   journey.getKey(),
											   journey.getOrderedIds());
		journey.setSteps(steps);
		//Save all steps to be attached to journey record
		for(Step step: steps) {
			journey_service.addStep(journey.getId(), step.getId());
		}
		
		//add Journey to domain map
		DomainMap domain_map = domain_map_service.findByDomainAuditId(domain_audit_id);
		domain_map_service.addJourneyToDomainMap(domain_map.getId(), domain_audit_id);
		
		
		if(JourneyStatus.DISCARDED.equals(journey.getStatus()) || existsInJourney(steps.subList(0,  steps.size()-1), final_step)) {
			log.warn("DISCARDED Journey! "+journey.getId());
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
			PageBuiltMessage page_built_msg = new PageBuiltMessage(journey_msg.getAccountId(),
																	journey_msg.getDomainAuditRecordId(),
																	journey_msg.getDomainId(),
																	final_page.getId());
			
			String page_built_str = mapper.writeValueAsString(page_built_msg);
			log.warn("SENDING page built message ...");
			page_built_topic.publish(page_built_str);
			
			//create landing step and make it the first record in a new list of steps
			Step landing_step = new LandingStep(final_page);
			Step temp_step = new LandingStep();
			temp_step.setKey(landing_step.getKey());
			landing_step = step_service.save(temp_step);
			step_service.setStartPage(landing_step.getId(), final_page.getId());
			landing_step.setStartPage(final_page);

			steps = new ArrayList<>();
			steps.add(landing_step);
			
			//CREATE JOURNEY
			Journey new_journey = new Journey();
			List<Long> ordered_ids = steps.stream()
					  .map(step -> step.getId())
					  .collect(Collectors.toList());
			new_journey.setStatus(JourneyStatus.VERIFIED);
			new_journey.setOrderedIds(ordered_ids);
			new_journey.setCandidateKey(new_journey.generateCandidateKey());
			new_journey.setKey(new_journey.generateKey());
			new_journey = journey_service.save(new_journey);
			
			for(Step step: steps) {
				journey_service.addStep(new_journey.getId(), step.getId());
			}
			new_journey.setSteps(steps);
			
			//TODO: Determine if this biz logic is correct. Should data be expanded and validated?
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
	 * @throws MalformedURLException 
	 */
	private JourneyStatus getVerifiedOrDiscarded(Journey journey) throws MalformedURLException {
		//get last step
		Step last_step = journey.getSteps().get(journey.getSteps().size()-1);
		
		PageState second_to_last_page = PathUtils.getSecondToLastPageState(journey.getSteps());
		PageState final_page = PathUtils.getLastPageState(journey.getSteps());
		
		if((journey.getSteps().size() > 1 && !(last_step instanceof LandingStep)) 
				&& (final_page.equals(second_to_last_page)
						|| BrowserUtils.isExternalLink(new URL(BrowserUtils.sanitizeUrl(second_to_last_page.getUrlAfterLoading(), false)).getHost(), final_page.getUrl()))) 
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
	 * @param domain_audit_id TODO
	 * 
	 * @return
	 * @throws Exception
	 * 
	 * @pre browser != null
	 */
	private PageState buildPage(Browser browser, long domain_audit_id) throws Exception {
		assert browser != null;
		
		PageState page_state = browser_service.performBuildPageProcess(browser);
		PageState page_state_record = audit_record_service.findPageWithKey(domain_audit_id, page_state.getKey());

		if(page_state_record == null ) 
		{				
			log.warn("Extracting element states..."+page_state.getUrl()+";   key = "+page_state.getKey());
			List<String> xpaths = browser_service.extractAllUniqueElementXpaths(page_state.getSrc());
			List<ElementState> element_states = browser_service.getDomElementStates(page_state, 
																					xpaths,
																					browser);
			
			String host = (new URL(browser.getDriver().getCurrentUrl())).getHost();
			element_states = browser_service.enrichElementStates(element_states, page_state, browser, host);
			element_states = browser_service.enrichImageElements(element_states, page_state, browser, host);
			element_states = ElementStateUtils.enrichBackgroundColor(element_states);
			
			page_state.setElements(element_states);
			page_state = page_state_service.save(domain_audit_id, page_state);
			audit_record_service.addPageToAuditRecord(domain_audit_id, page_state.getId());
		}
		else {
			log.warn("page state already exists for domain audit!!!!!! - "+page_state.getUrl());
			page_state = page_state_record;
		}
		
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
}
// [END run_pubsub_handler]
// [END cloudrun_pubsub_handler]
// [END run_pubsub_handler]
// [END cloudrun_pubsub_handler]