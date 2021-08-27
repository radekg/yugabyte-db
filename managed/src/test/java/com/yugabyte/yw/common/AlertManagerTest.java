// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.common;

import static com.yugabyte.yw.common.metrics.MetricService.buildMetricTemplate;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.yugabyte.yw.common.alerts.AlertConfigurationService;
import com.yugabyte.yw.common.alerts.AlertDefinitionService;
import com.yugabyte.yw.common.alerts.AlertNotificationReport;
import com.yugabyte.yw.common.alerts.AlertChannelEmailParams;
import com.yugabyte.yw.common.alerts.AlertChannelManager;
import com.yugabyte.yw.common.alerts.AlertChannelService;
import com.yugabyte.yw.common.alerts.AlertDestinationService;
import com.yugabyte.yw.common.alerts.AlertService;
import com.yugabyte.yw.common.alerts.AlertUtils;
import com.yugabyte.yw.common.metrics.MetricService;
import com.yugabyte.yw.common.alerts.PlatformNotificationException;
import com.yugabyte.yw.common.alerts.impl.AlertChannelEmail;
import com.yugabyte.yw.common.config.impl.SettableRuntimeConfigFactory;
import com.yugabyte.yw.models.Alert;
import com.yugabyte.yw.models.Alert.State;
import com.yugabyte.yw.models.AlertChannel.ChannelType;
import com.yugabyte.yw.models.AlertConfiguration;
import com.yugabyte.yw.models.AlertDefinition;
import com.yugabyte.yw.models.AlertChannel;
import com.yugabyte.yw.models.AlertDestination;
import com.yugabyte.yw.models.Customer;
import com.yugabyte.yw.models.Metric;
import com.yugabyte.yw.models.MetricKey;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.helpers.KnownAlertLabels;
import com.yugabyte.yw.models.helpers.PlatformMetrics;
import java.util.Collections;
import java.util.Date;
import javax.mail.MessagingException;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import junitparams.converters.Nullable;
import junitparams.naming.TestCaseName;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnitParamsRunner.class)
public class AlertManagerTest extends FakeDBApplication {

  @Rule public MockitoRule rule = MockitoJUnit.rule();

  private static final String DEFAULT_EMAIL = "to@to.com";

  private static final String ALERT_DESTINATION_NAME = "Test AlertDestination";

  private Customer defaultCustomer;

  @Mock private AlertChannelEmail emailChannel;

  @Mock private AlertChannelManager channelsManager;

  @Mock private EmailHelper emailHelper;

  private MetricService metricService;

  private AlertService alertService;

  private AlertDefinitionService alertDefinitionService;

  private AlertConfigurationService alertConfigurationService;

  private AlertManager am;

  private AlertConfiguration configuration;

  private AlertDefinition definition;

  private Universe universe;

  private AlertNotificationReport report = new AlertNotificationReport();

  private AlertDestination defaultDestination;
  private AlertChannel defaultChannel;

  private AlertChannelService alertChannelService;
  private AlertDestinationService alertDestinationService;

  @Before
  public void setUp() {
    defaultCustomer = ModelFactory.testCustomer();
    when(channelsManager.get(ChannelType.Email.name())).thenReturn(emailChannel);

    universe = ModelFactory.createUniverse();
    configuration = ModelFactory.createAlertConfiguration(defaultCustomer, universe);
    definition = ModelFactory.createAlertDefinition(defaultCustomer, universe, configuration);

    metricService = new MetricService();
    alertService = new AlertService();
    alertDefinitionService = new AlertDefinitionService(alertService);
    alertConfigurationService =
        new AlertConfigurationService(
            alertDefinitionService, new SettableRuntimeConfigFactory(app.config()));
    alertChannelService = new AlertChannelService();
    alertDestinationService =
        new AlertDestinationService(alertChannelService, alertConfigurationService);
    am =
        new AlertManager(
            emailHelper,
            alertService,
            alertConfigurationService,
            alertChannelService,
            alertDestinationService,
            channelsManager,
            metricService);

    defaultDestination = alertDestinationService.createDefaultDestination(defaultCustomer.uuid);
    defaultChannel = defaultDestination.getChannelsList().get(0);
    when(emailHelper.getDestinations(defaultCustomer.getUuid()))
        .thenReturn(Collections.singletonList(DEFAULT_EMAIL));
  }

