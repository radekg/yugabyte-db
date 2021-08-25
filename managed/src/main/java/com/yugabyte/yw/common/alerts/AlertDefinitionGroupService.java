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

import static com.yugabyte.yw.common.Util.doubleToString;
import static com.yugabyte.yw.models.AlertDefinitionGroup.createQueryByFilter;
import static com.yugabyte.yw.models.helpers.CommonUtils.nowWithoutMillis;
import static com.yugabyte.yw.models.helpers.CommonUtils.performPagedQuery;
import static com.yugabyte.yw.models.helpers.EntityOperation.CREATE;
import static com.yugabyte.yw.models.helpers.EntityOperation.DELETE;
import static com.yugabyte.yw.models.helpers.EntityOperation.UPDATE;
import static play.mvc.Http.Status.BAD_REQUEST;

import com.yugabyte.yw.common.AlertTemplate;
import com.yugabyte.yw.common.YWServiceException;
import com.yugabyte.yw.common.alerts.impl.AlertDefinitionTemplate;
import com.yugabyte.yw.common.config.RuntimeConfigFactory;
import com.yugabyte.yw.common.metrics.MetricLabelsBuilder;
import com.yugabyte.yw.models.AlertDefinition;
import com.yugabyte.yw.models.AlertDefinitionGroup;
import com.yugabyte.yw.models.AlertDefinitionGroup.SortBy;
import com.yugabyte.yw.models.AlertDefinitionGroupTarget;
import com.yugabyte.yw.models.AlertDefinitionGroupThreshold;
import com.yugabyte.yw.models.AlertRoute;
import com.yugabyte.yw.models.Customer;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.filters.AlertDefinitionFilter;
import com.yugabyte.yw.models.filters.AlertDefinitionGroupFilter;
import com.yugabyte.yw.models.helpers.EntityOperation;
import com.yugabyte.yw.models.paging.AlertDefinitionGroupPagedQuery;
import com.yugabyte.yw.models.paging.AlertDefinitionGroupPagedResponse;
import com.yugabyte.yw.models.paging.PagedQuery.SortDirection;
import io.ebean.Query;
import io.ebean.annotation.Transactional;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;

@Singleton
@Slf4j
public class AlertDefinitionGroupService {

  private static final int MAX_NAME_LENGTH = 1000;

  private final AlertDefinitionService alertDefinitionService;
  private final RuntimeConfigFactory runtimeConfigFactory;

  @Inject
  public AlertDefinitionGroupService(
      AlertDefinitionService alertDefinitionService, RuntimeConfigFactory runtimeConfigFactory) {
    this.alertDefinitionService = alertDefinitionService;
    this.runtimeConfigFactory = runtimeConfigFactory;
  }

  @Transactional
  public List<AlertDefinitionGroup> save(List<AlertDefinitionGroup> groups) {
    if (CollectionUtils.isEmpty(groups)) {
      return groups;
    }

    List<AlertDefinitionGroup> beforeGroups = Collections.emptyList();
    Set<UUID> groupUuids =
        groups
            .stream()
            .filter(group -> !group.isNew())
            .map(AlertDefinitionGroup::getUuid)
            .collect(Collectors.toSet());
    if (!groupUuids.isEmpty()) {
      AlertDefinitionGroupFilter filter =
          AlertDefinitionGroupFilter.builder().uuids(groupUuids).build();
      beforeGroups = list(filter);
    }
    Map<UUID, AlertDefinitionGroup> beforeGroupMap =
        beforeGroups
            .stream()
            .collect(Collectors.toMap(AlertDefinitionGroup::getUuid, Function.identity()));

    Map<EntityOperation, List<AlertDefinitionGroup>> toCreateAndUpdate =
        groups
            .stream()
            .peek(group -> prepareForSave(group, beforeGroupMap.get(group.getUuid())))
            .peek(group -> validate(group, beforeGroupMap.get(group.getUuid())))
            .collect(Collectors.groupingBy(group -> group.isNew() ? CREATE : UPDATE));

    if (toCreateAndUpdate.containsKey(CREATE)) {
      List<AlertDefinitionGroup> toCreate = toCreateAndUpdate.get(CREATE);
      toCreate.forEach(group -> group.setCreateTime(nowWithoutMillis()));
      toCreate.forEach(AlertDefinitionGroup::generateUUID);
      AlertDefinitionGroup.db().saveAll(toCreate);
    }

    if (toCreateAndUpdate.containsKey(UPDATE)) {
      List<AlertDefinitionGroup> toUpdate = toCreateAndUpdate.get(UPDATE);
      AlertDefinitionGroup.db().updateAll(toUpdate);
    }

    manageDefinitions(groups, beforeGroups);

    log.debug("{} alert definition groups saved", groups.size());
    return groups;
  }

