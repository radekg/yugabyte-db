// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.common.alerts;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import akka.actor.ActorSystem;
import akka.actor.Scheduler;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.typesafe.config.Config;
import com.yugabyte.yw.common.AlertManager;
import com.yugabyte.yw.common.AssertHelper;
import com.yugabyte.yw.common.EmailHelper;
import com.yugabyte.yw.common.FakeDBApplication;
import com.yugabyte.yw.common.ModelFactory;
import com.yugabyte.yw.common.alerts.impl.AlertChannelEmail;
import com.yugabyte.yw.common.config.RuntimeConfigFactory;
import com.yugabyte.yw.common.metrics.MetricService;
import com.yugabyte.yw.metrics.MetricQueryHelper;
import com.yugabyte.yw.metrics.data.AlertData;
import com.yugabyte.yw.metrics.data.AlertState;
import com.yugabyte.yw.models.Alert;
import com.yugabyte.yw.models.AlertChannel.ChannelType;
import com.yugabyte.yw.models.AlertDefinition;
import com.yugabyte.yw.models.AlertConfiguration;
import com.yugabyte.yw.models.Customer;
import com.yugabyte.yw.models.MetricKey;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.filters.AlertFilter;
import com.yugabyte.yw.models.helpers.KnownAlertLabels;
import com.yugabyte.yw.models.helpers.PlatformMetrics;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import junitparams.JUnitParamsRunner;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import scala.concurrent.ExecutionContext;

@RunWith(JUnitParamsRunner.class)
public class QueryAlertsTest extends FakeDBApplication {

  @Rule public MockitoRule rule = MockitoJUnit.rule();

  @Mock private ExecutionContext executionContext;

  @Mock private ActorSystem actorSystem;

  @Mock private MetricQueryHelper queryHelper;

  @Mock private RuntimeConfigFactory configFactory;

  @Mock private EmailHelper emailHelper;

  @Mock private AlertChannelManager channelsManager;

  @Mock private AlertChannelEmail emailReceiver;

  private QueryAlerts queryAlerts;

  private Customer customer;

  private Universe universe;

  @Mock private Config universeConfig;

  private AlertDefinition definition;

  private MetricService metricService;
  private AlertConfigurationService alertConfigurationService;
  private AlertDefinitionService alertDefinitionService;
  private AlertService alertService;
  private AlertChannelService alertChannelService;
  private AlertDestinationService alertDestinationService;
  private AlertManager alertManager;

  @Before
  public void setUp() {

    customer = ModelFactory.testCustomer();

    SmtpData smtpData = new SmtpData();
    when(channelsManager.get(ChannelType.Email.name())).thenReturn(emailReceiver);
    when(emailHelper.getDestinations(customer.uuid))
        .thenReturn(Collections.singletonList("to@to.com"));
    when(emailHelper.getSmtpData(customer.uuid)).thenReturn(smtpData);

    metricService = new MetricService();
    alertService = new AlertService();
    alertDefinitionService = new AlertDefinitionService(alertService);
    alertConfigurationService =
        new AlertConfigurationService(alertDefinitionService, configFactory);
    alertChannelService = new AlertChannelService();
    alertDestinationService =
        new AlertDestinationService(alertChannelService, alertConfigurationService);
    alertManager =
        new AlertManager(
            emailHelper,
            alertService,
            alertConfigurationService,
            alertChannelService,
            alertDestinationService,
            channelsManager,
            metricService);
    when(actorSystem.scheduler()).thenReturn(mock(Scheduler.class));
    queryAlerts =
        new QueryAlerts(
            executionContext,
            actorSystem,
            alertService,
            queryHelper,
            metricService,
            alertDefinitionService,
            alertConfigurationService,
            alertManager);

    universe = ModelFactory.createUniverse(customer.getCustomerId());
    when(configFactory.forUniverse(universe)).thenReturn(universeConfig);

    definition = ModelFactory.createAlertDefinition(customer, universe);
  }

  @Test
  public void testQueryAlertsNewAlert() {
    ZonedDateTime raisedTime = ZonedDateTime.parse("2018-07-04T20:27:12.60602144+02:00");
    when(queryHelper.queryAlerts()).thenReturn(ImmutableList.of(createAlertData(raisedTime)));

    queryAlerts.scheduleRunner();

    AlertFilter alertFilter =
        AlertFilter.builder()
            .customerUuid(customer.getUuid())
            .definitionUuid(definition.getUuid())
            .build();
    List<Alert> alerts = alertService.list(alertFilter);

    Alert expectedAlert = createAlert(raisedTime).setUuid(alerts.get(0).getUuid());
    copyNotificationFields(expectedAlert, alerts.get(0));
    assertThat(alerts, contains(expectedAlert));

    AssertHelper.assertMetricValue(
        metricService,
        MetricKey.builder().name(PlatformMetrics.ALERT_QUERY_STATUS.getMetricName()).build(),
        1.0);
    AssertHelper.assertMetricValue(
        metricService,
        MetricKey.builder().name(PlatformMetrics.ALERT_QUERY_TOTAL_ALERTS.getMetricName()).build(),
        1.0);
    AssertHelper.assertMetricValue(
        metricService,
        MetricKey.builder().name(PlatformMetrics.ALERT_QUERY_NEW_ALERTS.getMetricName()).build(),
        1.0);
  }

