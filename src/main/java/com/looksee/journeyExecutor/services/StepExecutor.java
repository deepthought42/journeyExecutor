package com.looksee.journeyExecutor.services;

import org.openqa.selenium.By;
import org.openqa.selenium.Point;
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
import com.looksee.utils.TimingUtils;

@Service
public class StepExecutor {
	@SuppressWarnings("unused")
	private static Logger log = LoggerFactory.getLogger(StepExecutor.class);
	
	public void execute(Browser browser, Step step) {
		assert browser != null;
		assert step != null;
		
		ActionFactory action_factory = new ActionFactory(browser.getDriver());
		
		if(step instanceof SimpleStep) {
			SimpleStep simple_step = (SimpleStep)step;
			log.warn(simple_step.getAction() + "  on element = "+simple_step.getElementState());
			ElementState element = simple_step.getElementState();
			WebElement web_element = browser.getDriver().findElement(By.xpath(element.getXpath()));
			Point offset = browser.getViewportScrollOffset();
			log.warn("ACTION offset before scrolling = "+offset);
			browser.scrollToElement(web_element);
			offset = browser.getViewportScrollOffset();
			log.warn("ACTION offset after scrolling = "+offset);
			log.warn("============================================================");;
			log.warn("browser dimension = "+browser.getViewportSize());
			log.warn("browser offset = "+browser.getXScrollOffset()+" , "+browser.getYScrollOffset());
			log.warn("element xpath = "+element.getXpath());
			log.warn("element location = "+element.getXLocation()+" , "+element.getYLocation());
			log.warn("element dimension = "+element.getWidth()+" , "+element.getHeight());
			
			action_factory.execAction(web_element, "", simple_step.getAction());
			TimingUtils.pauseThread(5000L);
		}
		else if(step instanceof LoginStep) {
			LoginStep login_step = (LoginStep)step;
			WebElement username_element = browser.getDriver().findElement(By.xpath(login_step.getUsernameElement().getXpath()));
			action_factory.execAction(username_element, login_step.getTestUser().getUsername(), Action.SEND_KEYS);
			
			WebElement password_element = browser.getDriver().findElement(By.xpath(login_step.getPasswordElement().getXpath()));
			action_factory.execAction(password_element, login_step.getTestUser().getPassword(), Action.SEND_KEYS);

			WebElement submit_element = browser.getDriver().findElement(By.xpath(login_step.getSubmitElement().getXpath()));
			action_factory.execAction(submit_element, "", Action.CLICK);
			TimingUtils.pauseThread(5000L);
		}
		else if(step instanceof LandingStep) {
			PageState initial_page = step.getStartPage();
			String sanitized_url = BrowserUtils.sanitizeUrl(initial_page.getUrl(), initial_page.isSecured());
			browser.navigateTo(sanitized_url);
			browser.scrollToBottomOfPage();
		}
		else {
			log.warn("Unknown step type during execution = " + step.getKey());
		}
	}
}
