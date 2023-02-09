package com.looksee.journeyExecutor.services;

import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
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
	
	public void execute(Browser browser, Step step) {
		assert browser != null;
		assert step != null;
		
		ActionFactory action_factory = new ActionFactory(browser.getDriver());

		if(step instanceof SimpleStep) {
			ElementState element = ((SimpleStep)step).getElementState();
			log.warn("executing simple step with element = "+  ((SimpleStep)step).getElementState());
			WebElement web_element = browser.getDriver().findElement(By.xpath(element.getXpath()));
			action_factory.execAction(web_element, "", ((SimpleStep)step).getAction());
		}
		else if(step instanceof LoginStep) {
			LoginStep login_step = (LoginStep)step;
			log.warn("executing login step= "+  ((LoginStep)step).getUsernameElement());
			WebElement username_element = browser.getDriver().findElement(By.xpath(login_step.getUsernameElement().getXpath()));
			action_factory.execAction(username_element, login_step.getTestUser().getUsername(), Action.SEND_KEYS);
			
			WebElement password_element = browser.getDriver().findElement(By.xpath(login_step.getPasswordElement().getXpath()));
			action_factory.execAction(password_element, login_step.getTestUser().getPassword(), Action.SEND_KEYS);

			WebElement submit_element = browser.getDriver().findElement(By.xpath(login_step.getSubmitElement().getXpath()));
			action_factory.execAction(submit_element, "", Action.CLICK);
		}
		else if(step instanceof LandingStep) {
			log.warn("executing landing step= "+  ((LandingStep)step));
			PageState initial_page = step.getStartPage();
			String sanitized_url = BrowserUtils.sanitizeUrl(initial_page.getUrl(), initial_page.isSecured());

			browser.navigateTo(sanitized_url);
		}
		else {
			log.warn("Unknown step type during execution = " + step.getKey());
		}
	}
}
