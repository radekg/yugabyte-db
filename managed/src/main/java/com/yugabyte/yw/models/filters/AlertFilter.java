/*
 * Copyright 2021 YugaByte, Inc. and Contributors
 *
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0.txt
 */
package com.yugabyte.yw.models.filters;

import com.yugabyte.yw.models.Alert;
import com.yugabyte.yw.models.AlertDefinitionGroup;
import com.yugabyte.yw.models.AlertDefinitionGroup.Severity;
import com.yugabyte.yw.models.AlertLabel;
import com.yugabyte.yw.models.helpers.KnownAlertLabels;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;
import lombok.Value;

@Value
@Builder
public class AlertFilter {
  Set<UUID> uuids;
  Set<UUID> excludeUuids;
  UUID customerUuid;
  Set<Alert.State> states;
  Set<UUID> definitionUuids;
  UUID groupUuid;
  Set<AlertDefinitionGroup.Severity> severities;
  Set<AlertDefinitionGroup.TargetType> groupTypes;
  AlertLabel label;
  Boolean notificationPending;

  // Can't use @Builder(toBuilder = true) as it sets null fields as well, which breaks non null
  // checks.
  public AlertFilterBuilder toBuilder() {
    AlertFilterBuilder result = AlertFilter.builder();
    if (uuids != null) {
      result.uuids(uuids);
    }
    if (excludeUuids != null) {
      result.excludeUuids(excludeUuids);
    }
    if (customerUuid != null) {
      result.customerUuid(customerUuid);
    }
    if (label != null) {
      result.label(label);
    }
    if (states != null) {
      result.states(states);
    }
    if (definitionUuids != null) {
      result.definitionUuids(definitionUuids);
    }
    if (groupUuid != null) {
      result.groupUuid(groupUuid);
    }
    if (severities != null) {
      result.severities(severities);
    }
    if (groupTypes != null) {
      result.groupTypes(groupTypes);
    }
    if (notificationPending != null) {
      result.notificationPending(notificationPending);
    }
    return result;
  }

  public static class AlertFilterBuilder {
    Set<UUID> uuids = new HashSet<>();
    Set<UUID> excludeUuids = new HashSet<>();
    Set<Alert.State> states = EnumSet.noneOf(Alert.State.class);
    Set<UUID> definitionUuids = new HashSet<>();
    Set<AlertDefinitionGroup.Severity> severities = new HashSet<>();
    Set<AlertDefinitionGroup.TargetType> groupTypes = new HashSet<>();

    public AlertFilterBuilder uuid(@NonNull UUID uuid) {
      this.uuids.add(uuid);
      return this;
    }

    public AlertFilterBuilder uuids(@NonNull Collection<UUID> uuids) {
      this.uuids.addAll(uuids);
      return this;
    }

    public AlertFilterBuilder excludeUuid(@NonNull UUID uuid) {
      this.excludeUuids.add(uuid);
      return this;
    }

    public AlertFilterBuilder excludeUuids(@NonNull Collection<UUID> uuids) {
      this.excludeUuids.addAll(uuids);
      return this;
    }

    public AlertFilterBuilder customerUuid(@NonNull UUID customerUuid) {
      this.customerUuid = customerUuid;
      return this;
    }

    public AlertFilterBuilder state(@NonNull Alert.State... state) {
      states.addAll(Arrays.asList(state));
      return this;
    }

    public AlertFilterBuilder states(@NonNull Set<Alert.State> states) {
      this.states.addAll(states);
      return this;
    }

    public AlertFilterBuilder label(@NonNull KnownAlertLabels name, @NonNull String value) {
      label = new AlertLabel(name.labelName(), value);
      return this;
    }

    public AlertFilterBuilder label(@NonNull String name, @NonNull String value) {
      label = new AlertLabel(name, value);
      return this;
    }

    public AlertFilterBuilder label(@NonNull AlertLabel label) {
      this.label = label;
      return this;
    }

    public AlertFilterBuilder definitionUuid(@NonNull UUID uuid) {
      this.definitionUuids.add(uuid);
      return this;
    }

    public AlertFilterBuilder definitionUuids(Collection<UUID> definitionUuids) {
      this.definitionUuids = new HashSet<>(definitionUuids);
      return this;
    }

    public AlertFilterBuilder severity(@NonNull Severity... severities) {
      this.severities.addAll(Arrays.asList(severities));
      return this;
    }

    public AlertFilterBuilder severities(@NonNull Set<Severity> severities) {
      this.severities.addAll(severities);
      return this;
    }

    public AlertFilterBuilder groupType(@NonNull AlertDefinitionGroup.TargetType... groupTypes) {
      this.groupTypes.addAll(Arrays.asList(groupTypes));
      return this;
    }

    public AlertFilterBuilder groupTypes(@NonNull Set<AlertDefinitionGroup.TargetType> groupTypes) {
      this.groupTypes.addAll(groupTypes);
      return this;
    }

    public AlertFilterBuilder notificationPending(boolean notificationPending) {
      this.notificationPending = notificationPending;
      return this;
    }
  }
}