  @Test
  public void testQueryAlertsMultipleSeverities() {
    ZonedDateTime raisedTime = ZonedDateTime.parse("2018-07-04T20:27:12.60602144+02:00");
    when(queryHelper.queryAlerts())
        .thenReturn(
            ImmutableList.of(
                createAlertData(raisedTime),
                createAlertData(raisedTime, AlertConfiguration.Severity.WARNING)));

    queryAlerts.scheduleRunner();

    AlertFilter alertFilter =
        AlertFilter.builder()
            .customerUuid(customer.getUuid())
            .definitionUuid(definition.getUuid())
            .build();
    List<Alert> alerts = alertService.list(alertFilter);

    Alert expectedAlert = createAlert(raisedTime).setUuid(alerts.get(0).getUuid());
    copyNotificationFields(expectedAlert, alerts.get(0));
    assertThat(alerts, contains(expectedAlert));

    AssertHelper.assertMetricValue(
        metricService,
        MetricKey.builder().name(PlatformMetrics.ALERT_QUERY_TOTAL_ALERTS.getMetricName()).build(),
        2.0);
    AssertHelper.assertMetricValue(
        metricService,
        MetricKey.builder()
            .name(PlatformMetrics.ALERT_QUERY_FILTERED_ALERTS.getMetricName())
            .build(),
        1.0);
    AssertHelper.assertMetricValue(
        metricService,
        MetricKey.builder().name(PlatformMetrics.ALERT_QUERY_NEW_ALERTS.getMetricName()).build(),
        1.0);
  }

  @Test
  public void testQueryAlertsNewAlertWithDefaults() {
    ZonedDateTime raisedTime = ZonedDateTime.parse("2018-07-04T20:27:12.60602144+02:00");
    when(queryHelper.queryAlerts()).thenReturn(ImmutableList.of(createAlertData(raisedTime)));

    queryAlerts.scheduleRunner();

    AlertFilter alertFilter =
        AlertFilter.builder()
            .customerUuid(customer.getUuid())
            .definitionUuid(definition.getUuid())
            .build();
    List<Alert> alerts = alertService.list(alertFilter);

    assertThat(alerts, hasSize(1));

    Alert expectedAlert = createAlert(raisedTime).setUuid(alerts.get(0).getUuid());
    copyNotificationFields(expectedAlert, alerts.get(0));
    assertThat(alerts, contains(expectedAlert));
  }

  @Test
  public void testQueryAlertsExistingAlert() {
    ZonedDateTime raisedTime = ZonedDateTime.parse("2018-07-04T20:27:12.60602144+02:00");
    when(queryHelper.queryAlerts()).thenReturn(ImmutableList.of(createAlertData(raisedTime)));

    Alert alert = createAlert(raisedTime);
    alertService.save(alert);

    queryAlerts.scheduleRunner();

    AlertFilter alertFilter =
        AlertFilter.builder()
            .customerUuid(customer.getUuid())
            .definitionUuid(definition.getUuid())
            .build();
    List<Alert> alerts = alertService.list(alertFilter);

    Alert expectedAlert =
        createAlert(raisedTime).setUuid(alert.getUuid()).setState(Alert.State.ACTIVE);
    copyNotificationFields(expectedAlert, alerts.get(0));
    assertThat(alerts, contains(expectedAlert));

    AssertHelper.assertMetricValue(
        metricService,
        MetricKey.builder().name(PlatformMetrics.ALERT_QUERY_TOTAL_ALERTS.getMetricName()).build(),
        1.0);
    AssertHelper.assertMetricValue(
        metricService,
        MetricKey.builder()
            .name(PlatformMetrics.ALERT_QUERY_UPDATED_ALERTS.getMetricName())
            .build(),
        1.0);
    AssertHelper.assertMetricValue(
        metricService,
        MetricKey.builder().name(PlatformMetrics.ALERT_QUERY_NEW_ALERTS.getMetricName()).build(),
        0.0);
  }

  private void copyNotificationFields(Alert expectedAlert, Alert alert) {
    expectedAlert
        .setNotificationAttemptTime(alert.getNotificationAttemptTime())
        .setNextNotificationTime(alert.getNextNotificationTime())
        .setNotificationsFailed(alert.getNotificationsFailed());
  }