  @Test
  public void testSendNotification_MetricsSetOk() {
    metricService.setStatusMetric(
        buildMetricTemplate(PlatformMetrics.ALERT_MANAGER_STATUS, defaultCustomer), "Some error");
    am.setChannelStatusMetric(
        PlatformMetrics.ALERT_MANAGER_CHANNEL_STATUS, defaultChannel, "Some channel error");

    Alert alert = ModelFactory.createAlert(defaultCustomer);

    am.sendNotificationForState(alert, State.ACTIVE, report);

    Metric amStatus =
        AssertHelper.assertMetricValue(
            metricService,
            MetricKey.builder()
                .customerUuid(defaultCustomer.getUuid())
                .name(PlatformMetrics.ALERT_MANAGER_STATUS.getMetricName())
                .targetUuid(defaultCustomer.getUuid())
                .build(),
            1.0);
    assertThat(amStatus.getLabelValue(KnownAlertLabels.ERROR_MESSAGE), nullValue());
    Metric channelStatus =
        AssertHelper.assertMetricValue(
            metricService,
            MetricKey.builder()
                .customerUuid(defaultCustomer.getUuid())
                .name(PlatformMetrics.ALERT_MANAGER_CHANNEL_STATUS.getMetricName())
                .targetUuid(defaultChannel.getUuid())
                .build(),
            1.0);
    assertThat(channelStatus.getLabelValue(KnownAlertLabels.ERROR_MESSAGE), nullValue());
  }

  @Test
  public void testSendNotification_FailureMetric() throws PlatformNotificationException {
    Alert alert = ModelFactory.createAlert(defaultCustomer);

    ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
    doThrow(new PlatformNotificationException("test"))
        .when(emailChannel)
        .sendNotification(eq(defaultCustomer), captor.capture(), any());
    am.sendNotificationForState(alert, State.ACTIVE, report);
    assertThat(captor.getValue().getUuid(), equalTo(alert.getUuid()));

    Metric channelStatus =
        AssertHelper.assertMetricValue(
            metricService,
            MetricKey.builder()
                .customerUuid(defaultCustomer.getUuid())
                .name(PlatformMetrics.ALERT_MANAGER_CHANNEL_STATUS.getMetricName())
                .targetUuid(defaultChannel.getUuid())
                .build(),
            0.0);
    assertThat(
        channelStatus.getLabelValue(KnownAlertLabels.ERROR_MESSAGE),
        equalTo("Error sending notification: test"));
  }

  @Test
  public void testSendNotification_AlertWoDefinition_SendEmailOldManner()
      throws PlatformNotificationException {
    Alert alert = ModelFactory.createAlert(defaultCustomer, universe);
    am.sendNotificationForState(alert, State.ACTIVE, report);

    ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
    verify(emailChannel, times(1)).sendNotification(eq(defaultCustomer), captor.capture(), any());
    assertThat(captor.getValue().getUuid(), is(alert.getUuid()));
  }

  @Test
  public void testSendNotification_NoDestinations() throws MessagingException {
    Alert alert = ModelFactory.createAlert(defaultCustomer, definition);
    am.sendNotificationForState(alert, State.ACTIVE, report);
    verify(emailHelper, never()).sendEmail(any(), anyString(), anyString(), any(), any());
  }

  @Test
  public void testSendNotification_TwoEmailDestinations()
      throws MessagingException, PlatformNotificationException {
    Alert alert = ModelFactory.createAlert(defaultCustomer, definition);

    AlertChannel channel1 =
        ModelFactory.createEmailChannel(defaultCustomer.getUuid(), "AlertChannel 1");
    AlertChannel channel2 =
        ModelFactory.createEmailChannel(defaultCustomer.getUuid(), "AlertChannel 2");
    AlertDestination destination =
        ModelFactory.createAlertDestination(
            defaultCustomer.uuid, ALERT_DESTINATION_NAME, ImmutableList.of(channel1, channel2));
    configuration.setDestinationUUID(destination.getUuid());
    configuration.save();

    am.sendNotificationForState(alert, State.ACTIVE, report);
    verify(emailHelper, never()).sendEmail(any(), anyString(), anyString(), any(), any());
    verify(emailChannel, times(2)).sendNotification(any(), any(), any());
  }

