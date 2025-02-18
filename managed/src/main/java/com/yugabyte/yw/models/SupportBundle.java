// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.models;

import static play.mvc.Http.Status.BAD_REQUEST;
import static play.mvc.Http.Status.INTERNAL_SERVER_ERROR;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.yugabyte.yw.common.PlatformServiceException;
import com.yugabyte.yw.common.Util;
import com.yugabyte.yw.models.helpers.BundleDetails;
import com.yugabyte.yw.forms.SupportBundleFormData;
import io.ebean.Finder;
import io.ebean.Model;
import io.ebean.annotation.DbEnumValue;
import io.ebean.annotation.DbJson;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.List;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Entity
public class SupportBundle extends Model {

  private static final Logger LOG = LoggerFactory.getLogger(SupportBundle.class);

  @Id
  @Column(nullable = false, unique = true)
  @Getter
  private UUID bundleUUID;

  @Column @Getter @Setter private String path;

  @Column(nullable = false)
  @Getter
  private UUID scopeUUID;

  @Column
  @Getter
  @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
  private Date startDate;

  @Column
  @Getter
  @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
  private Date endDate;

  @Column(nullable = true)
  @Getter
  @DbJson
  private BundleDetails bundleDetails;

  @Column(name = "status", nullable = false)
  @Getter
  @Setter
  private SupportBundleStatusType status;

  public enum SupportBundleStatusType {
    Running("Running"),
    Success("Success"),
    Failed("Failed");

    private final String status;

    private SupportBundleStatusType(String status) {
      this.status = status;
    }

    @DbEnumValue
    public String toString() {
      return this.status;
    }
  }

  public SupportBundle() {}

  public SupportBundle(
      UUID bundleUUID,
      UUID scopeUUID,
      String path,
      Date startDate,
      Date endDate,
      BundleDetails bundleDetails,
      SupportBundleStatusType status) {
    this.bundleUUID = bundleUUID;
    this.scopeUUID = scopeUUID;
    this.path = path;
    this.startDate = startDate;
    this.endDate = endDate;
    this.bundleDetails = bundleDetails;
    this.status = status;
  }

  @JsonIgnore
  public Path getPathObject() {
    return Paths.get(this.path);
  }

  public void setPathObject(Path path) {
    this.path = path.toString();
  }

  public static SupportBundle create(SupportBundleFormData bundleData, Universe universe) {
    SupportBundle supportBundle = new SupportBundle();
    supportBundle.bundleUUID = UUID.randomUUID();
    supportBundle.scopeUUID = universe.universeUUID;
    supportBundle.path = null;
    if (bundleData != null) {
      supportBundle.startDate = bundleData.startDate;
      supportBundle.endDate = bundleData.endDate;
      supportBundle.bundleDetails = new BundleDetails(bundleData.components);
    }
    supportBundle.status = SupportBundleStatusType.Running;
    supportBundle.save();
    return supportBundle;
  }

  public static final Finder<UUID, SupportBundle> find =
      new Finder<UUID, SupportBundle>(SupportBundle.class) {};

  public static SupportBundle getOrBadRequest(UUID bundleUUID) {
    SupportBundle bundle = get(bundleUUID);
    if (bundle == null) {
      throw new PlatformServiceException(BAD_REQUEST, "Invalid Bundle UUID:" + bundleUUID);
    }
    return bundle;
  }

  public static SupportBundle get(UUID bundleUUID) {
    return find.query().where().eq("bundle_uuid", bundleUUID).findOne();
  }

  public static List<SupportBundle> getAll() {
    List<SupportBundle> supportBundleList = find.query().findList();
    return supportBundleList;
  }

  public static InputStream getAsInputStream(UUID bundleUUID) {
    SupportBundle supportBundle = getOrBadRequest(bundleUUID);
    Path bundlePath = supportBundle.getPathObject();
    File file = bundlePath.toFile();
    InputStream is = Util.getInputStreamOrFail(file);
    return is;
  }

  @JsonIgnore
  public String getFileName() {
    Path bundlePath = this.getPathObject();
    if (bundlePath == null) {
      return null;
    }
    return bundlePath.getFileName().toString();
  }

  public static List<SupportBundle> getAll(UUID universeUUID) {
    List<SupportBundle> supportBundleList =
        find.query().where().eq("scope_uuid", universeUUID).findList();
    return supportBundleList;
  }

  public static void delete(UUID bundleUUID) {
    SupportBundle supportBundle = SupportBundle.getOrBadRequest(bundleUUID);
    if (supportBundle.getStatus() == SupportBundleStatusType.Running) {
      throw new PlatformServiceException(BAD_REQUEST, "The certificate is in running state.");
    } else {
      if (supportBundle.delete()) {
        LOG.info("Successfully deleted the db entry for support bundle: " + bundleUUID.toString());
      } else {
        throw new PlatformServiceException(
            INTERNAL_SERVER_ERROR, "Unable to delete the Support Bundle");
      }
    }
  }
}
