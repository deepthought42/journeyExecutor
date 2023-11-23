package com.looksee.journeyExecutor;

import java.awt.image.BufferedImage;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

import org.openqa.selenium.JavascriptException;
import org.openqa.selenium.NoSuchElementException;
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
import com.looksee.journeyExecutor.models.journeys.DomainMap;
import com.looksee.journeyExecutor.models.journeys.Journey;
import com.looksee.journeyExecutor.models.journeys.LandingStep;
import com.looksee.journeyExecutor.models.journeys.Step;
import com.looksee.journeyExecutor.models.message.DomainPageBuiltMessage;
import com.looksee.journeyExecutor.models.message.JourneyCandidateMessage;
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
	private ElementStateService element_state_service;
	
	@Autowired
	private StepService step_service;
	
	@Autowired
	private JourneyService journey_service;
	
	@Autowired
	private DomainMapService domain_map_service;
	
	@Autowired
	private DomainService domain_service;
	
	@Autowired
	private PubSubJourneyVerifiedPublisherImpl journey_verified_topic;
	
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
	    if(!JourneyStatus.CANDIDATE.equals(journey.getStatus())) {
	    	log.warn("Journey has already been verified or discarded, or is being evaluated with status = "+journey.getStatus());
	    	return new ResponseEntity<String>("Successfully generated journey expansions", HttpStatus.OK);
	    }
	    
	    //update journey status to REVIEWING
	    journey_service.updateStatus(journey.getId(), JourneyStatus.REVIEWING);
		
		List<Step> steps = new ArrayList<>(journey.getSteps());
		long domain_audit_id = journey_msg.getDomainAuditRecordId();
		//if journey with same candidate key exists that also has a status of VERIFIED or DISCARDED then don't iterate
		PageState final_page = null;
		Browser browser = null;
		Domain domain = domain_service.findByAuditRecord(journey_msg.getDomainAuditRecordId());
		String current_url = "";
		
		try {
			URL domain_url = new URL(BrowserUtils.sanitizeUserUrl(domain.getUrl()));
			browser = browser_service.getConnection(BrowserType.CHROME, BrowserEnvironment.DISCOVERY);
			
			performJourneyStepsInBrowser(steps, browser);
			TimingUtils.pauseThread(5000L);
			
			String sanitized_url = BrowserUtils.sanitizeUserUrl(browser.getDriver().getCurrentUrl());
			current_url = BrowserUtils.getPageUrl(sanitized_url);
			
			//if current url is external URL then create ExternalPageState			
			if(BrowserUtils.isExternalLink(domain_url.getHost(), new URL(sanitized_url).getHost())) {
				final_page = new PageState();
				final_page.setUrl(current_url);
				final_page.setKey(final_page.generateKey());
				final_page.setSrc("");
				final_page.setBrowser(BrowserType.CHROME);
				final_page = page_state_service.save(domain_audit_id, final_page);
				audit_record_service.addPageToAuditRecord(domain_audit_id, final_page.getId());
			}
			else {
				//if current url is different than second to last page then try to lookup page in database before building page				
				final_page = buildPageAndAddToDomainAudit(browser, journey_msg.getDomainAuditRecordId());			
			}
		}
		catch(JavascriptException e) {
			log.warn("Javascript Exception for steps = " + steps + "; journey = "+journey.getId() + "  with status = "+journey.getStatus());
		    journey_service.updateStatus(journey.getId(), JourneyStatus.CANDIDATE);
			return new ResponseEntity<String>("Error occured while validating journey with id = "+journey.getId(), HttpStatus.INTERNAL_SERVER_ERROR);
		}
		catch(NoSuchSessionException e) {
			log.warn("Failed to acquire browser session; journey = "+journey.getId() + "  with status = "+journey.getStatus() + "    --> "+e.getLocalizedMessage());
		    journey_service.updateStatus(journey.getId(), JourneyStatus.CANDIDATE);
			return new ResponseEntity<String>("Failed to acquire browser connection", HttpStatus.INTERNAL_SERVER_ERROR);
		}
		catch(NoSuchElementException e) {
			log.warn("Failed to acquire browser element for journey = "+journey.getId()+ " with status = "+journey.getStatus()+" ;  on page = " + current_url +  ";     " +e.getLocalizedMessage());
			//e.printStackTrace();
		    journey_service.updateStatus(journey.getId(), JourneyStatus.CANDIDATE);
			return new ResponseEntity<String>("Failed to acquire browser connection", HttpStatus.INTERNAL_SERVER_ERROR);
		}
		catch(org.openqa.selenium.interactions.MoveTargetOutOfBoundsException e) {
			log.warn("MOVE TO TARGET EXCEPTION FOR ELEMENT;    journey = "+journey.getId() + "  with status = "+journey.getStatus() + "  --> "+e.getMessage());
		    journey_service.updateStatus(journey.getId(), JourneyStatus.CANDIDATE);
			return new ResponseEntity<String>("MoveToTarget Exception occured while validating journey with id = "+journey.getId()+". Returning ERROR in hopes it works out in another journey", HttpStatus.INTERNAL_SERVER_ERROR);
		}
		catch(Exception e) {
			log.warn("Exception occurred! Returning FAILURE;   journey = "+journey.getId() + "  with status = "+journey.getStatus()+" message = "+e.getMessage());
			//e.printStackTrace();
		    journey_service.updateStatus(journey.getId(), JourneyStatus.CANDIDATE);
			return new ResponseEntity<String>("Error occured while validating journey with id = "+journey.getId(), HttpStatus.INTERNAL_SERVER_ERROR);
		}
		finally {			
			if(browser != null) {
				browser.close();
			}
		}
		
		if(final_page == null) {
			log.warn("FINAL PAGE IS NULL! RETURNING ERROR;  journey = "+journey.getId() + "  with status = "+journey.getStatus());
		    journey_service.updateStatus(journey.getId(), JourneyStatus.CANDIDATE);
			return new ResponseEntity<String>("Error occured building page while validating journey with id = "+journey.getId(), HttpStatus.INTERNAL_SERVER_ERROR);
		}
		
		//STEP AND JOURNEY SETUP	
		Step final_step = steps.get(steps.size()-1);
		final_step.setEndPage(final_page);

		if(final_step.getId() == null) {
			final_step.setKey(final_step.generateKey());			
			Step result_record = step_service.save(final_step);
			final_step.setId(result_record.getId());
			steps.set(steps.size()-1, final_step);
		}
		else {
			//step_service.updateStatus(final_step.getId(), JourneyStatus.VERIFIED);
			step_service.addEndPage(final_step.getId(), final_page.getId());
			step_service.updateKey(final_step.getId(), final_step.getKey());
		}
		
		//UPDATE JOURNEY
		journey.setSteps(steps);
		journey.setKey(journey.generateKey());
		JourneyStatus status = getVerifiedOrDiscarded(journey);
		
		Journey updated_journey = journey_service.updateFields(journey.getId(), 
															   status, 
															   journey.getKey(),
															   journey.getOrderedIds());
		updated_journey.setSteps(steps);
		//Save all steps to be attached to journey record
		for(Step step: steps) {
			journey_service.addStep(updated_journey.getId(), step.getId());
		}
		
		//add Journey to domain map
		DomainMap domain_map = domain_map_service.findByDomainAuditId(domain_audit_id);			
		
		if(JourneyStatus.DISCARDED.equals(updated_journey.getStatus()) || existsInJourney(steps.subList(0,  steps.size()-1), final_step)) {
			
			log.warn("DISCARDED Journey! "+updated_journey.getId() + " with status = "+updated_journey.getStatus());
		    journey_service.updateStatus(updated_journey.getId(), JourneyStatus.DISCARDED);
		    
		    /*
			DiscardedJourneyMessage journey_message = new DiscardedJourneyMessage(	journey, 
																					BrowserType.CHROME, 
																					domain.getId(),
																					journey_msg.getAccountId(), 
																					journey_msg.getDomainAuditRecordId());

			String discarded_journey_json = mapper.writeValueAsString(journey_message);
			discarded_journey_topic.publish(discarded_journey_json);
			*/
			log.warn("Returning success for journey with status = " +updated_journey.getStatus() + ";   journey id ="+updated_journey.getId());
		}
		else if(!JourneyUtils.hasLoginStep(steps)
					&& !final_step.getStartPage().getUrl().equals(final_step.getEndPage().getUrl())) 
		{
			//if page state isn't associated with domain audit then send pageBuilt message
			DomainPageBuiltMessage page_built_msg = new DomainPageBuiltMessage(journey_msg.getAccountId(),
																				journey_msg.getDomainAuditRecordId(),
																				final_page.getId());
			
			String page_built_str = mapper.writeValueAsString(page_built_msg);
			log.warn("SENDING page built message ...");
			page_built_topic.publish(page_built_str);
			
			//create landing step and make it the first record in a new list of steps
			Step landing_step = new LandingStep(final_page, JourneyStatus.VERIFIED);
			landing_step = step_service.save(landing_step);
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
			new_journey = journey_service.save(domain_map.getId(), new_journey);
			
			for(Step step: steps) {
				journey_service.addStep(new_journey.getId(), step.getId());
			}
			new_journey.setSteps(steps);
			
			//send candidate message with new landing step journey
			VerifiedJourneyMessage journey_message = new VerifiedJourneyMessage(new_journey,
																				JourneyStatus.VERIFIED, 
																				BrowserType.CHROME, 
																				journey_msg.getAccountId(), 
																				journey_msg.getDomainAuditRecordId());
			
			String journey_json = mapper.writeValueAsString(journey_message);
		    journey_verified_topic.publish(journey_json);
		    
		    log.warn("Returning success for journey with status = " +new_journey.getStatus() + ";   journey id ="+new_journey.getId());
		}
		else {
		    journey_service.updateStatus(updated_journey.getId(), JourneyStatus.VERIFIED);

			VerifiedJourneyMessage journey_message = new VerifiedJourneyMessage(updated_journey,
																	JourneyStatus.VERIFIED, 
																	BrowserType.CHROME, 
																	journey_msg.getAccountId(), 
																	journey_msg.getDomainAuditRecordId());
			
			String journey_json = mapper.writeValueAsString(journey_message);
			journey_verified_topic.publish(journey_json);
			log.warn("Returning success for journey with status = " +updated_journey.getStatus() + ";   journey id ="+updated_journey.getId());

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
	private PageState buildPageAndAddToDomainAudit(Browser browser, long domain_audit_id) throws Exception {
		assert browser != null;
		
		PageState page_state = browser_service.performBuildPageProcess(browser);
		PageState page_state_record = audit_record_service.findPageWithKey(domain_audit_id, page_state.getKey());

		if(page_state_record == null ) 
		{				
			log.warn("Extracting element states..."+page_state.getUrl()+";   key = "+page_state.getKey());
			List<String> xpaths = browser_service.extractAllUniqueElementXpaths(page_state.getSrc(), null);
			
			BufferedImage full_page_screenshot = ImageIO.read(new URL(page_state.getFullPageScreenshotUrlComposite()));
			List<ElementState> element_states = browser_service.getDomElementStates(page_state, 
																					xpaths,
																					browser,
																					domain_audit_id,
																					full_page_screenshot);
			
			//page_state.setElements(element_states);			
			page_state = page_state_service.save(domain_audit_id, page_state);
			
			for(ElementState element: element_states) {
				if(element.getId() == null) {
					element = element_state_service.save(domain_audit_id, element);
				}
				page_state_service.addElement(page_state.getId(), element.getId());
			}
			long element_count = page_state_service.getElementStateCount(page_state.getId());
			if(element_count != element_states.size()) {
				log.warn("Saved element count does not match!!! pageid = "+page_state.getId()+";    with elements size = "+element_states.size() + ";    element count = "+element_count);
			}
			audit_record_service.addPageToAuditRecord(domain_audit_id, page_state.getId());
		}
		else {
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
		
		String last_url = browser.getDriver().getCurrentUrl();
		
		//execute all steps sequentially in the journey
		for(Step step: steps) {
			step_executor.execute(browser, step);
			
			String current_url = browser.getDriver().getCurrentUrl();
			if(!last_url.equals(current_url)) {
				browser.waitForPageToLoad();
			}
			last_url = current_url;
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