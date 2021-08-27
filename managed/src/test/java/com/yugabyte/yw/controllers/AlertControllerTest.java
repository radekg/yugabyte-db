// Copyright (c) Yugabyte, Inc.

package com.yugabyte.yw.controllers;

import static com.yugabyte.yw.common.AssertHelper.assertBadRequest;
import static com.yugabyte.yw.common.AssertHelper.assertYWSE;
import static com.yugabyte.yw.common.FakeApiHelper.doRequestWithAuthToken;
import static com.yugabyte.yw.common.FakeApiHelper.doRequestWithAuthTokenAndBody;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.fail;
import static play.mvc.Http.Status.OK;
import static play.test.Helpers.contentAsString;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.yugabyte.yw.common.AlertTemplate;
import com.yugabyte.yw.common.AssertHelper;
import com.yugabyte.yw.common.EmailFixtures;
import com.yugabyte.yw.common.FakeDBApplication;
import com.yugabyte.yw.common.ModelFactory;
import com.yugabyte.yw.common.ValidatingFormFactory;
import com.yugabyte.yw.common.alerts.AlertConfigurationService;
import com.yugabyte.yw.common.alerts.AlertDefinitionService;
import com.yugabyte.yw.common.metrics.MetricLabelsBuilder;
import com.yugabyte.yw.common.alerts.AlertChannelEmailParams;
import com.yugabyte.yw.common.alerts.AlertChannelParams;
import com.yugabyte.yw.common.alerts.AlertChannelService;
import com.yugabyte.yw.common.alerts.AlertChannelSlackParams;
import com.yugabyte.yw.common.alerts.AlertDestinationService;
import com.yugabyte.yw.common.alerts.AlertService;
import com.yugabyte.yw.common.alerts.AlertUtils;
import com.yugabyte.yw.common.metrics.MetricService;
import com.yugabyte.yw.common.alerts.SmtpData;
import com.yugabyte.yw.common.config.impl.SettableRuntimeConfigFactory;
import com.yugabyte.yw.forms.filters.AlertApiFilter;
import com.yugabyte.yw.forms.filters.AlertConfigurationApiFilter;
import com.yugabyte.yw.forms.filters.AlertTemplateApiFilter;
import com.yugabyte.yw.forms.paging.AlertConfigurationPagedApiQuery;
import com.yugabyte.yw.forms.paging.AlertPagedApiQuery;
import com.yugabyte.yw.models.Alert;
import com.yugabyte.yw.models.AlertConfiguration;
import com.yugabyte.yw.models.AlertConfigurationThreshold;
import com.yugabyte.yw.models.AlertDefinition;
import com.yugabyte.yw.models.AlertConfiguration.SortBy;
import com.yugabyte.yw.models.AlertConfigurationTarget;
import com.yugabyte.yw.models.AlertChannel;
import com.yugabyte.yw.models.AlertChannel.ChannelType;
import com.yugabyte.yw.models.AlertDestination;
import com.yugabyte.yw.models.Customer;
import com.yugabyte.yw.models.Metric;
import com.yugabyte.yw.models.MetricKey;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.Users;
import com.yugabyte.yw.models.common.Unit;
import com.yugabyte.yw.models.filters.AlertFilter;
import com.yugabyte.yw.models.helpers.CommonUtils;
import com.yugabyte.yw.models.helpers.KnownAlertLabels;
import com.yugabyte.yw.models.helpers.PlatformMetrics;
import com.yugabyte.yw.models.paging.AlertConfigurationPagedResponse;
import com.yugabyte.yw.models.paging.AlertPagedResponse;
import com.yugabyte.yw.models.paging.PagedQuery;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import play.libs.Json;
import play.mvc.Result;

@RunWith(MockitoJUnitRunner.class)
public class AlertControllerTest extends FakeDBApplication {

  private Customer customer;

  private Users user;

  private String authToken;

  private Universe universe;

  @Mock private ValidatingFormFactory formFactory;

  @InjectMocks private AlertController controller;

  private SmtpData defaultSmtp = EmailFixtures.createSmtpData();

  private int alertChannelIndex;

  private int alertDestinationIndex;

  private MetricService metricService;
  private AlertService alertService;
  private AlertDefinitionService alertDefinitionService;
  private AlertConfigurationService alertConfigurationService;
  private AlertChannelService alertChannelService;
  private AlertDestinationService alertDestinationService;

  private AlertConfiguration alertConfiguration;
  private AlertDefinition alertDefinition;

  @Before
  public void setUp() {
    customer = ModelFactory.testCustomer();
    user = ModelFactory.testUser(customer);
    authToken = user.createAuthToken();

    universe = ModelFactory.createUniverse();

    metricService = new MetricService();
    alertService = new AlertService();
    alertDefinitionService = new AlertDefinitionService(alertService);
    alertConfigurationService =
        new AlertConfigurationService(
            alertDefinitionService, new SettableRuntimeConfigFactory(app.config()));
    alertChannelService = new AlertChannelService();
    alertDestinationService =
        new AlertDestinationService(alertChannelService, alertConfigurationService);
    alertConfiguration = ModelFactory.createAlertConfiguration(customer, universe);
    alertDefinition = ModelFactory.createAlertDefinition(customer, universe, alertConfiguration);

    controller.setMetricService(metricService);
    controller.setAlertService(alertService);
    controller.setAlertConfigurationService(alertConfigurationService);
  }

