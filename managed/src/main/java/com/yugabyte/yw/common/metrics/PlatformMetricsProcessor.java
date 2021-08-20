/*
 * Copyright 2021 YugaByte, Inc. and Contributors
 *
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0.txt
 */

package com.yugabyte.yw.common.metrics;

import static com.yugabyte.yw.common.metrics.MetricService.buildMetricTemplate;

import akka.actor.ActorSystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.yugabyte.yw.models.Metric;
import com.yugabyte.yw.models.filters.MetricFilter;
import com.yugabyte.yw.models.helpers.PlatformMetrics;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import scala.concurrent.ExecutionContext;
import scala.concurrent.duration.Duration;

@Singleton
@Slf4j
public class PlatformMetricsProcessor {

  private static final int YB_PROCESS_METRICS_INTERVAL_MIN = 1;

  private final AtomicBoolean running = new AtomicBoolean(false);

  private final ActorSystem actorSystem;

  private final ExecutionContext executionContext;

  private final MetricService metricService;

  private final List<MetricsProvider> metricsProviderList = new ArrayList<>();

  @Inject
  public PlatformMetricsProcessor(
      ActorSystem actorSystem,
      ExecutionContext executionContext,
      MetricService metricService,
      UniverseMetricProvider universeMetricProvider) {
    this.actorSystem = actorSystem;
    this.executionContext = executionContext;
    this.metricService = metricService;
    this.metricsProviderList.add(universeMetricProvider);
    this.initialize();
  }

  private void initialize() {
    this.actorSystem
        .scheduler()
        .schedule(
            Duration.Zero(),
            Duration.create(YB_PROCESS_METRICS_INTERVAL_MIN, TimeUnit.MINUTES),
            this::scheduleRunner,
            this.executionContext);
  }

  @VisibleForTesting
  void scheduleRunner() {
    String errorMessage = null;
    if (running.compareAndSet(false, true)) {
      try {
        errorMessage = updateMetrics();
        cleanExpiredMetrics();
      } catch (Exception e) {
        errorMessage = "Error processing metrics: " + e.getMessage();
        log.error("Error processing metrics", e);
      } finally {
        metricService.setStatusMetric(
            buildMetricTemplate(PlatformMetrics.METRIC_PROCESSOR_STATUS), errorMessage);
        running.set(false);
      }
    }
  }

  private String updateMetrics() {
    List<Metric> metrics = new ArrayList<>();
    List<MetricFilter> toRemove = new ArrayList<>();
    String errorMessage = null;
    for (MetricsProvider provider : metricsProviderList) {
      try {
        metrics.addAll(provider.getMetrics());
        toRemove.addAll(provider.getMetricsToRemove());
      } catch (Exception e) {
        log.error("Failed to get platform metrics from provider " + provider.getName(), e);
        if (errorMessage == null) {
          errorMessage =
              "Failed to get platform metrics from provider "
                  + provider.getName()
                  + ": "
                  + e.getMessage();
        }
      }
    }
    metricService.cleanAndSave(metrics, toRemove);
    return errorMessage;
  }

  private void cleanExpiredMetrics() {
    MetricFilter metricFilter = MetricFilter.builder().expired(true).build();
    metricService.delete(metricFilter);
  }
}
