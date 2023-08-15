package com.looksee.journeyExecutor.services;

import org.openqa.selenium.By;
import org.openqa.selenium.Point;
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
import com.looksee.utils.TimingUtils;

@Service
public class StepExecutor {
	@SuppressWarnings("unused")
	private static Logger log = LoggerFactory.getLogger(StepExecutor.class);
	
	public void execute(Browser browser, Step step) {
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
				
				log.warn("ACTION offset BEFORE scrolling = "+browser.getYScrollOffset());
				
				log.warn("Element location = ("+web_element.getLocation().getX()+" , "+web_element.getLocation().getY()+")");
				//browser.scrollToElement(web_element);
				browser.scrollTo(element.getXLocation(), element.getYLocation());
				ActionFactory action_factory = new ActionFactory(browser.getDriver());
				//action_factory.execAction(web_element, "", Action.MOUSE_OVER);
				//TimingUtils.pauseThread(1000L);
				log.warn("performing mouse over action");
				Point offsets = browser.getViewportScrollOffset();
				log.warn("ACTION offset AFTER scrolling = "+browser.getYScrollOffset());
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
				TimingUtils.pauseThread(5000);
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
			log.warn("============================================================");;
			e.printStackTrace();
			throw e;
		}
	}
}