  private void checkEmptyAnswer(String url) {
    Result result = doRequestWithAuthToken("GET", url, authToken);
    assertThat(result.status(), equalTo(OK));
    assertThat(contentAsString(result), equalTo("[]"));
  }

  private AlertChannelParams getAlertChannelParamsForTests() {
    AlertChannelEmailParams arParams = new AlertChannelEmailParams();
    arParams.recipients = Collections.singletonList("test@test.com");
    arParams.smtpData = defaultSmtp;
    return arParams;
  }

  private ObjectNode getAlertChannelJson() {
    ObjectNode data = Json.newObject();
    data.put("name", getAlertChannelName());
    data.put("params", Json.toJson(getAlertChannelParamsForTests()));
    return data;
  }

  private AlertChannel channelFromJson(JsonNode json) {
    ObjectMapper mapper = new ObjectMapper();
    try {
      return mapper.treeToValue(json, AlertChannel.class);
    } catch (JsonProcessingException e) {
      fail("Bad json format.");
      return null;
    }
  }

  private AlertChannel createAlertChannel() {
    ObjectNode channelFormDataJson = getAlertChannelJson();
    Result result =
        doRequestWithAuthTokenAndBody(
            "POST",
            "/api/customers/" + customer.getUuid() + "/alert_channels",
            authToken,
            channelFormDataJson);
    assertThat(result.status(), equalTo(OK));
    return channelFromJson(Json.parse(contentAsString(result)));
  }

  @Test
  public void testCreateAndListAlertChannel_OkResult() {
    checkEmptyAnswer("/api/customers/" + customer.getUuid() + "/alert_channels");

    AlertChannel createdChannel = createAlertChannel();
    assertThat(createdChannel.getUuid(), notNullValue());

    assertThat(
        AlertUtils.getJsonTypeName(createdChannel.getParams()), equalTo(ChannelType.Email.name()));
    assertThat(createdChannel.getParams(), equalTo(getAlertChannelParamsForTests()));

    Result result =
        doRequestWithAuthToken(
            "GET", "/api/customers/" + customer.getUuid() + "/alert_channels", authToken);

    assertThat(result.status(), equalTo(OK));
    JsonNode listedChannels = Json.parse(contentAsString(result));
    assertThat(listedChannels.size(), equalTo(1));
    assertThat(
        channelFromJson(listedChannels.get(0)), equalTo(CommonUtils.maskObject(createdChannel)));
  }

  @Test
  public void testCreateAlertChannel_ErrorResult() {
    checkEmptyAnswer("/api/customers/" + customer.getUuid() + "/alert_channels");
    ObjectNode data = Json.newObject();
    data.put("name", "name");
    data.put("params", Json.toJson(new AlertChannelEmailParams()));
    Result result =
        assertYWSE(
            () ->
                doRequestWithAuthTokenAndBody(
                    "POST",
                    "/api/customers/" + customer.getUuid() + "/alert_channels",
                    authToken,
                    data));

    AssertHelper.assertBadRequest(
        result, "Email parameters: only one of defaultRecipients and recipients[] should be set.");
    checkEmptyAnswer("/api/customers/" + customer.getUuid() + "/alert_channels");
  }

  @Test
  public void testGetAlertChannel_OkResult() {
    AlertChannel createdChannel = createAlertChannel();
    assertThat(createdChannel.getUuid(), notNullValue());

    Result result =
        doRequestWithAuthToken(
            "GET",
            "/api/customers/" + customer.getUuid() + "/alert_channels/" + createdChannel.getUuid(),
            authToken);
    assertThat(result.status(), equalTo(OK));

    AlertChannel channel = channelFromJson(Json.parse(contentAsString(result)));
    assertThat(channel, notNullValue());
    assertThat(channel, equalTo(CommonUtils.maskObject(createdChannel)));
  }

  @Test
  public void testGetAlertChannel_ErrorResult() {
    UUID uuid = UUID.randomUUID();
    Result result =
        assertYWSE(
            () ->
                doRequestWithAuthToken(
                    "GET",
                    "/api/customers/" + customer.getUuid() + "/alert_channels/" + uuid.toString(),
                    authToken));
    AssertHelper.assertBadRequest(result, "Invalid Alert Channel UUID: " + uuid.toString());
  }

  @Test
  public void testUpdateAlertChannel_OkResult() {
    AlertChannel createdChannel = createAlertChannel();
    assertThat(createdChannel.getUuid(), notNullValue());

    AlertChannelEmailParams params = (AlertChannelEmailParams) createdChannel.getParams();
    params.recipients = Collections.singletonList("new@test.com");
    params.smtpData.smtpPort = 1111;
    createdChannel.setParams(params);

    ObjectNode data = Json.newObject();
    data.put("alertChannelUUID", createdChannel.getUuid().toString())
        .put("name", createdChannel.getName())
        .put("params", Json.toJson(createdChannel.getParams()));

    Result result =
        doRequestWithAuthTokenAndBody(
            "PUT",
            "/api/customers/"
                + customer.getUuid()
                + "/alert_channels/"
                + createdChannel.getUuid().toString(),
            authToken,
            data);
    assertThat(result.status(), equalTo(OK));

    AlertChannel updatedChannel = channelFromJson(Json.parse(contentAsString(result)));

    assertThat(updatedChannel, notNullValue());
    assertThat(updatedChannel, equalTo(CommonUtils.maskObject(createdChannel)));
  }