  @Transactional
  public AlertDefinitionGroup save(AlertDefinitionGroup definition) {
    return save(Collections.singletonList(definition)).get(0);
  }

  public AlertDefinitionGroup get(UUID uuid) {
    if (uuid == null) {
      throw new YWServiceException(BAD_REQUEST, "Can't get Alert Definition Group by null uuid");
    }
    return list(AlertDefinitionGroupFilter.builder().uuid(uuid).build())
        .stream()
        .findFirst()
        .orElse(null);
  }

  public AlertDefinitionGroup getOrBadRequest(UUID uuid) {
    if (uuid == null) {
      throw new YWServiceException(BAD_REQUEST, "Invalid Alert Definition Group UUID: " + uuid);
    }
    AlertDefinitionGroup group = get(uuid);
    if (group == null) {
      throw new YWServiceException(BAD_REQUEST, "Invalid Alert Definition Group UUID: " + uuid);
    }
    return group;
  }

  public List<AlertDefinitionGroup> list(AlertDefinitionGroupFilter filter) {
    return createQueryByFilter(filter).findList();
  }

  public AlertDefinitionGroupPagedResponse pagedList(AlertDefinitionGroupPagedQuery pagedQuery) {
    if (pagedQuery.getSortBy() == null) {
      pagedQuery.setSortBy(SortBy.createTime);
      pagedQuery.setDirection(SortDirection.DESC);
    }
    Query<AlertDefinitionGroup> query = createQueryByFilter(pagedQuery.getFilter()).query();
    return performPagedQuery(query, pagedQuery, AlertDefinitionGroupPagedResponse.class);
  }

  public List<UUID> listIds(AlertDefinitionGroupFilter filter) {
    return createQueryByFilter(filter).findIds();
  }

  public void process(AlertDefinitionGroupFilter filter, Consumer<AlertDefinitionGroup> consumer) {
    createQueryByFilter(filter).findEach(consumer);
  }

  @Transactional
  public void delete(UUID uuid) {
    AlertDefinitionGroupFilter filter = AlertDefinitionGroupFilter.builder().uuid(uuid).build();
    delete(filter);
  }

  @Transactional
  public void delete(Collection<AlertDefinitionGroup> groups) {
    if (CollectionUtils.isEmpty(groups)) {
      return;
    }
    AlertDefinitionGroupFilter filter =
        AlertDefinitionGroupFilter.builder()
            .uuids(groups.stream().map(AlertDefinitionGroup::getUuid).collect(Collectors.toSet()))
            .build();
    delete(filter);
  }

  public void delete(AlertDefinitionGroupFilter filter) {
    List<AlertDefinitionGroup> toDelete = list(filter);

    manageDefinitions(Collections.emptyList(), toDelete);

    int deleted = createQueryByFilter(filter).delete();
    log.debug("{} alert definition groups deleted", deleted);
  }

  private void prepareForSave(AlertDefinitionGroup group, AlertDefinitionGroup before) {
    if (before != null) {
      group.setCreateTime(before.getCreateTime());
    }
  }

