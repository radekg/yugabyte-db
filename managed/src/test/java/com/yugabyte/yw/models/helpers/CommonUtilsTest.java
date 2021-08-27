// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.models.helpers;

import static com.yugabyte.yw.common.AssertHelper.assertValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yugabyte.yw.common.EmailFixtures;
import com.yugabyte.yw.common.alerts.AlertChannelEmailParams;
import java.util.Collections;
import org.junit.Test;
import play.libs.Json;

public class CommonUtilsTest {

  @Test
  public void maskConfigWithSensitiveData() {
    ObjectNode config = prepareConfig();
    JsonNode maskedData = CommonUtils.maskConfig(config);
    assertValue(maskedData, "SOME_KEY", "SE**********TA");
    assertValue(maskedData, "KEY_DATA", "SE**********TA");
    assertValue(maskedData, "MY_KEY_DATA", "SE**********TA");
    assertValue(maskedData, "SOME_SECRET", "SE**********TA");
    assertValue(maskedData, "SECRET_DATA", "SE**********TA");
    assertValue(maskedData, "MY_SECRET_DATA", "SE**********TA");
    assertValue(maskedData, "MY_PASSWORD", "********");
    assertValue(maskedData, "DATA", "VALUE");
    assertValue(maskedData, "MY_KEY", "********");
  }

  private ObjectNode prepareConfig() {
    ObjectNode config = Json.newObject();
    config.put("SOME_KEY", "SENSITIVE_DATA");
    config.put("KEY_DATA", "SENSITIVE_DATA");
    config.put("MY_KEY_DATA", "SENSITIVE_DATA");
    config.put("SOME_SECRET", "SENSITIVE_DATA");
    config.put("SECRET_DATA", "SENSITIVE_DATA");
    config.put("MY_SECRET_DATA", "SENSITIVE_DATA");
    config.put("MY_PASSWORD", "SENSITIVE_DATA"); // Strong sensitive, complete masking.
    config.put("MY_KEY", "DATA"); // Sensitive, complete masking as the lenght is less than 5.
    config.put("DATA", "VALUE");
    return config;
  }

  @Test
  public void testMaskConfigWithoutSensitiveData() {
    ObjectNode config = Json.newObject();
    config.put("SOME_DATA", "VISIBLE_DATA");
    JsonNode maskedData = CommonUtils.maskConfig(config);
    assertValue(maskedData, "SOME_DATA", "VISIBLE_DATA");
  }

  @Test
  public void testMaskConfigWithNullData() {
    JsonNode maskedData = CommonUtils.maskConfig(null);
    assertThat(maskedData.size(), is(0));
    assertThat(maskedData, notNullValue());
  }

  @Test
  public void testGetNodeProperty() {
    String propertyPath = "data.test.foo";
    JsonNode nullNode = CommonUtils.getNodeProperty(null, propertyPath);
    assertThat(nullNode, nullValue());
    JsonNode testObject = Json.newObject();
    nullNode = CommonUtils.getNodeProperty(testObject, propertyPath);
    ObjectMapper mapper = new ObjectMapper();
    ObjectNode rootNode = mapper.createObjectNode();
    ObjectNode dataNode = rootNode.putObject("data");
    dataNode.put("foo", "fail");
    ObjectNode testNode = dataNode.putObject("test");
    testNode.put("foo", "success");
    JsonNode result = CommonUtils.getNodeProperty(rootNode, propertyPath);
    assertThat(result.asText(), is("success"));
  }

  @Test
  public void testUnmaskConfig() {
    ObjectNode config = prepareConfig();
    JsonNode maskedData = CommonUtils.maskConfig(config);

    // 1. No changes.
    JsonNode unmaskedData = CommonUtils.unmaskConfig(config, maskedData);
    assertThat(unmaskedData, equalTo(config));

    // 2. Two fields changed.
    ((ObjectNode) maskedData).put("MY_KEY_DATA", "SENSITIVE_DATA_2");
    ((ObjectNode) maskedData).put("MY_PASSWORD", "SENSITIVE_DATA_2");
    unmaskedData = CommonUtils.unmaskConfig(config, maskedData);

    assertValue(unmaskedData, "SOME_KEY", "SENSITIVE_DATA");
    assertValue(unmaskedData, "KEY_DATA", "SENSITIVE_DATA");
    assertValue(unmaskedData, "SOME_SECRET", "SENSITIVE_DATA");
    assertValue(unmaskedData, "SECRET_DATA", "SENSITIVE_DATA");
    assertValue(unmaskedData, "MY_SECRET_DATA", "SENSITIVE_DATA");
    assertValue(unmaskedData, "DATA", "VALUE");
    assertValue(unmaskedData, "MY_KEY_DATA", "SENSITIVE_DATA_2");
    assertValue(unmaskedData, "MY_PASSWORD", "SENSITIVE_DATA_2");
  }

  @Test
  public void testMaskComplexObject() {
    AlertChannelEmailParams params = new AlertChannelEmailParams();
    params.recipients = Collections.singletonList("test@test.com");
    params.smtpData = EmailFixtures.createSmtpData();

    AlertChannelEmailParams maskedParams = CommonUtils.maskObject(params);
    assertThat(maskedParams, not(params));
    assertThat(maskedParams.smtpData.smtpPassword, not(params.smtpData.smtpPassword));

    AlertChannelEmailParams unmaskedParams = CommonUtils.unmaskObject(params, maskedParams);
    assertThat(unmaskedParams, not(maskedParams));
    assertThat(unmaskedParams, equalTo(params));
  }
}