  @Test
  public void testUpdateAlertChannel_ErrorResult() {
    AlertChannel createdChannel = createAlertChannel();
    assertThat(createdChannel.getUuid(), notNullValue());

    createdChannel.setParams(new AlertChannelSlackParams());

    ObjectNode data = Json.newObject();
    data.put("alertChannelUUID", createdChannel.getUuid().toString())
        .put("name", createdChannel.getName())
        .put("params", Json.toJson(createdChannel.getParams()));

    Result result =
        assertYWSE(
            () ->
                doRequestWithAuthTokenAndBody(
                    "PUT",
                    "/api/customers/"
                        + customer.getUuid()
                        + "/alert_channels/"
                        + createdChannel.getUuid().toString(),
                    authToken,
                    data));
    AssertHelper.assertBadRequest(
        result, "Unable to create/update alert channel: Slack parameters: username is empty.");
  }

  @Test
  public void testDeleteAlertChannel_OkResult() {
    checkEmptyAnswer("/api/customers/" + customer.getUuid() + "/alert_channels");

    AlertChannel createdChannel = createAlertChannel();
    assertThat(createdChannel.getUuid(), notNullValue());

    Metric channelStatus =
        metricService
            .buildMetricTemplate(
                PlatformMetrics.ALERT_MANAGER_CHANNEL_STATUS,
                MetricService.DEFAULT_METRIC_EXPIRY_SEC)
            .setCustomerUUID(customer.getUuid())
            .setSourceUuid(createdChannel.getUuid())
            .setLabels(MetricLabelsBuilder.create().appendSource(createdChannel).getMetricLabels())
            .setValue(0.0)
            .setLabel(KnownAlertLabels.ERROR_MESSAGE, "Some error");
    metricService.cleanAndSave(Collections.singletonList(channelStatus));

    Result result =
        doRequestWithAuthToken(
            "DELETE",
            "/api/customers/"
                + customer.getUuid()
                + "/alert_channels/"
                + createdChannel.getUuid().toString(),
            authToken);
    assertThat(result.status(), equalTo(OK));

    checkEmptyAnswer("/api/customers/" + customer.getUuid() + "/alert_channels");

    AssertHelper.assertMetricValue(
        metricService,
        MetricKey.builder()
            .customerUuid(customer.getUuid())
            .name(PlatformMetrics.ALERT_MANAGER_CHANNEL_STATUS.getMetricName())
            .targetUuid(createdChannel.getUuid())
            .build(),
        null);
  }

  @Test
  public void testDeleteAlertChannel_ErrorResult() {
    UUID uuid = UUID.randomUUID();
    Result result =
        assertYWSE(
            () ->
                doRequestWithAuthToken(
                    "DELETE",
                    "/api/customers/" + customer.getUuid() + "/alert_channels/" + uuid.toString(),
                    authToken));
    AssertHelper.assertBadRequest(result, "Invalid Alert Channel UUID: " + uuid.toString());
  }

  @Test
  public void testDeleteAlertChannel_LastChannelInDestination_ErrorResult() {
    checkEmptyAnswer("/api/customers/" + customer.getUuid() + "/alert_channels");

    AlertDestination firstDestination = createAlertDestination(false);
    assertThat(firstDestination.getUuid(), notNullValue());

    AlertDestination secondDestination = createAlertDestination(false);
    assertThat(secondDestination.getUuid(), notNullValue());

    // Updating second destination to have the same destinations.
    List<AlertChannel> channels = firstDestination.getChannelsList();
    secondDestination.setChannelsList(channels);
    Result result =
        doRequestWithAuthTokenAndBody(
            "PUT",
            "/api/customers/"
                + customer.getUuid()
                + "/alert_destinations/"
                + secondDestination.getUuid().toString(),
            authToken,
            Json.toJson(secondDestination));
    assertThat(result.status(), is(OK));

    result =
        doRequestWithAuthToken(
            "DELETE",
            "/api/customers/"
                + customer.getUuid()
                + "/alert_channels/"
                + channels.get(0).getUuid().toString(),
            authToken);
    assertThat(result.status(), is(OK));

    result =
        assertYWSE(
            () ->
                doRequestWithAuthToken(
                    "DELETE",
                    "/api/customers/"
                        + customer.getUuid()
                        + "/alert_channels/"
                        + channels.get(1).getUuid().toString(),
                    authToken));

    AssertHelper.assertBadRequest(
        result,
        String.format(
            "Unable to delete alert channel: %s. 2 alert destinations have it as a last channel."
                + " Examples: [%s, %s]",
            channels.get(1).getUuid(), firstDestination.getName(), secondDestination.getName()));
  }