  private void validate(AlertDefinitionGroup group, AlertDefinitionGroup before) {
    if (group.getCustomerUUID() == null) {
      throw new YWServiceException(BAD_REQUEST, "Customer UUID field is mandatory");
    }
    if (StringUtils.isEmpty(group.getName())) {
      throw new YWServiceException(BAD_REQUEST, "Name field is mandatory");
    }
    if (group.getName().length() > MAX_NAME_LENGTH) {
      throw new YWServiceException(
          BAD_REQUEST, "Name field can't be longer than " + MAX_NAME_LENGTH + " characters");
    }
    if (group.getTargetType() == null) {
      throw new YWServiceException(BAD_REQUEST, "Target type field is mandatory");
    }
    if (group.getTarget() == null) {
      throw new YWServiceException(BAD_REQUEST, "Target field is mandatory");
    }
    AlertDefinitionGroupTarget target = group.getTarget();
    if (target.isAll() != CollectionUtils.isEmpty(target.getUuids())) {
      throw new YWServiceException(
          BAD_REQUEST, "Should select either all entries or particular UUIDs as target");
    }
    if (!CollectionUtils.isEmpty(target.getUuids())) {
      switch (group.getTargetType()) {
        case UNIVERSE:
          Set<UUID> existingUuids =
              Universe.getAllWithoutResources(group.getTarget().getUuids())
                  .stream()
                  .map(Universe::getUniverseUUID)
                  .collect(Collectors.toSet());
          Set<UUID> missingUuids = new HashSet<>(group.getTarget().getUuids());
          missingUuids.removeAll(existingUuids);
          if (!missingUuids.isEmpty()) {
            throw new YWServiceException(
                BAD_REQUEST,
                "Universe(s) missing for uuid(s) "
                    + missingUuids.stream().map(UUID::toString).collect(Collectors.joining(", ")));
          }
          break;
        default:
          throw new YWServiceException(
              BAD_REQUEST, group.getTargetType().name() + " group can't have target uuids");
      }
    }
    if (group.getTemplate() == null) {
      throw new YWServiceException(BAD_REQUEST, "Template field is mandatory");
    }
    if (group.getTemplate().getTargetType() != group.getTargetType()) {
      throw new YWServiceException(BAD_REQUEST, "Target type should be consistent with template");
    }
    if (MapUtils.isEmpty(group.getThresholds())) {
      throw new YWServiceException(BAD_REQUEST, "Query thresholds are mandatory");
    }
    if (group.getRouteUUID() != null
        && AlertRoute.get(group.getCustomerUUID(), group.getRouteUUID()) == null) {
      throw new YWServiceException(
          BAD_REQUEST, "Alert route " + group.getRouteUUID() + " is missing");
    }
    if (group.getThresholdUnit() == null) {
      throw new YWServiceException(BAD_REQUEST, "Threshold unit is mandatory");
    }
    if (group.getThresholdUnit() != group.getTemplate().getDefaultThresholdUnit()) {
      throw new YWServiceException(
          BAD_REQUEST, "Can't set threshold unit incompatible with alert definition template");
    }
    group
        .getThresholds()
        .values()
        .forEach(
            threshold -> {
              if (threshold.getCondition() == null) {
                throw new YWServiceException(BAD_REQUEST, "Threshold condition is mandatory");
              }
              if (threshold.getThreshold() == null) {
                throw new YWServiceException(BAD_REQUEST, "Threshold value is mandatory");
              }
              if (threshold.getThreshold() < group.getTemplate().getThresholdMinValue()) {
                throw new YWServiceException(
                    BAD_REQUEST,
                    "Threshold value can't be less than "
                        + doubleToString(group.getTemplate().getThresholdMinValue()));
              }
              if (threshold.getThreshold() > group.getTemplate().getThresholdMaxValue()) {
                throw new YWServiceException(
                    BAD_REQUEST,
                    "Threshold value can't be greater than "
                        + doubleToString(group.getTemplate().getThresholdMaxValue()));
              }
            });
    if (group.getDurationSec() == null || group.getDurationSec() < 0) {
      throw new YWServiceException(BAD_REQUEST, "Duration can't be less than 0");
    }
    if (before != null) {
      if (!group.getCustomerUUID().equals(before.getCustomerUUID())) {
        throw new YWServiceException(
            BAD_REQUEST, "Can't change customer UUID for group " + group.getUuid());
      }
      if (!group.getTargetType().equals(before.getTargetType())) {
        throw new YWServiceException(
            BAD_REQUEST, "Can't change target type for group " + group.getUuid());
      }
      if (!group.getCreateTime().equals(before.getCreateTime())) {
        throw new YWServiceException(
            BAD_REQUEST, "Can't change create time for group " + group.getUuid());
      }
    } else if (!group.isNew()) {
      throw new YWServiceException(BAD_REQUEST, "Can't update missing group " + group.getUuid());
    }
  }