  @Test
  public void testDefaultDestination_IsUsed() throws PlatformNotificationException {
    Alert alert = ModelFactory.createAlert(defaultCustomer, universe);

    am.sendNotificationForState(alert, State.ACTIVE, report);
    ArgumentCaptor<AlertChannel> channelCaptor = ArgumentCaptor.forClass(AlertChannel.class);
    verify(emailChannel, times(1)).sendNotification(any(), any(), channelCaptor.capture());

    assertThat(AlertUtils.getJsonTypeName(channelCaptor.getValue().getParams()), is("Email"));
    AlertChannelEmailParams params = (AlertChannelEmailParams) channelCaptor.getValue().getParams();
    assertThat(params.recipients, nullValue());
    assertThat(params.defaultRecipients, is(true));
  }

  @Test
  public void testDefaultDestination_EmptyRecipientsAlertResolved()
      throws PlatformNotificationException {
    Alert alert = ModelFactory.createAlert(defaultCustomer, universe);
    when(emailHelper.getDestinations(defaultCustomer.getUuid()))
        .thenReturn(Collections.emptyList());

    am.sendNotificationForState(alert, State.ACTIVE, report);
    verify(emailChannel, never()).sendNotification(any(), any(), any());

    Metric amStatus =
        AssertHelper.assertMetricValue(
            metricService,
            MetricKey.builder()
                .customerUuid(defaultCustomer.getUuid())
                .name(PlatformMetrics.ALERT_MANAGER_STATUS.getMetricName())
                .targetUuid(defaultCustomer.getUuid())
                .build(),
            0.0);
    assertThat(
        amStatus.getLabelValue(KnownAlertLabels.ERROR_MESSAGE),
        equalTo(
            "Unable to notify about alert(s) using default destination, "
                + "there are no recipients configured in the customer's profile."));

    // Restoring recipients.
    when(emailHelper.getDestinations(defaultCustomer.getUuid()))
        .thenReturn(Collections.singletonList(DEFAULT_EMAIL));

    am.sendNotificationForState(alert, State.ACTIVE, report);
    verify(emailChannel, times(1)).sendNotification(any(), any(), any());

    amStatus =
        AssertHelper.assertMetricValue(
            metricService,
            MetricKey.builder()
                .customerUuid(defaultCustomer.getUuid())
                .name(PlatformMetrics.ALERT_MANAGER_STATUS.getMetricName())
                .targetUuid(defaultCustomer.getUuid())
                .build(),
            1.0);
    assertThat(amStatus.getLabelValue(KnownAlertLabels.ERROR_MESSAGE), nullValue());
  }

  // Aren't checking ACKNOWLEDGED in any state fields as such alert should not be
  // scheduled.
  @Parameters({
    // @formatter:off
    "null, ACTIVE, 1",
    "null, RESOLVED, 2",
    "ACTIVE, RESOLVED, 1",
    // @formatter:on
  })
  @TestCaseName(
      "{method}(Last sent state:{0}, current state:{1}, " + "expected notifications count:{2})")
  @Test
  public void testSendNotifications_CountMatched(
      @Nullable State notifiedState, State currentState, int expectedCount)
      throws PlatformNotificationException {
    Alert alert = ModelFactory.createAlert(defaultCustomer, universe);
    alert
        .setState(currentState)
        .setDefinitionUuid(definition.getUuid())
        .setNotifiedState(notifiedState);
    if (expectedCount > 0) {
      alert.setNextNotificationTime(Date.from(new Date().toInstant().minusSeconds(10)));
    }
    alert.save();

    am.sendNotifications();

    ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
    verify(emailChannel, times(expectedCount))
        .sendNotification(eq(defaultCustomer), captor.capture(), any());

    if (expectedCount > 0) {
      assertThat(captor.getValue().getUuid(), equalTo(alert.getUuid()));

      Alert updatedAlert = alertService.get(alert.getUuid());
      assertThat(updatedAlert.getNextNotificationTime(), nullValue());
      assertThat(updatedAlert.getNotificationAttemptTime(), notNullValue());
      assertThat(updatedAlert.getNotificationsFailed(), is(0));
    }
  }

  @Test
  public void testSendNotificationForState_WithAnotherState() throws PlatformNotificationException {
    Alert alert = ModelFactory.createAlert(defaultCustomer, universe);
    alert.setState(State.RESOLVED);
    alert.save();

    am.sendNotificationForState(alert, State.ACTIVE, report);

    ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
    verify(emailChannel, times(1)).sendNotification(eq(defaultCustomer), captor.capture(), any());
    assertThat(captor.getValue().getState(), is(State.ACTIVE));
  }
}