  private ObjectNode getAlertDestinationJson(boolean isDefault) {
    AlertChannel channel1 =
        ModelFactory.createAlertChannel(
            customer.getUuid(),
            getAlertChannelName(),
            AlertUtils.createParamsInstance(ChannelType.Email));
    AlertChannel channel2 =
        ModelFactory.createAlertChannel(
            customer.getUuid(),
            getAlertChannelName(),
            AlertUtils.createParamsInstance(ChannelType.Slack));

    ObjectNode data = Json.newObject();
    data.put("name", getAlertDestinationName())
        .put("defaultDestination", Boolean.valueOf(isDefault))
        .putArray("channels")
        .add(channel1.getUuid().toString())
        .add(channel2.getUuid().toString());
    return data;
  }

  private AlertDestination destinationFromJson(JsonNode json) {
    ObjectMapper mapper = new ObjectMapper();
    List<UUID> channelUUIDs;
    try {
      channelUUIDs = Arrays.asList(mapper.readValue(json.get("channels").traverse(), UUID[].class));
      List<AlertChannel> channels =
          channelUUIDs
              .stream()
              .map(uuid -> alertChannelService.getOrBadRequest(customer.getUuid(), uuid))
              .collect(Collectors.toList());

      AlertDestination destination = new AlertDestination();
      destination.setUuid(UUID.fromString(json.get("uuid").asText()));
      destination.setName(json.get("name").asText());
      destination.setCustomerUUID(UUID.fromString(json.get("customerUUID").asText()));
      destination.setChannelsList(channels);
      destination.setDefaultDestination(json.get("defaultDestination").asBoolean());
      return destination;
    } catch (IOException e) {
      return null;
    }
  }

  private AlertDestination createAlertDestination(boolean isDefault) {
    ObjectNode destinationFormDataJson = getAlertDestinationJson(isDefault);
    Result result =
        doRequestWithAuthTokenAndBody(
            "POST",
            "/api/customers/" + customer.getUuid() + "/alert_destinations",
            authToken,
            destinationFormDataJson);
    assertThat(result.status(), equalTo(OK));
    return destinationFromJson(Json.parse(contentAsString(result)));
  }

  @Test
  public void testCreateAlertDestination_OkResult() {
    checkEmptyAnswer("/api/customers/" + customer.getUuid() + "/alert_destinations");

    AlertDestination createdDestination = createAlertDestination(false);
    assertThat(createdDestination.getUuid(), notNullValue());

    Result result =
        doRequestWithAuthToken(
            "GET", "/api/customers/" + customer.getUuid() + "/alert_destinations", authToken);
    assertThat(result.status(), equalTo(OK));
    JsonNode listedDestinations = Json.parse(contentAsString(result));
    assertThat(listedDestinations.size(), equalTo(1));
    assertThat(destinationFromJson(listedDestinations.get(0)), equalTo(createdDestination));
  }

  @Test
  public void testCreateAlertDestination_ErrorResult() {
    checkEmptyAnswer("/api/customers/" + customer.getUuid() + "/alert_destinations");
    ObjectNode data = Json.newObject();
    String alertChannelUUID = UUID.randomUUID().toString();
    data.put("name", getAlertDestinationName())
        .put("defaultDestination", Boolean.FALSE)
        .putArray("channels")
        .add(alertChannelUUID);
    Result result =
        assertYWSE(
            () ->
                doRequestWithAuthTokenAndBody(
                    "POST",
                    "/api/customers/" + customer.getUuid() + "/alert_destinations",
                    authToken,
                    data));

    AssertHelper.assertBadRequest(result, "Invalid Alert Channel UUID: " + alertChannelUUID);
    checkEmptyAnswer("/api/customers/" + customer.getUuid() + "/alert_destinations");
  }

  @Test
  public void testCreateAlertDestinationWithDefaultChange() {
    checkEmptyAnswer("/api/customers/" + customer.getUuid() + "/alert_destinations");

    AlertDestination firstDestination = createAlertDestination(true);
    assertThat(firstDestination.getUuid(), notNullValue());
    assertThat(
        alertDestinationService.getDefaultDestination(customer.uuid), equalTo(firstDestination));

    AlertDestination secondDestination = createAlertDestination(true);
    assertThat(secondDestination.getUuid(), notNullValue());
    assertThat(
        alertDestinationService.getDefaultDestination(customer.uuid), equalTo(secondDestination));
  }

  @Test
  public void testGetAlertDestination_OkResult() {
    AlertDestination createdDestination = createAlertDestination(false);
    assertThat(createdDestination.getUuid(), notNullValue());

    Result result =
        doRequestWithAuthToken(
            "GET",
            "/api/customers/"
                + customer.getUuid()
                + "/alert_destinations/"
                + createdDestination.getUuid(),
            authToken);
    assertThat(result.status(), equalTo(OK));

    AlertDestination destination = destinationFromJson(Json.parse(contentAsString(result)));
    assertThat(destination, notNullValue());
    assertThat(destination, equalTo(createdDestination));
  }

  @Test
  public void testGetAlertDestination_ErrorResult() {
    UUID uuid = UUID.randomUUID();
    Result result =
        assertYWSE(
            () ->
                doRequestWithAuthToken(
                    "GET",
                    "/api/customers/"
                        + customer.getUuid()
                        + "/alert_destinations/"
                        + uuid.toString(),
                    authToken));
    AssertHelper.assertBadRequest(result, "Invalid Alert Destination UUID: " + uuid.toString());
  }

