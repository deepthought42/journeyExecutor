package com.looksee.journeyExecutor.services;

import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.MoveTargetOutOfBoundsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.looksee.browsing.ActionFactory;
import com.looksee.journeyExecutor.models.Browser;
import com.looksee.journeyExecutor.models.ElementState;
import com.looksee.journeyExecutor.models.PageState;
import com.looksee.journeyExecutor.models.enums.Action;
import com.looksee.journeyExecutor.models.journeys.LandingStep;
import com.looksee.journeyExecutor.models.journeys.LoginStep;
import com.looksee.journeyExecutor.models.journeys.SimpleStep;
import com.looksee.journeyExecutor.models.journeys.Step;
import com.looksee.utils.BrowserUtils;

@Service
public class StepExecutor {
	@SuppressWarnings("unused")
	private static Logger log = LoggerFactory.getLogger(StepExecutor.class);
	
	public void execute(Browser browser, Step step) throws Exception {
		assert browser != null;
		assert step != null;
		
		ElementState current_element = null;
		try {
			if(step instanceof SimpleStep) {
				SimpleStep simple_step = (SimpleStep)step;
				log.warn(simple_step.getAction() + "  on element = "+simple_step.getElementState());
				ElementState element = simple_step.getElementState();
				current_element=element;
				
				WebElement web_element = browser.getDriver().findElement(By.xpath(element.getXpath()));
				int escape_count = 0;
				int escape_limit = 30;
				long last_y_offset = browser.getYScrollOffset();
				while(escape_count < escape_limit && !BrowserService.isElementVisibleInPane(browser, 
															 web_element.getLocation(), 
															 web_element.getSize())) 
				{			
					log.warn("scrolling to element location = "+element.getYLocation()+"; browser y-offset = "+browser.getYScrollOffset());
					browser.scrollToElementCentered(web_element);
					long current_y_offset = browser.getYScrollOffset();
					if(current_y_offset == last_y_offset) {
						throw new Exception("Scrolling not working correctly. Retry again in a few minutes");
					}
					escape_count++;
				}
				
				ActionFactory action_factory = new ActionFactory(browser.getDriver());
				action_factory.execAction(web_element, "", simple_step.getAction());
			}
			else if(step instanceof LoginStep) {
				LoginStep login_step = (LoginStep)step;
				WebElement username_element = browser.getDriver().findElement(By.xpath(login_step.getUsernameElement().getXpath()));
				ActionFactory action_factory = new ActionFactory(browser.getDriver());
				action_factory.execAction(username_element, login_step.getTestUser().getUsername(), Action.SEND_KEYS);
				
				WebElement password_element = browser.getDriver().findElement(By.xpath(login_step.getPasswordElement().getXpath()));
				action_factory.execAction(password_element, login_step.getTestUser().getPassword(), Action.SEND_KEYS);
	
				WebElement submit_element = browser.getDriver().findElement(By.xpath(login_step.getSubmitElement().getXpath()));
				action_factory.execAction(submit_element, "", Action.CLICK);
			}
			else if(step instanceof LandingStep) {
				PageState initial_page = step.getStartPage();
				String sanitized_url = BrowserUtils.sanitizeUrl(initial_page.getUrl(), initial_page.isSecured());
				browser.navigateTo(sanitized_url);
			}
			else {
				log.warn("Unknown step type during execution = " + step.getKey());
			}
		}
		catch(MoveTargetOutOfBoundsException e) {
			log.warn("MOVE TO TARGET EXCEPTION FOR ELEMENT = "+e.getMessage());
			log.warn("============================================================");;
			log.warn("URL = "+browser.getDriver().getCurrentUrl());
			log.warn("browser dimension = "+browser.getViewportSize());
			log.warn("browser offset = "+browser.getXScrollOffset()+" , "+browser.getYScrollOffset());
			log.warn("element xpath = "+current_element.getXpath());
			log.warn("element location = "+current_element.getXLocation()+" , "+current_element.getYLocation());
			log.warn("element dimension = "+current_element.getWidth()+" , "+current_element.getHeight());
			log.warn("is element visible? = "+current_element.isVisible());
			log.warn("============================================================");;
			e.printStackTrace();
			throw e;
		}
	}
}
