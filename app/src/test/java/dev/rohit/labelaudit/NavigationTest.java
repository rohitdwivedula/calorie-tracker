package dev.rohit.labelaudit;

import static org.junit.Assert.*;

import org.junit.Test;

public class NavigationTest {
  @Test
  public void backTraversesIngredientFlow() {
    Navigation<String> n = new Navigation<>();
    n.show(null, "Today", null);
    n.show("Today", "dish", "home");
    n.show("dish", "picker", "typed dish");
    n.show("picker", "portion", "saved foods");
    assertEquals("saved foods", n.back().state);
    assertEquals("typed dish", n.back().state);
    assertEquals("Today", n.back().route);
    assertFalse(n.canGoBack());
  }

  @Test
  public void savingIngredientUnwindsChildren() {
    Navigation<Integer> n = new Navigation<>();
    n.show("Today", "dish", 1);
    n.show("dish", "picker", 2);
    n.show("picker", "editor", 3);
    n.show("editor", "portion", 4);
    n.show("portion", "dish", 5);
    assertEquals("Today", n.back().route);
    assertFalse(n.canGoBack());
  }

  @Test
  public void homeClearsHistory() {
    Navigation<Integer> n = new Navigation<>();
    n.show("Today", "foods", 1);
    n.show("foods", "portion", 2);
    n.show("portion", "Today", 3);
    assertFalse(n.canGoBack());
    assertNull(n.back());
  }

  @Test
  public void refreshingScreenDoesNotAddBackSteps() {
    Navigation<Integer> n = new Navigation<>();
    n.show("Today", "details", 1);
    n.show("details", "details", 2);
    assertEquals("Today", n.back().route);
    assertFalse(n.canGoBack());
  }

  @Test
  public void editorReturnsToExactPortionState() {
    Navigation<String> n = new Navigation<>();
    n.show("foods", "portion", "list scroll");
    n.show("portion", "editor", "2 servings");
    assertEquals("2 servings", n.back().state);
    assertEquals("list scroll", n.back().state);
  }
}