  @Transactional
  public void handleTargetRemoval(
      UUID customerUuid, AlertDefinitionGroup.TargetType groupType, UUID targetUuid) {
    AlertDefinitionGroupFilter filter =
        AlertDefinitionGroupFilter.builder()
            .customerUuid(customerUuid)
            .targetType(groupType)
            .build();

    List<AlertDefinitionGroup> groups =
        list(filter)
            .stream()
            .filter(
                group ->
                    group.getTarget().isAll() || group.getTarget().getUuids().remove(targetUuid))
            .collect(Collectors.toList());

    Map<EntityOperation, List<AlertDefinitionGroup>> toUpdateAndDelete =
        groups
            .stream()
            .collect(
                Collectors.groupingBy(
                    group ->
                        group.getTarget().isAll() || !group.getTarget().getUuids().isEmpty()
                            ? UPDATE
                            : DELETE));

    // Just need to save - service will delete definition itself.
    save(toUpdateAndDelete.get(UPDATE));
    delete(toUpdateAndDelete.get(DELETE));
  }

  private void manageDefinitions(
      List<AlertDefinitionGroup> groups, List<AlertDefinitionGroup> beforeList) {
    Set<UUID> groupUUIDs =
        Stream.concat(groups.stream(), beforeList.stream())
            .map(AlertDefinitionGroup::getUuid)
            .collect(Collectors.toSet());

    if (groupUUIDs.isEmpty()) {
      return;
    }

    AlertDefinitionFilter filter = AlertDefinitionFilter.builder().groupUuids(groupUUIDs).build();
    Map<UUID, List<AlertDefinition>> definitionsByGroup =
        alertDefinitionService
            .list(filter)
            .stream()
            .collect(Collectors.groupingBy(AlertDefinition::getGroupUUID, Collectors.toList()));

    List<AlertDefinition> toSave = new ArrayList<>();
    List<AlertDefinition> toRemove = new ArrayList<>();

    Map<UUID, AlertDefinitionGroup> groupsMap =
        groups
            .stream()
            .collect(Collectors.toMap(AlertDefinitionGroup::getUuid, Function.identity()));
    Map<UUID, AlertDefinitionGroup> beforeMap =
        beforeList
            .stream()
            .collect(Collectors.toMap(AlertDefinitionGroup::getUuid, Function.identity()));
    for (UUID uuid : groupUUIDs) {
      AlertDefinitionGroup group = groupsMap.get(uuid);
      AlertDefinitionGroup before = beforeMap.get(uuid);

      List<AlertDefinition> currentDefinitions =
          definitionsByGroup.getOrDefault(uuid, Collections.emptyList());
      if (group == null) {
        toRemove.addAll(currentDefinitions);
      } else {
        boolean configurationChanged = before != null && !before.equals(group);
        Customer customer = Customer.getOrBadRequest(group.getCustomerUUID());
        AlertDefinitionGroupTarget target = group.getTarget();
        switch (group.getTargetType()) {
          case CUSTOMER:
            if (currentDefinitions.size() > 1) {
              throw new IllegalStateException(
                  "More than one definition for CUSTOMER alert definition group " + uuid);
            }
            AlertDefinition definition;
            if (currentDefinitions.isEmpty()) {
              definition = createEmptyDefinition(group);
            } else {
              definition = currentDefinitions.get(0);
            }
            definition.setQuery(group.getTemplate().buildTemplate(customer));
            if (!group.getTemplate().isSkipTargetLabels()) {
              definition.setLabels(
                  MetricLabelsBuilder.create().appendTarget(customer).getDefinitionLabels());
            }
            toSave.add(definition);
            break;
          case UNIVERSE:
            Set<UUID> universeUUIDs;
            Set<Universe> universes;

            if (target.isAll()) {
              universes = Universe.getAllWithoutResources(customer);
              universeUUIDs =
                  Stream.concat(
                          currentDefinitions.stream().map(AlertDefinition::getUniverseUUID),
                          universes.stream().map(Universe::getUniverseUUID))
                      .collect(Collectors.toSet());
            } else {
              universeUUIDs =
                  Stream.concat(
                          currentDefinitions.stream().map(AlertDefinition::getUniverseUUID),
                          target.getUuids().stream())
                      .collect(Collectors.toSet());
              universes = Universe.getAllWithoutResources(universeUUIDs);
            }
            Map<UUID, Universe> universeMap =
                universes
                    .stream()
                    .collect(Collectors.toMap(Universe::getUniverseUUID, Function.identity()));
            Map<UUID, AlertDefinition> definitionsByUniverseUuid =
                currentDefinitions
                    .stream()
                    .collect(
                        Collectors.toMap(AlertDefinition::getUniverseUUID, Function.identity()));
            for (UUID universeUuid : universeUUIDs) {
              Universe universe = universeMap.get(universeUuid);
              AlertDefinition universeDefinition = definitionsByUniverseUuid.get(universeUuid);
              boolean shouldHaveDefinition =
                  (target.isAll() || target.getUuids().contains(universeUuid)) && universe != null;
              if (shouldHaveDefinition) {
                if (universeDefinition == null) {
                  universeDefinition = createEmptyDefinition(group);
                } else if (!configurationChanged) {
                  // We want to avoid updating definitions unnecessarily
                  continue;
                }
                universeDefinition.setConfigWritten(false);
                universeDefinition.setQuery(group.getTemplate().buildTemplate(customer, universe));
                if (!group.getTemplate().isSkipTargetLabels()) {
                  universeDefinition.setLabels(
                      MetricLabelsBuilder.create().appendTarget(universe).getDefinitionLabels());
                }
                toSave.add(universeDefinition);
              } else if (universeDefinition != null) {
                toRemove.add(universeDefinition);
              }
            }
            break;
          default:
            throw new IllegalStateException("Unexpected target type " + group.getTargetType());
        }
      }
    }

    if (!toSave.isEmpty()) {
      alertDefinitionService.save(toSave);
    }
    if (!toRemove.isEmpty()) {
      Set<UUID> uuids = toRemove.stream().map(AlertDefinition::getUuid).collect(Collectors.toSet());
      alertDefinitionService.delete(AlertDefinitionFilter.builder().uuids(uuids).build());
    }
  }

