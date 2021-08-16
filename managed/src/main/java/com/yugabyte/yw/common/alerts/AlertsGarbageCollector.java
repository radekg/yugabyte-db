package com.yugabyte.yw.common.alerts;

import akka.actor.ActorSystem;
import akka.actor.Scheduler;
import com.google.common.annotations.VisibleForTesting;
import com.yugabyte.yw.common.config.RuntimeConfigFactory;
import com.yugabyte.yw.models.Customer;
import com.yugabyte.yw.models.filters.AlertFilter;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Counter;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import scala.concurrent.ExecutionContext;

@Singleton
@Slf4j
public class AlertsGarbageCollector {

  private static final int YB_ALERT_GC_INTERVAL_DAYS = 1;

  // Counter names
  private static final String ALERT_GC_COUNT = "ybp_alert_gc_count";
  private static final String ALERT_GC_RUN_COUNT = "ybp_alert_gc_run_count";

  // Counters
  private static final Counter ALERT_GC_COUNTER =
      Counter.build(ALERT_GC_COUNT, "Number of garbage collected alerts")
          .register(CollectorRegistry.defaultRegistry);
  private static final Counter ALERT_GC_RUN_COUNTER =
      Counter.build(ALERT_GC_RUN_COUNT, "Number of alert GC runs")
          .register(CollectorRegistry.defaultRegistry);

  // Config names
  @VisibleForTesting
  static final String YB_ALERT_GC_RESOLVED_RETENTION_DURATION =
      "yb.alert.resolved_retention_duration";

  private final Scheduler scheduler;
  private final RuntimeConfigFactory runtimeConfigFactory;
  private final ExecutionContext executionContext;
  private final AlertService alertService;

  @Inject
  public AlertsGarbageCollector(
      ExecutionContext executionContext,
      ActorSystem actorSystem,
      RuntimeConfigFactory runtimeConfigFactory,
      AlertService alertService) {

    this.scheduler = actorSystem.scheduler();
    this.runtimeConfigFactory = runtimeConfigFactory;
    this.executionContext = executionContext;
    this.alertService = alertService;
    start();
  }

  public void start() {
    scheduler.schedule(
        Duration.ZERO,
        Duration.of(YB_ALERT_GC_INTERVAL_DAYS, ChronoUnit.DAYS),
        this::scheduleRunner,
        this.executionContext);
  }

  @VisibleForTesting
  void scheduleRunner() {
    Customer.getAll().forEach(this::checkCustomer);
  }

  private void checkCustomer(Customer c) {
    ALERT_GC_RUN_COUNTER.inc();
    Date resolvedDateBefore = Date.from(Instant.now().minus(alertRetentionDuration(c)));
    AlertFilter filter =
        AlertFilter.builder()
            .customerUuid(c.getUuid())
            .resolvedDateBefore(resolvedDateBefore)
            .build();
    int deleted = alertService.delete(filter);
    ALERT_GC_COUNTER.inc(deleted);
  }

  private Duration alertRetentionDuration(Customer customer) {
    return runtimeConfigFactory
        .forCustomer(customer)
        .getDuration(YB_ALERT_GC_RESOLVED_RETENTION_DURATION);
  }
}