  @Test
  public void testUpdateAlertDestination_AnotherDefaultDestination() {
    checkEmptyAnswer("/api/customers/" + customer.getUuid() + "/alert_destinations");

    AlertDestination firstDestination = createAlertDestination(true);
    assertThat(firstDestination.getUuid(), notNullValue());
    assertThat(
        alertDestinationService.getDefaultDestination(customer.uuid), equalTo(firstDestination));

    AlertDestination secondDestination = createAlertDestination(false);
    assertThat(secondDestination.getUuid(), notNullValue());
    // To be sure the default destination hasn't been changed.
    assertThat(
        alertDestinationService.getDefaultDestination(customer.uuid), equalTo(firstDestination));

    secondDestination.setDefaultDestination(true);

    Result result =
        doRequestWithAuthTokenAndBody(
            "PUT",
            "/api/customers/"
                + customer.getUuid()
                + "/alert_destinations/"
                + secondDestination.getUuid().toString(),
            authToken,
            Json.toJson(secondDestination));
    assertThat(result.status(), is(OK));
    AlertDestination receivedDestination = destinationFromJson(Json.parse(contentAsString(result)));

    assertThat(receivedDestination.isDefaultDestination(), is(true));
    assertThat(
        alertDestinationService.getDefaultDestination(customer.uuid), equalTo(secondDestination));
  }

  @Test
  public void testUpdateAlertDestination_ChangeDefaultFlag_ErrorResult() {
    checkEmptyAnswer("/api/customers/" + customer.getUuid() + "/alert_destinations");

    AlertDestination destination = createAlertDestination(true);
    assertThat(destination.getUuid(), notNullValue());
    assertThat(alertDestinationService.getDefaultDestination(customer.uuid), equalTo(destination));

    destination.setDefaultDestination(false);
    Result result =
        assertYWSE(
            () ->
                doRequestWithAuthTokenAndBody(
                    "PUT",
                    "/api/customers/"
                        + customer.getUuid()
                        + "/alert_destinations/"
                        + destination.getUuid().toString(),
                    authToken,
                    Json.toJson(destination)));
    AssertHelper.assertBadRequest(
        result,
        "Can't set the alert destination as non-default. Make another"
            + " destination as default at first.");
    destination.setDefaultDestination(true);
    assertThat(alertDestinationService.getDefaultDestination(customer.uuid), equalTo(destination));
  }

  @Test
  public void testDeleteAlertDestination_OkResult() {
    checkEmptyAnswer("/api/customers/" + customer.getUuid() + "/alert_destinations");

    AlertDestination createdDestination = createAlertDestination(false);
    assertThat(createdDestination.getUuid(), notNullValue());

    Result result =
        doRequestWithAuthToken(
            "DELETE",
            "/api/customers/"
                + customer.getUuid()
                + "/alert_destinations/"
                + createdDestination.getUuid().toString(),
            authToken);
    assertThat(result.status(), equalTo(OK));

    checkEmptyAnswer("/api/customers/" + customer.getUuid() + "/alert_destinations");
  }

  @Test
  public void testDeleteAlertDestination_InvalidUUID_ErrorResult() {
    UUID uuid = UUID.randomUUID();
    Result result =
        assertYWSE(
            () ->
                doRequestWithAuthToken(
                    "DELETE",
                    "/api/customers/"
                        + customer.getUuid()
                        + "/alert_destinations/"
                        + uuid.toString(),
                    authToken));
    AssertHelper.assertBadRequest(result, "Invalid Alert Destination UUID: " + uuid.toString());
  }

  @Test
  public void testDeleteAlertDestination_DefaultDestination_ErrorResult() {
    AlertDestination createdDestination = createAlertDestination(true);
    String destinationUUID = createdDestination.getUuid().toString();

    Result result =
        assertYWSE(
            () ->
                doRequestWithAuthToken(
                    "DELETE",
                    "/api/customers/"
                        + customer.getUuid()
                        + "/alert_destinations/"
                        + destinationUUID,
                    authToken));
    AssertHelper.assertBadRequest(
        result,
        "Unable to delete default alert destination "
            + destinationUUID
            + ", make another destination default at first.");
  }

  @Test
  public void testListAlertDestinations_OkResult() {
    checkEmptyAnswer("/api/customers/" + customer.getUuid() + "/alert_destinations");

    AlertDestination createdDestination1 = createAlertDestination(false);
    AlertDestination createdDestination2 = createAlertDestination(false);

    Result result =
        doRequestWithAuthToken(
            "GET", "/api/customers/" + customer.getUuid() + "/alert_destinations", authToken);
    assertThat(result.status(), equalTo(OK));
    JsonNode listedDestinations = Json.parse(contentAsString(result));
    assertThat(listedDestinations.size(), equalTo(2));

    AlertDestination listedDestination1 = destinationFromJson(listedDestinations.get(0));
    AlertDestination listedDestination2 = destinationFromJson(listedDestinations.get(1));
    assertThat(listedDestination1, not(listedDestination2));
    assertThat(
        listedDestination1, anyOf(equalTo(createdDestination1), equalTo(createdDestination2)));
    assertThat(
        listedDestination2, anyOf(equalTo(createdDestination1), equalTo(createdDestination2)));
  }

