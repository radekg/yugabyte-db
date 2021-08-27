// Copyright (c) Yugabyte, Inc.

package com.yugabyte.yw.models;

import static com.yugabyte.yw.models.helpers.CommonUtils.deepMerge;
import static io.swagger.annotations.ApiModelProperty.AccessMode.READ_ONLY;
import static play.mvc.Http.Status.BAD_REQUEST;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Joiner;
import com.yugabyte.yw.common.PlatformServiceException;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams.Cluster;
import io.ebean.Finder;
import io.ebean.Model;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.data.validation.Constraints;
import play.libs.Json;

@Entity
@ApiModel(description = "Customers features and Universe UUID.")
public class Customer extends Model {

  public static final Logger LOG = LoggerFactory.getLogger(Customer.class);
  // A globally unique UUID for the customer.
  @Column(nullable = false, unique = true)
  @ApiModelProperty(value = "Customer uuid", accessMode = READ_ONLY)
  public UUID uuid = UUID.randomUUID();

  public void setUuid(UUID uuid) {
    this.uuid = uuid;
  }

  public UUID getUuid() {
    return uuid;
  }

  // An auto incrementing, user-friendly id for the customer. Used to compose a db prefix. Currently
  // it is assumed that there is a single instance of the db. The id space for this field may have
  // to be partitioned in case the db is being sharded.
  // Use IDENTITY strategy because `customer.id` is a `bigserial` type; not a sequence.
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @ApiModelProperty(value = "Customer id", accessMode = READ_ONLY)
  private Long id;

  @ApiModelProperty(value = "Customer id", accessMode = READ_ONLY, example = "1")
  public Long getCustomerId() {
    return id;
  }

  @Column(length = 15, nullable = false)
  @Constraints.Required
  @ApiModelProperty(value = "Customer code", example = "admin", required = true)
  public String code;

  @Column(length = 256, nullable = false)
  @Constraints.Required
  @Constraints.MinLength(3)
  @ApiModelProperty(value = "Name of customer", example = "sridhar", required = true)
  public String name;

  @Column(nullable = false)
  @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
  @ApiModelProperty(
      value = "Creation time",
      example = "2021-06-17 15:00:05",
      accessMode = READ_ONLY)
  public Date creationDate;

  // To be replaced with runtime config
  @Column(nullable = true, columnDefinition = "TEXT")
  @ApiModelProperty(value = "UI_ONLY", hidden = true, accessMode = READ_ONLY)
  private JsonNode features;

  @Column(columnDefinition = "TEXT", nullable = false)
  @ApiModelProperty(
      value = "Universe UUIDs",
      accessMode = READ_ONLY,
      example = "[c3595ca7-68a3-47f0-b1b2-1725886d5ed5, 9e0bb733-556c-4935-83dd-6b742a2c32e6]")
  private String universeUUIDs = "";

  public synchronized void addUniverseUUID(UUID universeUUID) {
    Set<UUID> universes = getUniverseUUIDs();
    universes.add(universeUUID);
    universeUUIDs = Joiner.on(",").join(universes);
    LOG.trace("New universe list for customer [" + name + "] : " + universeUUIDs);
  }

  public synchronized void removeUniverseUUID(UUID universeUUID) {
    Set<UUID> universes = getUniverseUUIDs();
    universes.remove(universeUUID);
    universeUUIDs = Joiner.on(",").join(universes);
    LOG.trace("New universe list for customer [" + name + "] : " + universeUUIDs);
  }

  public Set<UUID> getUniverseUUIDs() {
    Set<UUID> uuids = new HashSet<UUID>();
    if (!universeUUIDs.isEmpty()) {
      String[] ids = universeUUIDs.split(",");
      for (String id : ids) {
        uuids.add(UUID.fromString(id));
      }
    }
    return uuids;
  }

  @JsonIgnore
  public Set<Universe> getUniverses() {
    if (getUniverseUUIDs().isEmpty()) {
      return new HashSet<>();
    }
    return Universe.getAllPresent(getUniverseUUIDs());
  }

  @JsonIgnore
  public Set<Universe> getUniversesForProvider(UUID providerUUID) {
    Set<Universe> universesInProvider =
        getUniverses()
            .stream()
            .filter(u -> checkClusterInProvider(u, providerUUID))
            .collect(Collectors.toSet());
    return universesInProvider;
  }

  private boolean checkClusterInProvider(Universe universe, UUID providerUUID) {
    for (Cluster cluster : universe.getUniverseDetails().clusters) {
      if (cluster.userIntent.provider.equals(providerUUID.toString())) {
        return true;
      }
    }
    return false;
  }

  public static final Finder<UUID, Customer> find = new Finder<UUID, Customer>(Customer.class) {};

  public static Customer getOrBadRequest(UUID customerUUID) {
    Customer customer = get(customerUUID);
    if (customer == null) {
      throw new PlatformServiceException(BAD_REQUEST, "Invalid Customer UUID:" + customerUUID);
    }
    return customer;
  }

  public static Customer get(UUID customerUUID) {
    return find.query().where().eq("uuid", customerUUID).findOne();
  }

  public static Customer get(long id) {
    return find.query().where().idEq(String.valueOf(id)).findOne();
  }

  public static List<Customer> getAll() {
    return find.query().findList();
  }

  public Customer() {
    this.creationDate = new Date();
  }

  /** Create new customer, we encrypt the password before we store it in the DB */
  public static Customer create(String code, String name) {
    Customer cust = new Customer();
    cust.code = code;
    cust.name = name;
    cust.creationDate = new Date();
    cust.save();
    return cust;
  }

  /** Get features for this customer. */
  public JsonNode getFeatures() {
    return features == null ? Json.newObject() : features;
  }

  /**
   * Upserts features for this customer. If updating a feature, only specified features will be
   * updated.
   */
  public void upsertFeatures(JsonNode input) {
    if (!input.isObject()) {
      throw new PlatformServiceException(BAD_REQUEST, "Features must be Jsons.");
    } else if (features == null || features.isNull() || features.size() == 0) {
      features = input;
    } else {
      deepMerge(features, input);
    }
    save();
  }

  @JsonIgnore
  public String getTag() {
    return String.format("[%s][%s]", name, code);
  }
}
