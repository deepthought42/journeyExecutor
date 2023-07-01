package com.looksee.browsing;

import java.util.NoSuchElementException;
import java.util.Random;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Point;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;

import com.looksee.journeyExecutor.models.Element;
import com.looksee.journeyExecutor.models.ElementState;
import com.looksee.journeyExecutor.models.enums.Action;


/**
 * Provides methods for crawling web pages using Selenium
 */
@Component
public class Crawler {
	private static Logger log = LoggerFactory.getLogger(Crawler.class);
	
	/**
	 * Executes the given {@link ElementAction element action} pair such that
	 * the action is executed against the element
	 *
	 * @return whether action was able to be performed on element or not
	 */
	public static void performAction(Action action, Element elem, WebDriver driver) throws NoSuchElementException{
		ActionFactory actionFactory = new ActionFactory(driver);
		WebElement element = driver.findElement(By.xpath(elem.getXpath()));
		actionFactory.execAction(element, "", action);
		//TimingUtils.pauseThread(500L);
	}

	/**
	 * Executes the given {@link ElementAction element action} pair such that
	 * the action is executed against the element
	 *
	 * @return whether action was able to be performed on element or not
	 */
	public static void performAction(Action action, Element elem, WebDriver driver, Point location) throws NoSuchElementException{
		ActionFactory actionFactory = new ActionFactory(driver);
		WebElement element = driver.findElement(By.xpath(elem.getXpath()));
		actionFactory.execAction(element, "", action);
		//TimingUtils.pauseThread(500L);
	}
	
	public static void scrollDown(WebDriver driver, int distance)
    {
        ((JavascriptExecutor)driver).executeScript("scroll(0,"+ distance +");");
    }
	
	/**
	 * 
	 * @param web_element
	 * @return
	 * 
	 * @pre web_element != null
	 * @pre child_element != null
	 * @pre offset != null
	 */
	public static Point generateRandomLocationWithinElementButNotWithinChildElements(WebElement web_element, 
																					ElementState child_element) {
		assert web_element != null;
		assert child_element != null;
		
		Point elem_location = web_element.getLocation();

		int left_lower_x = 0;
		int left_upper_x = child_element.getXLocation()- elem_location.getX();
		int right_lower_x = (child_element.getXLocation() - elem_location.getX()) + child_element.getWidth();
		int right_upper_x = web_element.getSize().getWidth();
		
		int top_lower_y = 0;
		int top_upper_y = child_element.getYLocation() - elem_location.getY();
		int bottom_lower_y = child_element.getYLocation() - elem_location.getY() + child_element.getHeight();
		int bottom_upper_y = web_element.getSize().getHeight();
		
		int x_coord = 0;
		int y_coord = 0;
		
		if(left_lower_x != left_upper_x && left_upper_x > 0){
			x_coord = new Random().nextInt(left_upper_x);
		}
		else {
			int difference = right_upper_x - right_lower_x;
			int x_offset = 0;
			if(difference == 0){
				x_offset = new Random().nextInt(right_upper_x);
			}
			else{
				x_offset = new Random().nextInt(difference);
			}
			x_coord = right_lower_x + x_offset;
		}
		
		if(top_lower_y != top_upper_y && top_upper_y > 0){
			y_coord = new Random().nextInt(top_upper_y);
		}
		else {
			int difference = bottom_upper_y - bottom_lower_y;
			int y_offset = 0;
			if(difference == 0){
				y_offset = new Random().nextInt(bottom_upper_y);
			}
			else{
				y_offset = new Random().nextInt(bottom_upper_y - bottom_lower_y);
			}
			y_coord = bottom_lower_y + y_offset;
		}

		return new Point(x_coord, y_coord);
	}
}