  private String getAlertChannelName() {
    return "Test AlertChannel " + (alertChannelIndex++);
  }

  private String getAlertDestinationName() {
    return "Test AlertDestination " + (alertDestinationIndex++);
  }

  @Test
  public void testGetAlert() {
    Alert initial = ModelFactory.createAlert(customer, alertDefinition);

    Result result =
        doRequestWithAuthToken(
            "GET", "/api/customers/" + customer.uuid + "/alerts/" + initial.getUuid(), authToken);
    assertThat(result.status(), equalTo(OK));
    JsonNode alertsJson = Json.parse(contentAsString(result));
    Alert alert = Json.fromJson(alertsJson, Alert.class);

    assertThat(alert, equalTo(initial));
  }

  @Test
  public void testListAlerts() {
    checkEmptyAnswer("/api/customers/" + customer.getUuid() + "/alerts");
    Alert initial = ModelFactory.createAlert(customer, alertDefinition);

    Result result =
        doRequestWithAuthToken(
            "GET", "/api/customers/" + customer.getUuid() + "/alerts", authToken);
    assertThat(result.status(), equalTo(OK));
    JsonNode alertsJson = Json.parse(contentAsString(result));
    List<Alert> alerts = Arrays.asList(Json.fromJson(alertsJson, Alert[].class));

    assertThat(alerts, hasSize(1));
    assertThat(alerts.get(0), equalTo(initial));
  }

  @Test
  public void testListActiveAlerts() {
    Alert initial = ModelFactory.createAlert(customer, alertDefinition);
    Alert initial2 = ModelFactory.createAlert(customer, alertDefinition);

    alertService.markResolved(AlertFilter.builder().uuid(initial2.getUuid()).build());

    Result result =
        doRequestWithAuthToken(
            "GET", "/api/customers/" + customer.getUuid() + "/alerts/active", authToken);
    assertThat(result.status(), equalTo(OK));
    JsonNode alertsJson = Json.parse(contentAsString(result));
    List<Alert> alerts = Arrays.asList(Json.fromJson(alertsJson, Alert[].class));

    assertThat(alerts, hasSize(1));
    assertThat(alerts.get(0), equalTo(initial));
  }

  @Test
  public void testPageAlerts() {
    ModelFactory.createAlert(customer, alertDefinition);
    Alert initial2 = ModelFactory.createAlert(customer, alertDefinition);
    Alert initial3 = ModelFactory.createAlert(customer, alertDefinition);

    initial2.setCreateTime(Date.from(initial2.getCreateTime().toInstant().minusSeconds(5))).save();
    initial3.setCreateTime(Date.from(initial3.getCreateTime().toInstant().minusSeconds(10))).save();

    AlertPagedApiQuery query = new AlertPagedApiQuery();
    query.setSortBy(Alert.SortBy.createTime);
    query.setDirection(PagedQuery.SortDirection.DESC);
    query.setFilter(new AlertApiFilter());
    query.setLimit(2);
    query.setOffset(1);
    query.setNeedTotalCount(true);

    Result result =
        doRequestWithAuthTokenAndBody(
            "POST",
            "/api/customers/" + customer.getUuid() + "/alerts/page",
            authToken,
            Json.toJson(query));
    assertThat(result.status(), equalTo(OK));
    JsonNode alertsJson = Json.parse(contentAsString(result));
    AlertPagedResponse alerts = Json.fromJson(alertsJson, AlertPagedResponse.class);

    assertThat(alerts.isHasNext(), is(false));
    assertThat(alerts.isHasPrev(), is(true));
    assertThat(alerts.getTotalCount(), equalTo(3));
    assertThat(alerts.getEntities(), hasSize(2));
    assertThat(alerts.getEntities(), contains(initial2, initial3));
  }

  @Test
  public void testAcknowledgeAlert() {
    Alert initial = ModelFactory.createAlert(customer, alertDefinition);

    Result result =
        doRequestWithAuthToken(
            "POST",
            "/api/customers/" + customer.uuid + "/alerts/" + initial.getUuid() + "/acknowledge",
            authToken);
    assertThat(result.status(), equalTo(OK));

    JsonNode alertsJson = Json.parse(contentAsString(result));
    Alert acknowledged = Json.fromJson(alertsJson, Alert.class);

    initial.setState(Alert.State.ACKNOWLEDGED);
    initial.setAcknowledgedTime(acknowledged.getAcknowledgedTime());
    initial.setNotifiedState(Alert.State.ACKNOWLEDGED);
    initial.setNextNotificationTime(null);
    assertThat(acknowledged, equalTo(initial));
  }

