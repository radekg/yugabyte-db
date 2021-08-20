package com.yugabyte.yw.controllers;

import static com.yugabyte.yw.common.TestHelper.createTempFile;
import static org.junit.Assert.assertEquals;
import static com.yugabyte.yw.common.AssertHelper.assertBadRequest;
import static com.yugabyte.yw.common.AssertHelper.assertYWSE;
import static com.yugabyte.yw.common.AssertHelper.assertValue;
import static play.test.Helpers.contentAsString;

import akka.stream.javadsl.FileIO;
import akka.stream.javadsl.Source;
import akka.util.ByteString;
import com.fasterxml.jackson.databind.JsonNode;
import com.yugabyte.yw.common.FakeApiHelper;
import com.yugabyte.yw.common.FakeDBApplication;
import com.yugabyte.yw.common.ModelFactory;
import com.yugabyte.yw.common.TestHelper;
import com.yugabyte.yw.models.Customer;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.Users;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import play.libs.Json;
import play.mvc.Http;
import play.mvc.Result;

public class ScheduleScriptControllerTest extends FakeDBApplication {
  private Customer defaultCustomer;
  private Universe defaultUniverse;
  private Users defaultUser;

  private String validScriptParam =
      "{\"params1\" : \"val1\", \"params2\" : \"val2\", \"params3\": \"val3\"}";

  int OK = 200;

  @Before
  public void setUp() throws Exception {
    defaultCustomer = ModelFactory.testCustomer();
    defaultUniverse = ModelFactory.createUniverse(defaultCustomer.getCustomerId());
    defaultUser = ModelFactory.testUser(defaultCustomer);
    new File(TestHelper.TMP_PATH).mkdirs();
  }

  @After
  public void tearDown() throws IOException {
    FileUtils.deleteDirectory(new File(TestHelper.TMP_PATH));
  }

  private Result createScriptSchedule(
      UUID universeUUID,
      String cronExpression,
      String scriptParam,
      String timeLimitMins,
      boolean addScript) {
    List<Http.MultipartFormData.Part<Source<ByteString, ?>>> bodyData = new ArrayList<>();
    if (cronExpression != null) {
      bodyData.add(new Http.MultipartFormData.DataPart("cronExpression", cronExpression));
    }
    if (scriptParam != null) {
      bodyData.add(new Http.MultipartFormData.DataPart("scriptParameter", scriptParam));
    }
    if (timeLimitMins != null) {
      bodyData.add(new Http.MultipartFormData.DataPart("timeLimitMins", timeLimitMins));
    }
    if (addScript) {
      String tmpFile = createTempFile("User defined Script");
      Source<ByteString, ?> scriptFile = FileIO.fromFile(new File(tmpFile));
      bodyData.add(
          new Http.MultipartFormData.FilePart(
              "script", "test.py", "application/octet-stream", scriptFile));
    }
    String url =
        "/api/v1/customers/"
            + defaultCustomer.uuid
            + "/universes/"
            + universeUUID
            + "/scheduleScript";
    return FakeApiHelper.doRequestWithMultipartData("POST", url, bodyData, mat);
  }

  @Test
  public void testWithoutCronExpression() {
    Result result =
        assertYWSE(
            () ->
                createScriptSchedule(
                    defaultUniverse.universeUUID, null, validScriptParam, "5", true));
    assertBadRequest(result, "No cronExpression found");
  }

  @Test
  public void testWithInvalidCronExpression() {
    Result result =
        assertYWSE(
            () ->
                createScriptSchedule(
                    defaultUniverse.universeUUID, "* * * * ? * *", validScriptParam, "5", true));
    assertBadRequest(result, "Please provide a valid cronExpression");
  }

  @Test
  public void testWithoutTimeLimitMins() {
    Result result =
        assertYWSE(
            () ->
                createScriptSchedule(
                    defaultUniverse.universeUUID, "5 * * * *", validScriptParam, null, true));
    assertBadRequest(result, "Please provide a time limit in mins for script execution.");
  }

  @Test
  public void testWithInvalidTimeLimitMins() {
    Result result =
        assertYWSE(
            () ->
                createScriptSchedule(
                    defaultUniverse.universeUUID,
                    "5 * * * *",
                    validScriptParam,
                    "dummyValue",
                    true));
    assertBadRequest(result, "Please provide a valid time limit in mins for script execution.");
  }

  @Test
  public void testWithoutUploadingScript() {
    Result result =
        assertYWSE(
            () ->
                createScriptSchedule(
                    defaultUniverse.universeUUID, "5 * * * *", validScriptParam, "5", false));
    assertBadRequest(result, "Script file not found");
  }

  @Test
  public void testWithUnkownUniverse() {
    UUID unKnownUniverseUUID = UUID.randomUUID();
    Result result =
        assertYWSE(
            () ->
                createScriptSchedule(
                    unKnownUniverseUUID, "5 * * * *", validScriptParam, "5", true));
    assertBadRequest(result, "Cannot find universe");
  }

  @Test
  public void testCreateScriptSchedule() {
    Result result =
        createScriptSchedule(
            defaultUniverse.universeUUID, "5 * * * *", validScriptParam, "5", true);
    assertEquals(OK, result.status());
    JsonNode json = Json.parse(contentAsString(result));
    assertValue(json, "status", "Active");
  }

  @Test
  public void testCreateMultipleScriptSchedule() {
    createScriptSchedule(defaultUniverse.universeUUID, "5 * * * *", validScriptParam, "5", true);
    Result result =
        assertYWSE(
            () ->
                createScriptSchedule(
                    defaultUniverse.universeUUID, "5 * * * *", validScriptParam, "5", true));
    assertBadRequest(result, "A External Script is already scheduled for this universe.");
  }

  @Test
  public void testModifyScriptSchedule() {
    createScriptSchedule(defaultUniverse.universeUUID, "5 * * * *", validScriptParam, "1", true);
    List<Http.MultipartFormData.Part<Source<ByteString, ?>>> bodyData = new ArrayList<>();
    bodyData.add(new Http.MultipartFormData.DataPart("cronExpression", "2 * * * *"));
    bodyData.add(new Http.MultipartFormData.DataPart("timeLimitMins", "5"));
    String tmpFile = createTempFile("User defined Script");
    Source<ByteString, ?> scriptFile = FileIO.fromFile(new File(tmpFile));
    bodyData.add(
        new Http.MultipartFormData.FilePart(
            "script", "test.py", "application/octet-stream", scriptFile));
    String url =
        "/api/v1/customers/"
            + defaultCustomer.uuid
            + "/universes/"
            + defaultUniverse.universeUUID
            + "/updateScheduledScript";
    Result result = FakeApiHelper.doRequestWithMultipartData("PUT", url, bodyData, mat);
    assertEquals(OK, result.status());
    JsonNode json = Json.parse(contentAsString(result));
    assertValue(json, "cronExpression", "2 * * * *");
  }

  @Test
  public void testStopScriptSchedule() {
    createScriptSchedule(defaultUniverse.universeUUID, "5 * * * *", validScriptParam, "5", true);
    String url =
        "/api/v1/customers/"
            + defaultCustomer.uuid
            + "/universes/"
            + defaultUniverse.universeUUID
            + "/stopScheduledScript";
    Result result = FakeApiHelper.doRequest("PUT", url);
    assertEquals(OK, result.status());
    assertEquals(OK, result.status());
    JsonNode json = Json.parse(contentAsString(result));
    assertValue(json, "status", "Stopped");
  }
}
