package com.looksee.journeyExecutor;

import java.awt.image.BufferedImage;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
import com.looksee.journeyExecutor.models.message.DiscardedJourneyMessage;
import com.looksee.journeyExecutor.models.message.JourneyCandidateMessage;
import com.looksee.journeyExecutor.models.message.PageBuiltMessage;
import com.looksee.journeyExecutor.models.message.VerifiedJourneyMessage;
import com.looksee.journeyExecutor.services.BrowserService;
import com.looksee.journeyExecutor.services.DomainMapService;
import com.looksee.journeyExecutor.services.DomainService;
import com.looksee.journeyExecutor.services.ElementStateService;
import com.looksee.journeyExecutor.services.JourneyService;
import com.looksee.journeyExecutor.services.PageStateService;
import com.looksee.journeyExecutor.services.StepExecutor;
import com.looksee.journeyExecutor.services.StepService;
import com.looksee.utils.BrowserUtils;
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

	private static Map<Long,Integer> review_map = new HashMap<>();

	@Autowired
	private BrowserService browser_service;
	
	@Autowired
	private ElementStateService element_state_service;

	@Autowired
	private PageStateService page_state_service;
	
	@Autowired
	private StepService step_service;
	
	@Autowired
	private JourneyService journey_service;
	
	@Autowired
	private DomainMapService domain_map_service;
	
	@Autowired
	private DomainService domain_service;
	
	@Autowired
	private PubSubJourneyVerifiedPublisherImpl verified_journey_topic;
	
	@Autowired
	private PubSubDiscardedJourneyPublisherImpl discarded_journey_topic;
	
	@Autowired
	private StepExecutor step_executor;
	
	@Autowired
	private PubSubPageBuiltPublisherImpl page_built_topic;
	
	@RequestMapping(value = "/", method = RequestMethod.POST)
	public ResponseEntity<String> receiveMessage(@RequestBody Body body) 
			throws Exception 
	{
		Browser browser = null;
		Journey journey = null;
		List<Step> steps = new ArrayList<>();
		String current_url = "";
		PageState final_page = null;
		long domain_audit_id = -1;
		JourneyCandidateMessage journey_msg = null;
		JsonMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
		boolean is_external_link = false;
		Domain domain = null;
		
		Body.Message message = body.getMessage();
		String data = message.getData();
		String target = !data.isEmpty() ? new String(Base64.getDecoder().decode(data)) : "";
		
		ObjectMapper input_mapper = new ObjectMapper();
		journey_msg = input_mapper.readValue(target, JourneyCandidateMessage.class);
		journey = journey_msg.getJourney();

		review_map.putIfAbsent(journey.getId(), 0);
		if(review_map.containsKey(journey.getId()) && review_map.get(journey.getId()) >= 4){
			journey_service.updateStatus(journey.getId(), JourneyStatus.ERROR);
			return new ResponseEntity<String>("Test errored too much", HttpStatus.OK);
		}
		review_map.merge(journey.getId(), 1, (a,b) -> a+b);

		try {
			steps = new ArrayList<>(journey.getSteps());
			domain_audit_id = journey_msg.getAuditRecordId();
		
			Optional<Journey> journey_opt = journey_service.findById(journey.getId());

			if(journey_opt.isPresent() && !JourneyStatus.CANDIDATE.equals(journey_opt.get().getStatus())){
				return new ResponseEntity<String>("Journey "+ journey_opt.get().getId()+" does not have CANDIDATE status. It has already been evaluated", HttpStatus.OK);
			}
			else {
				journey = journey_opt.get();
			}

			//update journey status to REVIEWING
			journey_service.updateStatus(journey.getId(), JourneyStatus.REVIEWING);
			
			//if journey with same candidate key exists that also has a status of VERIFIED or DISCARDED then don't iterate
			domain = domain_service.findByAuditRecord(journey_msg.getAuditRecordId());
		
			URL domain_url = new URL(BrowserUtils.sanitizeUserUrl(domain.getUrl()));
			browser = browser_service.getConnection(BrowserType.CHROME, BrowserEnvironment.DISCOVERY);
			
			String browser_url = performJourneyStepsInBrowser(steps, browser);
			TimingUtils.pauseThread(3000L);
			String sanitized_url = BrowserUtils.sanitizeUserUrl(browser_url);
			current_url = BrowserUtils.getPageUrl(sanitized_url);
			is_external_link = BrowserUtils.isExternalLink(domain_url.getHost(), new URL(sanitized_url).getHost());
			//if current url is external URL then create ExternalPageState
			if(is_external_link) {
				final_page = new PageState();
				final_page.setUrl(current_url);
				final_page.setSrc("External Links are not mapped");
				final_page.setBrowser(BrowserType.CHROME);
				final_page.setElementExtractionComplete(true);
				final_page.setAuditRecordId(journey_msg.getAuditRecordId());
				final_page.setKey(final_page.generateKey());
				final_page = page_state_service.save( journey_msg.getMapId(), final_page);
			}
			else {
				//if current url is different than second to last page then try to lookup page in database before building page
				final_page = buildPage(browser, journey_msg.getMapId(), journey_msg.getAuditRecordId(), browser_url);
				log.warn("created page "+final_page.getUrl() + " with key =   "+final_page.getKey());
			}
		}
		catch(JavascriptException e) {
			log.warn("Javascript Exception for steps = " + steps + "; journey = "+journey.getId() + "  with status = "+journey.getStatus());
		    journey_service.updateStatus(journey.getId(), JourneyStatus.CANDIDATE);
			return new ResponseEntity<String>("Error occured while validating journey with id = "+journey.getId(), HttpStatus.INTERNAL_SERVER_ERROR);
		}
		catch(NoSuchSessionException e) {
			log.warn("Failed to acquire browser session; journey = "+journey.getId() + "  with status = "+journey.getStatus() + "    --> "+e.getLocalizedMessage().substring(0,100));
		    journey_service.updateStatus(journey.getId(), JourneyStatus.CANDIDATE);
			return new ResponseEntity<String>("Failed to acquire browser connection", HttpStatus.INTERNAL_SERVER_ERROR);
		}
		catch(NoSuchElementException e) {
			log.warn("Failed to acquire browser element for journey = "+journey.getId()+ " with status = "+journey.getStatus()+" ;  on page = " + current_url +  ";     " +e.getLocalizedMessage().substring(0,100));
		    journey_service.updateStatus(journey.getId(), JourneyStatus.CANDIDATE);
			return new ResponseEntity<String>("Element not found", HttpStatus.INTERNAL_SERVER_ERROR);
		}
		catch(org.openqa.selenium.interactions.MoveTargetOutOfBoundsException e) {
			log.debug("MOVE TO TARGET EXCEPTION FOR ELEMENT;    journey = "+journey.getId() + "  with status = "+journey.getStatus() + "  --> "+e.getMessage().substring(0,100));
		    journey_service.updateStatus(journey.getId(), JourneyStatus.CANDIDATE);
			return new ResponseEntity<String>("MoveToTarget Exception occured while validating journey with id = "+journey.getId()+". Returning ERROR in hopes it works out in another journey", HttpStatus.INTERNAL_SERVER_ERROR);
		}
		catch(MalformedURLException e){
			log.warn("MalformedUrlException = "+current_url);
			journey_service.updateStatus(journey.getId(), JourneyStatus.DISCARDED);
			return new ResponseEntity<String>(current_url + " is malformed", HttpStatus.OK);
		}
		catch(NullPointerException e){
			log.warn("NullPointerException occurred");
			e.printStackTrace();
			return new ResponseEntity<String>(current_url + " experience Null Pointer Exception", HttpStatus.INTERNAL_SERVER_ERROR);
		}
		catch(Exception e) {
			log.warn("Exception occurred! Returning FAILURE;   message = "+e.getMessage());
			//e.printStackTrace();
			journey_service.updateStatus(journey.getId(), JourneyStatus.CANDIDATE);
			return new ResponseEntity<String>("Error occured while validating journey with id = "+journey.getId(), HttpStatus.INTERNAL_SERVER_ERROR);
		}
		finally {
			if(browser != null) {
				browser.close();
			}
		}
		
		try{
			//STEP AND JOURNEY SETUP
			Step final_step = steps.get(steps.size()-1);
			final_step.setEndPage(final_page);

			if(final_step.getId() == null) {
				final_step.setKey(final_step.generateKey());
				Step result_record = step_service.save(final_step);
				journey_service.addStep(journey.getId(), result_record.getId());
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
			JourneyStatus status = getVerifiedOrDiscarded(journey, domain);
			Journey updated_journey = journey_service.updateFields(journey.getId(),
																status,
																journey.getKey(),
																journey.getOrderedIds());
			updated_journey.setSteps(steps);
			//Save all steps to be attached to journey record
			//for(Step step: steps) {
			//	journey_service.addStep(updated_journey.getId(), step.getId());
			//}
			
			//add Journey to domain map
			DomainMap domain_map = domain_map_service.findByDomainAuditId(domain_audit_id);
			
			if(JourneyStatus.DISCARDED.equals(updated_journey.getStatus()) 
				|| existsInJourney(steps.subList(0,  steps.size()-1), final_step)) {
				log.warn("DISCARDED Journey! "+updated_journey.getId() + " with status = "+updated_journey.getStatus());
				
				DiscardedJourneyMessage journey_message = new DiscardedJourneyMessage(	journey,
																						journey_msg.getBrowser(),
																						journey_msg.getAccountId(),
																						journey_msg.getAuditRecordId());

				String discarded_journey_json = mapper.writeValueAsString(journey_message);
				discarded_journey_topic.publish(discarded_journey_json);
			}
			else if(!final_step.getStartPage().getUrl().equals(final_step.getEndPage().getUrl()))
			{
				log.warn("VERIFIED Journey! "+updated_journey.getId() + " with status = "+updated_journey.getStatus());

				//if page state isn't associated with domain audit then send pageBuilt message
				PageBuiltMessage page_built_msg = new PageBuiltMessage(journey_msg.getAccountId(),
																		final_page.getId(),
																		journey_msg.getAuditRecordId());

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
																					BrowserType.CHROME, 
																					journey_msg.getAccountId(), 
																					journey_msg.getAuditRecordId());
				String journey_json = mapper.writeValueAsString(journey_message);
				verified_journey_topic.publish(journey_json);
			}
			else {
				log.warn("VERIFIED Journey! "+updated_journey.getId() + " with status = "+updated_journey.getStatus());

				VerifiedJourneyMessage journey_message = new VerifiedJourneyMessage(updated_journey,
																					BrowserType.CHROME, 
																					journey_msg.getAccountId(), 
																					journey_msg.getAuditRecordId());
				
				String journey_json = mapper.writeValueAsString(journey_message);
				verified_journey_topic.publish(journey_json);

			}
			return new ResponseEntity<String>("Successfully verified journey", HttpStatus.OK);
		} catch(Exception e){
			e.printStackTrace();
			journey_service.updateStatus(journey.getId(), JourneyStatus.CANDIDATE);
			return new ResponseEntity<String>("Error verifying journey", HttpStatus.INTERNAL_SERVER_ERROR);
		}
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
	private JourneyStatus getVerifiedOrDiscarded(Journey journey, Domain domain) throws MalformedURLException {
		//get last step
		Step last_step = journey.getSteps().get(journey.getSteps().size()-1);
		PageState second_to_last_page = PathUtils.getSecondToLastPageState(journey.getSteps());
		PageState final_page = PathUtils.getLastPageState(journey.getSteps());
		
		if((journey.getSteps().size() > 1 && !(last_step instanceof LandingStep)) 
				&& (final_page.equals(second_to_last_page)
						|| BrowserUtils.isExternalLink(new URL(BrowserUtils.sanitizeUserUrl(domain.getUrl())).getHost(), final_page.getUrl())))
		{
			return JourneyStatus.DISCARDED;
		}
		else {
			return JourneyStatus.VERIFIED;
		}
	}

	/**
	 * Constructs a {@link PageState page} including all {@link ElementState elements} on the page as a {@linkplain List}
	 * 
	 * @param browser
	 * @param domain_map_id TODO
	 * 
	 * @return
	 * @throws Exception
	 * 
	 * @pre browser != null
	 */
	private PageState buildPage(Browser browser, 
								long domain_map_id, 
								long audit_record_id, 
								String browser_url)
							throws Exception {
		assert browser != null;
		assert browser_url != null;
		
		PageState page_state = browser_service.buildPageState(browser, audit_record_id, browser_url);
		
		List<String> xpaths = browser_service.extractAllUniqueElementXpaths(page_state.getSrc());
		if(xpaths.size() <= 2){
			log.warn("ONLY 2 XPATHS WERE FOUND!!!   url =  "+page_state.getUrl() + ";;   source = "+page_state.getSrc() + ";;   xpaths = "+xpaths);
			throw new Exception("Error! Only 2 xpaths found");
		}
		page_state = page_state_service.save(domain_map_id, page_state);
		
		if(!page_state.isInteractiveElementExtractionComplete()){
			BufferedImage full_page_screenshot = ImageIO.read(new URL(page_state.getFullPageScreenshotUrl()));
			List<ElementState> element_states = browser_service.getDomElementStates(page_state, 
																					xpaths,
																					browser,
																					domain_map_id,
																					full_page_screenshot,
																					browser_url);
			if(page_state.getUrl().contains("blog")){
				for(ElementState element: element_states){
					if(element == null){
						continue;
					}
					log.warn("element xpath = "+element.getXpath());
				}
			}

			if(element_states.size() == 0){
				log.warn("Uh oh! No elements were found. WELL THIS IS CONCERNING!!! XPATHS used for element build = "+xpaths.size()+"url = "+page_state.getUrl() +" for page id = "+page_state.getKey());
				throw new Exception("Error! No elements were found");
			}

			log.warn("Extracted "+element_states.size() +" elements from DOM for page "+page_state.getUrl());
			long page_state_id = page_state.getId();
			
			element_states.stream()
							.filter(Objects::nonNull)
							.map( element -> element_state_service.save(domain_map_id, element))
							.map( element -> page_state_service.addElement(page_state_id, element.getId()))
							.collect(Collectors.toList());

			log.warn("Extracted1 "+element_states.size() +" elements from DOM for page "+page_state.getUrl());
			if(page_state.getUrl().contains("blog")){
				for(ElementState element: element_states){
					log.warn("element xpath = "+element.getXpath());
				}
			}

			page_state_service.updateInteractiveElementExtractionComplete(page_state.getId(), true);
		}
		
		return page_state;
	}

	/**
	 * Creates {@link Browser} connection and performs journey steps
	 * 
	 * @param steps
	 * @param browser TODO
	 * 
	 * @return current url
	 * @throws Exception
	 * 
	 * @pre steps != null
	 * @pre !steps.isEmpty()
	 * @pre browser != null;
	 */
	private String performJourneyStepsInBrowser(List<Step> steps, Browser browser) throws Exception  {
		assert steps != null;
		assert !steps.isEmpty();
		assert browser != null;
		
		String last_url = "";
		String current_url = "";
		//execute all steps sequentially in the journey
		for(Step step: steps) {
			step_executor.execute(browser, step);
			TimingUtils.pauseThread(2000L);
			current_url = browser.getDriver().getCurrentUrl();
			if(!last_url.equals(current_url)) {
				try{
					browser.waitForPageToLoad();
				}catch(Exception e){
					log.warn("waiting for page to load timed out..."+e.getMessage());
				}
			}
			last_url = current_url;
		}

		return current_url;
	}

	/**
	 * Checks if step is already present within the {@link Journey journey}
	 * 
	 * @param steps
	 * @param step
	 * 
	 * @return
	 * 
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