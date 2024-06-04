/***************************** BEGIN LICENSE BLOCK ***************************
 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 ******************************* END LICENSE BLOCK ***************************/
package org.sensorhub.impl.sensor;

import net.opengis.swe.v20.DataBlock;
import org.sensorhub.api.data.DataEvent;
import org.sensorhub.api.sensor.ISensorDriver;
import org.vast.swe.SWEConstants;
import org.vast.swe.helper.GeoPosHelper;

/**
 * Default orientation output with heading, pitch, and roll angles.
 */
public class DefaultOrientationOutputEuler extends DefaultOrientationOutput {
    public DefaultOrientationOutputEuler(ISensorDriver parentSensor, String sensorFrameID, double updatePeriod) {
        super(parentSensor, updatePeriod);

        GeoPosHelper fac = new GeoPosHelper();

        outputStruct = fac.createRecord()
                .label("Sensor Orientation")
                .addSamplingTimeIsoUTC("time")
                .addField("orientation", fac.createVector()
                        .from(fac.newEulerOrientationNED(SWEConstants.DEF_SENSOR_ORIENT))
                        .localFrame('#' + sensorFrameID))
                .build();

        outputStruct.setName(getName());
        outputStruct.setId(AbstractSensorModule.ORIENTATION_OUTPUT_ID);
        outputEncoding = fac.newTextEncoding();
    }

    @Override
    public void updateOrientation(double time, double heading, double pitch, double roll, boolean forceUpdate) {
        // Build new DataBlock
        DataBlock dataBlock = (latestRecord == null) ? outputStruct.createDataBlock() : latestRecord.renew();
        dataBlock.setDoubleValue(0, time);
        dataBlock.setDoubleValue(1, heading);
        dataBlock.setDoubleValue(2, pitch);
        dataBlock.setDoubleValue(3, roll);

        var changed = forceUpdate || latestRecord == null ||
                latestRecord.getDoubleValue(1) != dataBlock.getDoubleValue(1) ||
                latestRecord.getDoubleValue(2) != dataBlock.getDoubleValue(2) ||
                latestRecord.getDoubleValue(3) != dataBlock.getDoubleValue(3);

        // If the location has actually changed, update the latest record and send event
        if (changed) {
            latestRecord = dataBlock;
            latestRecordTime = System.currentTimeMillis();
            eventHandler.publish(new DataEvent(latestRecordTime, this, dataBlock));
        }
    }
}
