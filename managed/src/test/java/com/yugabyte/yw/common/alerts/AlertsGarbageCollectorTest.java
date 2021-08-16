/*
 * Copyright 2021 YugaByte, Inc. and Contributors
 *
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0.txt
 */
package com.yugabyte.yw.common.alerts;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import akka.actor.ActorSystem;
import akka.actor.Scheduler;
import com.typesafe.config.Config;
import com.yugabyte.yw.common.FakeDBApplication;
import com.yugabyte.yw.common.ModelFactory;
import com.yugabyte.yw.common.config.RuntimeConfigFactory;
import com.yugabyte.yw.models.Alert;
import com.yugabyte.yw.models.Alert.State;
import com.yugabyte.yw.models.Customer;
import com.yugabyte.yw.models.filters.AlertFilter;
import java.sql.Date;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import scala.concurrent.ExecutionContext;

@RunWith(MockitoJUnitRunner.Silent.class)
public class AlertsGarbageCollectorTest extends FakeDBApplication {

  @Mock private ExecutionContext executionContext;

  @Mock private ActorSystem actorSystem;

  @Mock Config mockAppConfig;

  @Mock RuntimeConfigFactory mockRuntimeConfigFactory;

  private AlertsGarbageCollector alertsGarbageCollector;

  private AlertService alertService = new AlertService();

  private Customer customer;

  @Before
  public void setUp() {
    customer = ModelFactory.testCustomer();
    when(mockRuntimeConfigFactory.forCustomer(customer)).thenReturn(mockAppConfig);
    when(mockAppConfig.getDuration(AlertsGarbageCollector.YB_ALERT_GC_RESOLVED_RETENTION_DURATION))
        .thenReturn(Duration.of(2, ChronoUnit.MINUTES));
    when(actorSystem.scheduler()).thenReturn(mock(Scheduler.class));
    alertsGarbageCollector =
        new AlertsGarbageCollector(
            executionContext, actorSystem, mockRuntimeConfigFactory, alertService);
  }

  @Test
  public void testAlertsGc() {
    Alert activeAlert = ModelFactory.createAlert(customer);
    Alert resolvedNotCollected = ModelFactory.createAlert(customer);
    resolvedNotCollected.setState(State.RESOLVED);
    resolvedNotCollected.setResolvedTime(Date.from(Instant.now().minus(1, ChronoUnit.MINUTES)));
    resolvedNotCollected.save();
    Alert resolvedCollected = ModelFactory.createAlert(customer);
    resolvedCollected.setState(State.RESOLVED);
    resolvedCollected.setResolvedTime(Date.from(Instant.now().minus(3, ChronoUnit.MINUTES)));
    resolvedCollected.save();

    alertsGarbageCollector.scheduleRunner();

    List<Alert> remainingAlerts = alertService.list(AlertFilter.builder().build());
    assertThat(remainingAlerts, containsInAnyOrder(activeAlert, resolvedNotCollected));
  }
}
