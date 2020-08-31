/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.sos;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataEncoding;
import org.sensorhub.api.persistence.IObsFilter;
import org.sensorhub.api.persistence.IObsStorage;
import org.sensorhub.api.persistence.IRecordStoreInfo;
import org.sensorhub.api.persistence.ObsFilter;
import org.sensorhub.api.persistence.ObsPeriod;
import org.vast.ogc.om.IObservation;
import org.vast.ows.sos.SOSException;
import org.vast.swe.SWEConstants;
import org.vast.swe.SWEHelper;
import com.vividsolutions.jts.geom.Polygon;


/**
 * <p>
 * Implementation of SOS data provider connecting to a storage via 
 * SensorHub's persistence API (ITimeSeriesStorage and derived classes)
 * </p>
 *
 * @author Alex Robin <alex.robin@sensiasoftware.com>
 * @since Sep 7, 2013
 */
public class StorageFoiTimePeriodsProvider implements ISOSDataProvider
{
    static final String DEF_FOI_URI = SWEHelper.getPropertyUri("FeatureOfInterestUID");
    SWEHelper swe = new SWEHelper();
    IObsStorage storage;
    List<IObsFilter> dataStoreFilters;
    Iterator<ObsPeriod> obsPeriods;
    DataComponent dataStruct;
    DataEncoding dataEncoding;
    DataBlock latestRecord;
    
    
    public StorageFoiTimePeriodsProvider(IObsStorage storage, StorageDataProviderConfig config, final SOSDataFilter filter) throws SOSException
    {
        this.storage = storage;
        this.dataStoreFilters = new ArrayList<>();
        
        // build record structure and encoding
        this.dataStruct = swe.createDataRecord()
            .addField("foi", swe.createText()
                .definition(DEF_FOI_URI)
                .label("Feature of Interest ID")
                .description("Unique ID of feature of interest")
                .build())
            .addField("timeRange", swe.createTimeRange()
                .asPhenomenonTimeIsoUTC()
                .label("Phenomenon Time Range")
                .description("Time range during which observations of this feature of interest are available")
                .build())
            .build();
        this.dataEncoding = swe.newTextEncoding();
        
        filter.getObservables().add(DEF_FOI_URI);
        filter.getObservables().add(SWEConstants.DEF_PHENOMENON_TIME);
        
        // prepare time range filter
        final double[] timePeriod;
        if (filter.getTimeRange() != null && !filter.getTimeRange().isNull())
        {
            // special case if requesting latest records
            if (filter.getTimeRange().isBaseAtNow())
            {
                timePeriod = new double[] {
                    Double.POSITIVE_INFINITY,
                    Double.POSITIVE_INFINITY
                };
            }
            else
            {
                timePeriod = new double[] {
                    filter.getTimeRange().getStartTime(),
                    filter.getTimeRange().getStopTime()
                };
            }
        }
        else
            timePeriod = null;
        
        // loop through all outputs
        for (Entry<String, ? extends IRecordStoreInfo> dsEntry: storage.getRecordStores().entrySet())
        {
            // skip excluded outputs
            if (config.excludedOutputs != null && config.excludedOutputs.contains(dsEntry.getKey()))
                continue;
            
            IRecordStoreInfo recordInfo = dsEntry.getValue();
            String recordType = recordInfo.getName();
            
            // prepare record filter
            IObsFilter storageFilter = new ObsFilter(recordType) {
                public double[] getTimeStampRange() { return timePeriod; }
                public Set<String> getFoiIDs() { return filter.getFoiIds(); }
                public Polygon getRoi() {return filter.getRoi(); }
            };
            
            dataStoreFilters.add(storageFilter);            
        }
    }
    
    
    @Override
    public IObservation getNextObservation() throws IOException
    {
        DataBlock rec = getNextResultRecord();
        if (rec == null)
            return null;
        
        return buildObservation(rec);
    }
    
    
    protected IObservation buildObservation(DataBlock rec) throws IOException
    {
        getResultStructure().setData(rec);
        String foiID = rec.getStringValue(0);
        return SOSProviderUtils.buildObservation(getResultStructure(), foiID, storage.getLatestDataSourceDescription().getUniqueIdentifier());
    }
    

    @Override
    public DataBlock getNextResultRecord() throws IOException
    {   
        if (obsPeriods == null)
            obsPeriods = fetchResults();
        
        if (obsPeriods == null || !obsPeriods.hasNext())
            return null;
        ObsPeriod obsPeriod = obsPeriods.next();
                
        if (latestRecord == null)
            latestRecord = dataStruct.createDataBlock();
        else
            latestRecord = latestRecord.renew();
        
        latestRecord.setStringValue(0, obsPeriod.foiID);
        latestRecord.setDoubleValue(1, obsPeriod.begin);
        latestRecord.setDoubleValue(2, obsPeriod.end);
        return latestRecord;
    }
    
    
    Iterator<ObsPeriod> fetchResults()
    {
        for (IObsFilter filter: dataStoreFilters)
        {
            obsPeriods = ((IObsStorage)storage).getFoiTimeRanges(filter);
            return obsPeriods;
        }
        
        return null;
    }
    

    @Override
    public DataComponent getResultStructure() throws IOException
    {
        return dataStruct;
    }
    

    @Override
    public DataEncoding getDefaultResultEncoding() throws IOException
    {
        return dataEncoding;
    }


    @Override
    public void close()
    {
    }

}
