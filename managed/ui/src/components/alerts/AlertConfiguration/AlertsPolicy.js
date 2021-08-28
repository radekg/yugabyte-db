import React, { Component } from 'react';
import { Row, Col } from 'react-bootstrap';
import { Field } from 'redux-form';
import { YBInputField, YBSelect } from '../../common/forms/fields';
import '../CreateAlerts.scss';

const required = (value) => (value ? undefined : 'This field is required.');

export class AlertsPolicy extends Component {
  /**
   * Constant option for severity types
   * TODO: Source and values of actual list may differ.
   */
  severityTypes = [
    <option key={0}>Select Severity</option>,
    <option key={1} value="SEVERE">
      Severe
    </option>,
    <option key={2} value="WARNING">
      Warning
    </option>
  ];

  /**
   * Constant option for eqality type of policy.
   * TODO: Source and values of actual list may differ.
   */
  conditionTypes = [
    <option key={0}>Select Condition</option>,
    <option key={1} value="GREATER_THAN">
      Greater Than
    </option>,
    <option key={2} value="LESS_THAN">
      Less than
    </option>
  ];
  /**
   * Push an enpty object if field array is empty.
   */
  componentDidMount() {
    const { fields } = this.props;
    if (fields.length === 0) {
      this.props.fields.push({});
    }
  }

  /**
   * Add a new row in field array.
   * @param {Event} e
   */
  addRow = (e) => {
    this.props.fields.push({});
    e.preventDefault();
  };

  /**
   * Remove the element from array based on index.
   * @param {Number} instanceTypeIdx
   */
  removeRow = (instanceTypeIdx) => {
    this.props.fields.remove(instanceTypeIdx);
  };

  render() {
    const { fields, currentMetric } = this.props;

    let metricUnit = '';

    switch (currentMetric?.thresholdUnit) {
      case 'MILLISECOND':
        metricUnit = 'ms';
        break;
      case 'SECOND':
        metricUnit = 'sec';
        break;
      case 'DAY':
        metricUnit = 'day';
        break;
      case 'PERCENT':
        metricUnit = '%';
        break;
      default:
        metricUnit = '';
    }
    return (
      <div className="condition-row-container">
        <Row>
          <Col lg={2}>Severity</Col>
          <Col lg={2}>Condition</Col>
          <Col lg={5}>Threshold</Col>
        </Row>
        {fields.map((instanceTypeItem, instanceTypeIdx) => (
          <Row key={instanceTypeIdx}>
            <Col lg={2}>
              <Field
                name={`${instanceTypeItem}_SEVERITY`}
                component={YBSelect}
                insetError={true}
                validate={required}
                options={this.severityTypes}
              />
            </Col>
            <Col lg={2}>
              <Field
                name={`${instanceTypeItem}_CONDITION`}
                component={YBSelect}
                insetError={true}
                validate={required}
                options={this.conditionTypes}
              />
            </Col>
            <Col lg={1}>
              <Field
                name={`${instanceTypeItem}_THRESHOLD`}
                component={YBInputField}
                validate={required}
              />
            </Col>
            <Col lg={1}>
              <div className="flex-container">
                <p className="percent-text">{metricUnit}</p>
              </div>
            </Col>
            <Col lg={1}>
              {fields.length > 1 ? (
                <i
                  className="fa fa-minus-circle on-prem-row-delete-btn"
                  onClick={() => this.removeRow(instanceTypeIdx)}
                />
              ) : null}
            </Col>
          </Row>
        ))}
        <Row>
          <Col lg={2}>
            <a href="# " className="on-prem-add-link" onClick={this.addRow}>
              <i className="fa fa-plus-circle fa-2x on-prem-row-add-btn" onClick={this.addRow} />
              Add Severity
            </a>
          </Col>
        </Row>
      </div>
    );
  }
}