  @Test
  public void testAcknowledgeAlerts() {
    Alert initial = ModelFactory.createAlert(customer, alertDefinition);
    ModelFactory.createAlert(customer, alertDefinition);
    ModelFactory.createAlert(customer, alertDefinition);

    AlertApiFilter apiFilter = new AlertApiFilter();
    apiFilter.setUuids(ImmutableSet.of(initial.getUuid()));

    Result result =
        doRequestWithAuthTokenAndBody(
            "POST",
            "/api/customers/" + customer.getUuid() + "/alerts/acknowledge",
            authToken,
            Json.toJson(apiFilter));
    assertThat(result.status(), equalTo(OK));

    Alert acknowledged = alertService.get(initial.getUuid());
    initial.setState(Alert.State.ACKNOWLEDGED);
    initial.setAcknowledgedTime(acknowledged.getAcknowledgedTime());
    initial.setNotifiedState(Alert.State.ACKNOWLEDGED);
    initial.setNextNotificationTime(null);
    assertThat(acknowledged, equalTo(initial));
  }

  @Test
  public void testListTemplates() {
    AlertTemplateApiFilter apiFilter = new AlertTemplateApiFilter();
    apiFilter.setName(AlertTemplate.MEMORY_CONSUMPTION.getName());

    Result result =
        doRequestWithAuthTokenAndBody(
            "POST",
            "/api/customers/" + customer.getUuid() + "/alert_templates",
            authToken,
            Json.toJson(apiFilter));
    assertThat(result.status(), equalTo(OK));
    JsonNode templatesJson = Json.parse(contentAsString(result));
    List<AlertConfiguration> templates =
        Arrays.asList(Json.fromJson(templatesJson, AlertConfiguration[].class));

    assertThat(templates, hasSize(1));
    AlertConfiguration template = templates.get(0);
    assertThat(template.getName(), equalTo(AlertTemplate.MEMORY_CONSUMPTION.getName()));
    assertThat(template.getTemplate(), equalTo(AlertTemplate.MEMORY_CONSUMPTION));
    assertThat(
        template.getDescription(), equalTo(AlertTemplate.MEMORY_CONSUMPTION.getDescription()));
    assertThat(template.getTargetType(), equalTo(AlertTemplate.MEMORY_CONSUMPTION.getTargetType()));
    assertThat(template.getTarget(), equalTo(new AlertConfigurationTarget().setAll(true)));
    assertThat(
        template.getThresholdUnit(),
        equalTo(AlertTemplate.MEMORY_CONSUMPTION.getDefaultThresholdUnit()));
    assertThat(
        template.getThresholds(),
        equalTo(
            ImmutableMap.of(
                AlertConfiguration.Severity.SEVERE,
                new AlertConfigurationThreshold()
                    .setCondition(AlertConfigurationThreshold.Condition.GREATER_THAN)
                    .setThreshold(90D))));
    assertThat(
        template.getDurationSec(),
        equalTo(AlertTemplate.MEMORY_CONSUMPTION.getDefaultDurationSec()));
  }

  @Test
  public void testGetConfigurationSuccess() {
    Result result =
        doRequestWithAuthToken(
            "GET",
            "/api/customers/"
                + customer.getUuid()
                + "/alert_configurations/"
                + alertConfiguration.getUuid(),
            authToken);
    assertThat(result.status(), equalTo(OK));
    JsonNode configurationJson = Json.parse(contentAsString(result));
    AlertConfiguration configuration = Json.fromJson(configurationJson, AlertConfiguration.class);

    assertThat(configuration, equalTo(alertConfiguration));
  }

  @Test
  public void testGetConfigurationFailure() {
    UUID uuid = UUID.randomUUID();
    Result result =
        assertYWSE(
            () ->
                doRequestWithAuthToken(
                    "GET",
                    "/api/customers/" + customer.getUuid() + "/alert_configurations/" + uuid,
                    authToken));
    AssertHelper.assertBadRequest(result, "Invalid Alert Configuration UUID: " + uuid);
  }

  @Test
  public void testPageConfigurations() {
    AlertConfiguration configuration2 = ModelFactory.createAlertConfiguration(customer, universe);
    AlertConfiguration configuration3 = ModelFactory.createAlertConfiguration(customer, universe);

    configuration2
        .setCreateTime(Date.from(configuration2.getCreateTime().toInstant().minusSeconds(5)))
        .save();
    configuration3
        .setCreateTime(Date.from(configuration3.getCreateTime().toInstant().minusSeconds(10)))
        .save();

    AlertConfigurationPagedApiQuery query = new AlertConfigurationPagedApiQuery();
    query.setSortBy(SortBy.createTime);
    query.setDirection(PagedQuery.SortDirection.DESC);
    query.setFilter(new AlertConfigurationApiFilter());
    query.setLimit(2);
    query.setOffset(1);
    query.setNeedTotalCount(true);

    Result result =
        doRequestWithAuthTokenAndBody(
            "POST",
            "/api/customers/" + customer.getUuid() + "/alert_configurations/page",
            authToken,
            Json.toJson(query));
    assertThat(result.status(), equalTo(OK));
    JsonNode configurationsJson = Json.parse(contentAsString(result));
    AlertConfigurationPagedResponse configurations =
        Json.fromJson(configurationsJson, AlertConfigurationPagedResponse.class);

    assertThat(configurations.isHasNext(), is(false));
    assertThat(configurations.isHasPrev(), is(true));
    assertThat(configurations.getTotalCount(), equalTo(3));
    assertThat(configurations.getEntities(), hasSize(2));
    assertThat(configurations.getEntities(), contains(configuration2, configuration3));
  }

