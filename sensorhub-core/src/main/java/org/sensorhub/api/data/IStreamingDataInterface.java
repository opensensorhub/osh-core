/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.api.data;

import java.time.Instant;
import org.sensorhub.api.event.IEventProducer;
import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataEncoding;


/**
 * <p>
 * Output interface used by data producers using the SWE model to describe
 * structure and encoding of data they generate (e.g. sensors, processes...)
 * </p>
 *
 * @author Alex Robin
 * @since Feb 21, 2015
 */
public interface IStreamingDataInterface extends IEventProducer
{

    /**
     * Allows by-reference access to parent producer
     * @return parent producer instance
     */
    public IDataProducer getParentProducer();
    
    
    /**
     * Gets this output interface name.
     * <p><i>It MUST be the name reported in the map obtained by the getOutputs()
     * method of {@link org.sensorhub.api.data.IDataProducer}</i></p>
     * @return name of this data interface
     */
    public String getName();


    /**
     * Checks if this interface is enabled
     * @return true if interface is enabled, false otherwise
     */
    public boolean isEnabled();
    
    
    /**
     * Retrieves the definition of records produced by this interface.
     * <p><i>Note that this is usually sent by reference and MUST not be modified
     * by the caller. If you really need to modify it, first get an independent
     * copy using {@link net.opengis.swe.v20.DataComponent#copy()}</i></p>
     * @return a DataComponent object defining the structure of the produced data
     */
    public DataComponent getRecordDescription();


    /**
     * Provides the recommended encoding for records produced by this interface.
     * <p><i>Note that this is usually sent by reference and MUST not be modified
     * by the caller. If you really need to modify it, first get an independent
     * copy using {@link net.opengis.swe.v20.DataEncoding#copy()}</i></p>
     * @return recommended encoding description for the produced data
     */
    public DataEncoding getRecommendedEncoding();
    
    
    /**
     * Gets the latest record received on this data channel.
     * <p><i>Note that this does not trigger a new measurement but simply
     * retrieves the result of the last measurement made.</i></p>
     * @return the last measurement record or null if no data is available
     */
    public DataBlock getLatestRecord();
    
    
    /**
     * Used to check when the last record was produced.<br/>
     * This is usually set as system time when the output is sent, and must be
     * the same as the corresponding event time stamp. However, it does not usually
     * equal the sampling time stamp that is often sent with the data.
     * <p><i>Note: this method is useful to know if a record has been produced since the
     * last call to {@link #getLatestRecord}.</i></p>
     * @return time of last record as unix time (ms since 1970) or {@link Long#MIN_VALUE}
     * if no record is available yet
     */
    public long getLatestRecordTime();
    
    
    /**
     * Gets the average rate at which this interface produces data.<br/>
     * This can be {@link Double#NaN} if the data is not streamed regularly 
     * (e.g. case of a sensor/process that is triggered manually or by another
     * unpredictable external stimulus)
     * @return sampling period in seconds or {@link Double#NaN} if unknown
     */
    public double getAverageSamplingPeriod();
    
    
    /**
     * Gets the datastream valid time. If null is returned, the valid start time of the
     * parent system will be used, or if an older version of the same datastream already 
     * exists with the parent system time, current time will be used instead.
     * @return the timestamp to use for the valid start time of the datastream
     */
    public default Instant getValidStartTime()
    {
        return null;
    }
}
