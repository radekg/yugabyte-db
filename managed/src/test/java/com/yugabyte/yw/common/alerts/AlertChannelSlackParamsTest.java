// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.common.alerts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import junitparams.converters.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(JUnitParamsRunner.class)
public class AlertChannelSlackParamsTest {

  @Test
  // @formatter:off
  @Parameters({
    "null, null, null, Slack parameters: username is empty.",
    "channel, null, null, Slack parameters: incorrect WebHook url.",
    "channel, incorrect url, null, Slack parameters: incorrect WebHook url.",
    "channel, http://www.google.com, null, null",
    "channel, http://www.google.com, incorrect url, Slack parameters: incorrect icon url.",
    "channel, http://www.google.com, http://www.google.com, null",
  })
  // @formatter:on
  public void testValidate(
      @Nullable String username,
      @Nullable String webHookUrl,
      @Nullable String iconUrl,
      @Nullable String expectedError)
      throws PlatformValidationException {
    AlertChannelSlackParams params = new AlertChannelSlackParams();
    params.username = username;
    params.webhookUrl = webHookUrl;
    params.iconUrl = iconUrl;
    if (expectedError != null) {
      PlatformValidationException ex =
          assertThrows(
              PlatformValidationException.class,
              () -> {
                params.validate();
              });
      assertEquals(expectedError, ex.getMessage());
    } else {
      params.validate();
    }
  }
}
