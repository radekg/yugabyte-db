// Copyright (c) YugaByte, Inc.
//
// Author: Gaurav Raj(gauraraj@deloitte.com)
//
// This file will hold Universe alert creation and Platform
// alert creation.

import { Field, reduxForm, FieldArray } from 'redux-form';
import React, { useState, useEffect } from 'react';
import { Col, Row } from 'react-bootstrap';
import {
  YBButton,
  YBMultiSelectWithLabel,
  YBSelectWithLabel,
  YBTextArea,
  YBTextInputWithLabel
} from '../../common/forms/fields';
import { connect } from 'react-redux';
import '../CreateAlerts.scss';
import { AlertsPolicy } from './AlertsPolicy';
import { isNonEmptyArray } from '../../../utils/ObjectUtils';

const required = (value) => (value ? undefined : 'This field is required.');

const CreateAlert = (props) => {
  const {
    enablePlatformAlert,
    alertUniverseList,
    onCreateCancel,
    handleSubmit,
    initialValues,
    metricsData,
    setInitialValues,
    createAlertConfig,
    alertDestinations,
    updateAlertConfig
  } = props;
  const [isAllUniversesDisabled, setIsAllUniversesDisabled] = useState(initialValues.ALERT_TARGET_TYPE==='allUniverses');
  const [alertDestination, setAlertDestination] = useState([]);
  const [currentMetric, setCurrentMetric] = useState({thresholdUnit: initialValues.thresholdUnit,template: initialValues.ALERT_METRICS_CONDITION});

  useEffect(() => {
    alertDestinations().then((res) => {
      res = res.map((destination, index) => (
        <option key={index} value={destination.uuid}>
          {destination.name}
        </option>
      ));
      setAlertDestination([<option key="i" value=''>Select Destination</option>, ...res]);
    });
  }, [alertDestinations]);

  /**
   * Constant option for metrics condition.
   */
  const alertMetricsConditionList = [
    <option key="default" value={null}>Select Metric</option>,
    ...metricsData.map((metric, i) => {
      return (
        <option key={i} value={metric.template}>
          {metric.name}
        </option>
      );
    })
  ];

  /**
   *
   * @param {Event} Event
   * Disable universe list dropdown and clear all the selection
   */
  const handleTargetTypeChange = (event) => {
    const value = event.target?.value;
    if (value === 'allUniverses') {
      setIsAllUniversesDisabled(true);
      props.updateField('alertConfigForm', 'ALERT_UNIVERSE_LIST', []);
    } else {
      setIsAllUniversesDisabled(false);
    }
  };

  /**
   * This method is used to set up the thresshold unit.
   *
   * @param {string} value Metric.
   */
  const handleMetricConditionChange = (value) => {
    const metric = metricsData.find((metric) => metric.template === value);
    setCurrentMetric(metric);
    if (metric && metric.template === initialValues.ALERT_METRICS_CONDITION) {
      props.updateField(
        'alertConfigForm',
        'ALERT_METRICS_CONDITION_POLICY',
        initialValues.ALERT_METRICS_CONDITION_POLICY
      );
    } else {
      const conditions = [];
      // Setting up the threshold values.
      Object.keys(metric.thresholds).forEach((policy) => {
        conditions.push({
          _SEVERITY: policy,
          _CONDITION: metric.thresholds[policy].condition,
          _THRESHOLD: metric.thresholds[policy].threshold
        });
      });
      props.updateField('alertConfigForm', 'ALERT_METRICS_CONDITION_POLICY', conditions);
    }
  };

  /**
   *
   * @param {Formvalues} values
   * TODO: Make an API call to submit the form by reformatting the payload.
   */
  const handleOnSubmit = (values) => {
    const cUUID = localStorage.getItem('customerId');
    const payload = {
      uuid: values.type === 'update' ? values.uuid : null,
      customerUUID: cUUID,
      createTime: values.type === 'update' ? initialValues.createTime : null,
      name: values['ALERT_CONFIGURATION_NAME'],
      description: values['ALERT_CONFIGURATION_DESCRIPTION'],
      targetType: !enablePlatformAlert ? 'UNIVERSE' : 'PLATFORM',
      target: !enablePlatformAlert
        ? {
          all: isNonEmptyArray(values['ALERT_UNIVERSE_LIST']) ? false : true,
          uuids: isNonEmptyArray(values['ALERT_UNIVERSE_LIST']) ? [] : null
        }
        : { all: true },
      thresholds: '',
      thresholdUnit: '',
      template: values['ALERT_METRICS_CONDITION'] || 'REPLICATION_LAG',
      durationSec: values['ALERT_METRICS_DURATION'],
      active: true,
      destinationUUID: values['ALERT_DESTINATION_LIST']
    };

    // setting up the thresshold unit.
    switch (values['ALERT_METRICS_CONDITION']) {
      case 'MEMORY_CONSUMPTION':
        payload.thresholdUnit = 'PERCENT';
        break;
      case 'CLOCK_SKEW':
        payload.thresholdUnit = 'MILLISECOND';
        break;
      default:
        payload.thresholdUnit = currentMetric.thresholdUnit;
    }

    // Setting up the universe uuids.
    isNonEmptyArray(values['ALERT_UNIVERSE_LIST']) &&
      values['ALERT_UNIVERSE_LIST'].forEach((list) => payload.target.uuids.push(list.value));

    // Setting up the threshold values.
    isNonEmptyArray(values['ALERT_METRICS_CONDITION_POLICY']) &&
      values['ALERT_METRICS_CONDITION_POLICY'].forEach((policy) => {
        payload.thresholds = Object.assign(
          { [policy._SEVERITY]: { condition: policy._CONDITION, threshold: policy._THRESHOLD } },
          payload.thresholds
        );
      });

    values.type === 'update'
      ? updateAlertConfig(payload, values.uuid).then(() => onCreateCancel(false))
      : createAlertConfig(payload).then(() => onCreateCancel(false));
  };

  const targetOptions = [
    { label: 'All Universes', value: 'allUniverses' },
    { label: 'Selected Universes', value: 'selectedUniverses' }
  ];

  return (
    <form name="alertConfigForm" onSubmit={handleSubmit(handleOnSubmit)}>
      <Row className="config-section-header">
        <Row>
          <Col md={6}>
            <div className="form-item-custom-label">Name</div>
            <Field
              name="ALERT_CONFIGURATION_NAME"
              placeHolder="Enter an alert name"
              component={YBTextInputWithLabel}
              validate={required}
              isReadOnly={false}
            />
          </Col>
          <Col md={6}>
            <div className="form-item-custom-label">Description</div>
            <Field
              name="ALERT_CONFIGURATION_DESCRIPTION"
              placeHolder="Enter an alert description"
              component={YBTextArea}
              isReadOnly={false}
            />
          </Col>
        </Row>
        {!enablePlatformAlert && (
          <Row>
            <Col md={6}>
              <div className="form-item-custom-label">Target</div>
              {targetOptions.map((target) => (
                <label className="btn-group btn-group-radio" key={target.value}>
                  <Field
                    name="ALERT_TARGET_TYPE"
                    component="input"
                    onChange={handleTargetTypeChange}
                    type="radio"
                    value={target.value}
                  />{' '}
                  {target.label}
                </label>
              ))}
              <Field
                name="ALERT_UNIVERSE_LIST"
                component={YBMultiSelectWithLabel}
                options={alertUniverseList}
                hideSelectedOptions={false}
                isMulti={true}
                validate={!isAllUniversesDisabled && required}
                className={isAllUniversesDisabled ? 'hide-field' : ''}
              />
            </Col>
          </Row>
        )}
        <hr />
        <Row>
          <Col md={12}>
            <h4>Conditions</h4>
          </Col>
          <Row>
            <Col md={6}>
              <div className="form-item-custom-label">Metric</div>
              <Field
                name="ALERT_METRICS_CONDITION"
                component={YBSelectWithLabel}
                validate={required}
                options={alertMetricsConditionList}
                onInputChanged={handleMetricConditionChange}
              />
            </Col>
            <Col md={3}>
              <div className="form-item-custom-label">Duration</div>
              <Field
                name="ALERT_METRICS_DURATION"
                component={YBTextInputWithLabel}
                validate={required}
                placeHolder="Enter duration in minutes"
              />
            </Col>
          </Row>
          <Row>
            <Col md={12}>
              <div className="form-field-grid">
                <FieldArray
                  name="ALERT_METRICS_CONDITION_POLICY"
                  component={AlertsPolicy}
                  props={{ currentMetric: currentMetric }}
                />
              </div>
            </Col>
          </Row>
        </Row>
        <Row className="actionBtnsMargin">
          <Col md={6}>
            <div className="form-item-custom-label">Destination</div>
            <Field
              name="ALERT_DESTINATION_LIST"
              component={YBSelectWithLabel}
              options={alertDestination}
            />
          </Col>
        </Row>
        <Row className="alert-action-button-container">
          <Col lg={6} lgOffset={6}>
            <YBButton
              btnText="Cancel"
              btnClass="btn"
              onClick={() => {
                onCreateCancel(false);
                setInitialValues();
              }}
            />
            <YBButton btnText="Save" btnType="submit" btnClass="btn btn-orange" />
          </Col>
        </Row>
      </Row>
    </form>
  );
};

const mapStateToProps = (state, ownProps) => {
  return {
    initialValues: { ...ownProps.initialValues }
  };
};

export default connect(mapStateToProps)(
  reduxForm({
    form: 'alertConfigForm',
    enableReinitialize: true,
    keepDirtyOnReinitialize: true
  })(CreateAlert)
);