  @Test
  public void testListConfigurations() {
    AlertConfiguration configuration2 = ModelFactory.createAlertConfiguration(customer, universe);
    AlertConfiguration configuration3 = ModelFactory.createAlertConfiguration(customer, universe);

    configuration3.setActive(false);
    alertConfigurationService.save(configuration3);

    AlertConfigurationApiFilter filter = new AlertConfigurationApiFilter();
    filter.setActive(true);

    Result result =
        doRequestWithAuthTokenAndBody(
            "POST",
            "/api/customers/" + customer.getUuid() + "/alert_configurations/list",
            authToken,
            Json.toJson(filter));
    assertThat(result.status(), equalTo(OK));
    JsonNode configurationsJson = Json.parse(contentAsString(result));
    List<AlertConfiguration> configurations =
        Arrays.asList(Json.fromJson(configurationsJson, AlertConfiguration[].class));

    assertThat(configurations, hasSize(2));
    assertThat(configurations, containsInAnyOrder(alertConfiguration, configuration2));
  }

  @Test
  public void testCreateConfiguration() {
    AlertDestination destination = createAlertDestination(false);
    alertConfiguration.setUuid(null);
    alertConfiguration.setCreateTime(null);
    alertConfiguration.setDestinationUUID(destination.getUuid());

    Result result =
        doRequestWithAuthTokenAndBody(
            "POST",
            "/api/customers/" + customer.getUuid() + "/alert_configurations",
            authToken,
            Json.toJson(alertConfiguration));
    assertThat(result.status(), equalTo(OK));
    JsonNode configurationJson = Json.parse(contentAsString(result));
    AlertConfiguration configuration = Json.fromJson(configurationJson, AlertConfiguration.class);

    assertThat(configuration.getUuid(), notNullValue());
    assertThat(configuration.getCreateTime(), notNullValue());
    assertThat(configuration.getCustomerUUID(), equalTo(customer.getUuid()));
    assertThat(configuration.getName(), equalTo("alertConfiguration"));
    assertThat(configuration.getTemplate(), equalTo(AlertTemplate.MEMORY_CONSUMPTION));
    assertThat(configuration.getDescription(), equalTo("alertConfiguration description"));
    assertThat(configuration.getTargetType(), equalTo(AlertConfiguration.TargetType.UNIVERSE));
    assertThat(
        configuration.getTarget(),
        equalTo(
            new AlertConfigurationTarget().setUuids(ImmutableSet.of(universe.getUniverseUUID()))));
    assertThat(configuration.getThresholdUnit(), equalTo(Unit.PERCENT));
    assertThat(
        configuration.getThresholds(),
        equalTo(
            ImmutableMap.of(
                AlertConfiguration.Severity.SEVERE,
                new AlertConfigurationThreshold()
                    .setCondition(AlertConfigurationThreshold.Condition.GREATER_THAN)
                    .setThreshold(1D))));
    assertThat(configuration.getDurationSec(), equalTo(15));
    assertThat(configuration.getDestinationUUID(), equalTo(destination.getUuid()));
  }

  @Test
  public void testCreateConfigurationFailure() {
    alertConfiguration.setUuid(null);
    alertConfiguration.setName(null);

    Result result =
        assertYWSE(
            () ->
                doRequestWithAuthTokenAndBody(
                    "POST",
                    "/api/customers/" + customer.getUuid() + "/alert_configurations",
                    authToken,
                    Json.toJson(alertConfiguration)));
    assertBadRequest(result, "Name field is mandatory");
  }

  @Test
  public void testUpdateConfiguration() {
    AlertDestination destination = createAlertDestination(false);
    alertConfiguration.setDestinationUUID(destination.getUuid());

    Result result =
        doRequestWithAuthTokenAndBody(
            "PUT",
            "/api/customers/"
                + customer.getUuid()
                + "/alert_configurations/"
                + alertConfiguration.getUuid(),
            authToken,
            Json.toJson(alertConfiguration));
    assertThat(result.status(), equalTo(OK));
    JsonNode configurationJson = Json.parse(contentAsString(result));
    AlertConfiguration configuration = Json.fromJson(configurationJson, AlertConfiguration.class);

    assertThat(configuration.getDestinationUUID(), equalTo(destination.getUuid()));
  }

  @Test
  public void testUpdateConfigurationFailure() {
    alertConfiguration.setTargetType(null);

    Result result =
        assertYWSE(
            () ->
                doRequestWithAuthTokenAndBody(
                    "PUT",
                    "/api/customers/"
                        + customer.getUuid()
                        + "/alert_configurations/"
                        + alertConfiguration.getUuid(),
                    authToken,
                    Json.toJson(alertConfiguration)));
    assertBadRequest(result, "Target type field is mandatory");
  }

  @Test
  public void testDeleteConfiguration() {
    Result result =
        doRequestWithAuthToken(
            "DELETE",
            "/api/customers/"
                + customer.getUuid()
                + "/alert_configurations/"
                + alertConfiguration.getUuid(),
            authToken);
    assertThat(result.status(), equalTo(OK));
  }
}
