/*
 * Copyright 2021 YugaByte, Inc. and Contributors
 *
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0.txt
 */
package com.yugabyte.yw.common;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;

public class ThrownMatcher extends TypeSafeMatcher<Runnable> {

  private static final String NOTHING = "nothing";

  private final String expected;
  private final String expectedMessage;
  private String actual;
  private String actualMessage;

  public ThrownMatcher(String s, String expectedMessage) {
    expected = s;
    this.expectedMessage = expectedMessage;
  }

  public static Matcher<Runnable> thrown(Class<? extends Throwable> expected) {
    return new ThrownMatcher(expected.getName(), null);
  }

  public static Matcher<Runnable> thrown(
      Class<? extends Throwable> expected, String expectedMessage) {
    return new ThrownMatcher(expected.getName(), expectedMessage);
  }

  @Override
  public boolean matchesSafely(Runnable action) {
    actual = NOTHING;
    actualMessage = NOTHING;
    try {
      action.run();
      return false;
    } catch (Throwable t) {
      actual = t.getClass().getName();
      actualMessage = t.getMessage();
      return actual.equals(expected)
          && (expectedMessage == null || actualMessage.equals(expectedMessage));
    }
  }

  @Override
  public void describeTo(Description description) {
    if (!actual.equals(expected)) {
      description.appendText("Should have thrown " + expected + " but threw " + actual);
    }
    if (expectedMessage != null) {
      description.appendText(
          "Should have thrown "
              + expected
              + " with message '"
              + expectedMessage
              + "' but threw "
              + actual
              + " with message '"
              + actualMessage
              + "'");
    }
  }
}