  public AlertDefinitionTemplate createDefinitionTemplate(
      Customer customer, AlertTemplate template) {
    AlertDefinitionGroup group =
        new AlertDefinitionGroup()
            .setCustomerUUID(customer.getUuid())
            .setName(template.getName())
            .setDescription(template.getDescription())
            .setTargetType(template.getTargetType())
            .setTarget(new AlertDefinitionGroupTarget().setAll(true))
            .setThresholds(
                template
                    .getDefaultThresholdMap()
                    .entrySet()
                    .stream()
                    .collect(
                        Collectors.toMap(
                            Map.Entry::getKey,
                            e ->
                                new AlertDefinitionGroupThreshold()
                                    .setCondition(template.getDefaultThresholdCondition())
                                    .setThreshold(
                                        e.getValue().isParamName()
                                            ? runtimeConfigFactory
                                                .globalRuntimeConf()
                                                .getDouble(e.getValue().getParamName())
                                            : e.getValue().getThreshold()))))
            .setThresholdUnit(template.getDefaultThresholdUnit())
            .setTemplate(template)
            .setDurationSec(template.getDefaultDurationSec());
    return new AlertDefinitionTemplate()
        .setDefaultGroup(group)
        .setThresholdMinValue(template.getThresholdMinValue())
        .setThresholdMaxValue(template.getThresholdMaxValue())
        .setThresholdInteger(template.getDefaultThresholdUnit().isInteger());
  }

  private AlertDefinition createEmptyDefinition(AlertDefinitionGroup group) {
    return new AlertDefinition()
        .setCustomerUUID(group.getCustomerUUID())
        .setGroupUUID(group.getUuid());
  }
}
