/***************************** BEGIN LICENSE BLOCK ***************************
 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 Copyright (C) 2024 Botts Innovative Research Inc. All Rights Reserved.
 ******************************* END LICENSE BLOCK ***************************/
package org.sensorhub.impl.sensor;

import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataEncoding;
import org.sensorhub.api.sensor.ISensorDriver;

/**
 * Default orientation output for sensor drivers outputting their own orientation.
 */
public abstract class DefaultOrientationOutput extends AbstractSensorOutput<ISensorDriver> {
    protected DataComponent outputStruct;
    DataEncoding outputEncoding;
    protected double updatePeriod;

    protected DefaultOrientationOutput(ISensorDriver parentSensor, double updatePeriod) {
        super(AbstractSensorModule.ORIENTATION_OUTPUT_NAME, parentSensor);
        this.updatePeriod = updatePeriod;
    }

    @Override
    public DataComponent getRecordDescription() {
        return outputStruct;
    }

    @Override
    public DataEncoding getRecommendedEncoding() {
        return outputEncoding;
    }

    @Override
    public double getAverageSamplingPeriod() {
        return updatePeriod;
    }

    /**
     * Update the orientation output with the given heading, pitch, and roll angles.
     *
     * @param time        The time of the orientation update.
     * @param heading     The heading angle in degrees.
     * @param pitch       The pitch angle in degrees.
     * @param roll        The roll angle in degrees.
     * @param forceUpdate If true, the orientation will be updated even if the angles have not changed.
     */
    public abstract void updateOrientation(double time, double heading, double pitch, double roll, boolean forceUpdate);
}