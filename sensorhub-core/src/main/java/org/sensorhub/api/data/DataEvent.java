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
import org.sensorhub.api.event.EventUtils;
import org.vast.util.Asserts;
import net.opengis.swe.v20.DataBlock;


/**
 * <p>
 * Type of event generated when new data is available from a data producer.
 * It is immutable and carries data by reference.
 * </p>
 *
 * @author Alex Robin
 * @since Feb 20, 2015
 */
public class DataEvent extends DataStreamEvent
{
    protected String foiUID;
    protected Instant resultTime;
    protected DataBlock[] records;


    /**
     * Constructs a data event associated to a specific system and channel
     * @param timeStamp Time of event generation (unix time in milliseconds, base 1970)
     * @param sysUID Unique ID of system that produced the data records
     * @param outputName Name of the output interface that generated the data
     * @param records Array of data records that triggered this event
     */
    public DataEvent(long timeStamp, String sysUID, String outputName, DataBlock ... records)
    {
        super(timeStamp, sysUID, outputName);
        Asserts.checkNotNullOrEmpty(records, "records must be provided");
        this.records = records;
    }


    /**
     * Helper constructor to construct a data event associated to the system
     * that is the parent of the given data interface
     * @param timeStamp Time of event generation (unix time in milliseconds, base 1970)
     * @param dataInterface Stream interface that generated the associated data
     * @param records Array of records that triggered this event
     */
    public DataEvent(long timeStamp, IStreamingDataInterface dataInterface, DataBlock ... records)
    {
        this(timeStamp,
             dataInterface.getParentProducer().getUniqueIdentifier(),
             dataInterface.getName(),
             records);
        this.source = dataInterface;
        
        // infer FOI in case there is only one
        var fois = dataInterface.getParentProducer().getCurrentFeaturesOfInterest();
        if (fois.size() == 1)
            this.foiUID = fois.keySet().stream().findFirst().orElse(null);
    }


    /**
     * Helper constructor to construct a data event associated to the system
     * that is the parent of the given data interface
     * @param timeStamp Time of event generation (unix time in milliseconds, base 1970)
     * @param dataInterface Stream interface that generated the associated data
     * @param foiUID Unique ID of feature of interest that this data applies to
     * @param records Array of records that triggered this event
     */
    public DataEvent(long timeStamp, IStreamingDataInterface dataInterface, String foiUID, DataBlock ... records)
    {
        this(timeStamp,
             dataInterface.getParentProducer().getUniqueIdentifier(),
             dataInterface.getName(),
             foiUID,
             records);
        this.source = dataInterface;
    }


    /**
     * Helper constructor to construct a data event associated to the system
     * that is the parent of the given data interface
     * @param timeStamp Time of event generation (unix time in milliseconds, base 1970)
     * @param dataInterface Stream interface that generated the associated data
     * @param resultTime Time at which the data was generated (e.g. model run time)
     * @param foiUID Unique ID of feature of interest that this data applies to
     * @param records Array of records that triggered this event
     */
    public DataEvent(long timeStamp, IStreamingDataInterface dataInterface, Instant resultTime, String foiUID, DataBlock ... records)
    {
        this(timeStamp,
             dataInterface.getParentProducer().getUniqueIdentifier(),
             dataInterface.getName(),
             resultTime,
             foiUID,
             records);
        this.source = dataInterface;
    }


    /**
     * Constructs a data event associated to a specific system, channel and FOI
     * @param timeStamp Time of event generation (unix time in milliseconds, base 1970)
     * @param sysUID Unique ID of system that produced the data records
     * @param outputName Name of the output interface that generated the data
     * @param foiUID Unique ID of feature of interest that this data applies to
     * @param records Array of data records that triggered this event
     */
    public DataEvent(long timeStamp, String sysUID, String outputName, String foiUID, DataBlock ... records)
    {
        this(timeStamp, sysUID, outputName, records);
        this.foiUID = foiUID;
    }


    /**
     * Constructs a data event associated to a specific system and channel,
     * and tagged by a specific result time.
     * @param timeStamp Time of event generation (unix time in milliseconds, base 1970)
     * @param sysUID Unique ID of system that produced the data records
     * @param outputName Name of the output interface that generated the data
     * @param resultTime Time at which the data was generated (e.g. model run time)
     * @param records Array of data records that triggered this event
     */
    public DataEvent(long timeStamp, String sysUID, String outputName, Instant resultTime, DataBlock ... records)
    {
        this(timeStamp, sysUID, outputName, records);
        this.resultTime = resultTime;
    }


    /**
     * Constructs a data event associated to a specific system, channel and FOI,
     * and tagged by a specific result time.
     * @param timeStamp Time of event generation (unix time in milliseconds, base 1970)
     * @param sysUID Unique ID of system that produced the data records
     * @param outputName Name of the output interface that generated the data
     * @param resultTime Time at which the data was generated (e.g. model run time)
     * @param foiUID Unique ID of feature of interest that this data applies to
     * @param records Array of data records that triggered this event
     */
    public DataEvent(long timeStamp, String sysUID, String outputName, Instant resultTime, String foiUID, DataBlock ... records)
    {
        this(timeStamp, sysUID, outputName, records);
        this.resultTime = resultTime;
        this.foiUID = foiUID;
    }


    @Override
    public IStreamingDataInterface getSource()
    {
        return (IStreamingDataInterface)this.source;
    }


    /**
     * @return The time at which the data records were generated by the system
     */
    public Instant getResultTime()
    {
        return resultTime;
    }


    /**
     * @return Unique ID of feature of interest that this data applies to
     */
    public String getFoiUID()
    {
        return foiUID;
    }


    /**
     * @return List of data records attached to this event.<br/>
     * Multiple records can be associated to a single event because with high
     * rate or batch producers (e.g. models), it is often not practical or a
     * waste of resources to generate an event for every single record of measurements.
     * Note that all records share the same system, foi and result time.
     */
    public DataBlock[] getRecords()
    {
        return records;
    }


    @Override
    public String getSourceID()
    {
        if (sourceID == null)
            sourceID = EventUtils.getDataStreamDataTopicID(systemUID, outputName);
        return sourceID;
    }
}