  @Test
  public void testQueryAlertsExistingResolvedAlert() {
    ZonedDateTime raisedTime = ZonedDateTime.parse("2018-07-04T20:27:12.60602144+02:00");
    when(queryHelper.queryAlerts()).thenReturn(ImmutableList.of(createAlertData(raisedTime)));

    Alert alert = createAlert(raisedTime);
    alert.setState(Alert.State.RESOLVED);
    alertService.save(alert);

    queryAlerts.scheduleRunner();

    AlertFilter alertFilter =
        AlertFilter.builder()
            .customerUuid(customer.getUuid())
            .definitionUuid(definition.getUuid())
            .build();
    List<Alert> alerts = alertService.list(alertFilter);

    assertThat(alerts, hasSize(2));

    AssertHelper.assertMetricValue(
        metricService,
        MetricKey.builder().name(PlatformMetrics.ALERT_QUERY_TOTAL_ALERTS.getMetricName()).build(),
        1.0);
    AssertHelper.assertMetricValue(
        metricService,
        MetricKey.builder().name(PlatformMetrics.ALERT_QUERY_NEW_ALERTS.getMetricName()).build(),
        1.0);
  }

  @Test
  public void testQueryAlertsResolveExistingAlert() {
    ZonedDateTime raisedTime = ZonedDateTime.parse("2018-07-04T20:27:12.60602144+02:00");
    when(queryHelper.queryAlerts()).thenReturn(Collections.emptyList());

    Alert alert = createAlert(raisedTime);
    alertService.save(alert);

    queryAlerts.scheduleRunner();

    AlertFilter alertFilter =
        AlertFilter.builder()
            .customerUuid(customer.getUuid())
            .definitionUuid(definition.getUuid())
            .build();
    List<Alert> alerts = alertService.list(alertFilter);

    Alert expectedAlert =
        createAlert(raisedTime)
            .setUuid(alert.getUuid())
            .setState(Alert.State.RESOLVED)
            .setResolvedTime(alerts.get(0).getResolvedTime());
    copyNotificationFields(expectedAlert, alerts.get(0));
    assertThat(alerts, contains(expectedAlert));

    AssertHelper.assertMetricValue(
        metricService,
        MetricKey.builder().name(PlatformMetrics.ALERT_QUERY_TOTAL_ALERTS.getMetricName()).build(),
        0.0);
    AssertHelper.assertMetricValue(
        metricService,
        MetricKey.builder()
            .name(PlatformMetrics.ALERT_QUERY_RESOLVED_ALERTS.getMetricName())
            .build(),
        1.0);
  }

  private Alert createAlert(ZonedDateTime raisedTime) {
    return new Alert()
        .setCreateTime(Date.from(raisedTime.toInstant()))
        .setCustomerUUID(customer.getUuid())
        .setDefinitionUuid(definition.getUuid())
        .setConfigurationUuid(definition.getConfigurationUUID())
        .setConfigurationType(AlertConfiguration.TargetType.UNIVERSE)
        .setSeverity(AlertConfiguration.Severity.SEVERE)
        .setName("Clock Skew Alert")
        .setMessage("Clock Skew Alert for universe Test is firing")
        .setState(Alert.State.ACTIVE)
        .setLabel(KnownAlertLabels.CUSTOMER_UUID, customer.getUuid().toString())
        .setLabel(KnownAlertLabels.DEFINITION_UUID, definition.getUuid().toString())
        .setLabel(KnownAlertLabels.CONFIGURATION_UUID, definition.getConfigurationUUID().toString())
        .setLabel(
            KnownAlertLabels.CONFIGURATION_TYPE, AlertConfiguration.TargetType.UNIVERSE.name())
        .setLabel(KnownAlertLabels.DEFINITION_NAME, "Clock Skew Alert")
        .setLabel(KnownAlertLabels.SEVERITY, AlertConfiguration.Severity.SEVERE.name());
  }

  private AlertData createAlertData(ZonedDateTime raisedTime) {
    return createAlertData(raisedTime, AlertConfiguration.Severity.SEVERE);
  }

  private AlertData createAlertData(
      ZonedDateTime raisedTime, AlertConfiguration.Severity severity) {
    Map<String, String> labels = new HashMap<>();
    labels.put("customer_uuid", customer.getUuid().toString());
    labels.put("definition_uuid", definition.getUuid().toString());
    labels.put("configuration_uuid", definition.getConfigurationUUID().toString());
    labels.put("configuration_type", "UNIVERSE");
    labels.put("definition_name", "Clock Skew Alert");
    labels.put("severity", severity.name());
    return AlertData.builder()
        .activeAt(raisedTime.withZoneSameInstant(ZoneId.of("UTC")))
        .annotations(ImmutableMap.of("summary", "Clock Skew Alert for universe Test is firing"))
        .labels(labels)
        .state(AlertState.firing)
        .value(1)
        .build();
  }
}
