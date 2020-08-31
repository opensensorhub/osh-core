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
import org.sensorhub.api.persistence.DataKey;
import org.sensorhub.api.persistence.IBasicStorage;
import org.sensorhub.api.persistence.IDataRecord;
import org.sensorhub.api.persistence.IObsFilter;
import org.sensorhub.api.persistence.IRecordStoreInfo;
import org.sensorhub.api.persistence.ObsFilter;
import org.sensorhub.api.persistence.ObsKey;
import org.vast.data.DataIterator;
import org.vast.ogc.om.IObservation;
import org.vast.ows.sos.SOSException;
import org.vast.swe.SWEConstants;
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
public class StorageDataProvider implements ISOSDataProvider
{
    private static final String TOO_MANY_OBS_MSG = "Too many observations requested. Please further restrict your filtering options";
    
    IBasicStorage storage;
    List<StorageState> dataStoresStates;
    DataKey lastRecordKey;
    
    // replay stuff 
    double replaySpeedFactor;
    double requestStartTime;
    long requestSystemTime;
    
    
    class StorageState
    {
        DataComponent recordStruct;
        DataEncoding recordEnc;
        IObsFilter filter;
        Iterator<? extends IDataRecord> recordIterator;
        IDataRecord nextRecord;
    }
    
    
    public StorageDataProvider(IBasicStorage storage, StorageDataProviderConfig config, final SOSDataFilter filter) throws SOSException
    {
        this.storage = storage;
        this.dataStoresStates = new ArrayList<>();
        this.replaySpeedFactor = filter.getReplaySpeedFactor();
        this.requestSystemTime = System.currentTimeMillis();
                        
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
            
            this.requestStartTime = timePeriod[0];
        }
        else
            timePeriod = null;
        
        // loop through all outputs and connect to the ones containing observables we need
        for (Entry<String, ? extends IRecordStoreInfo> dsEntry: storage.getRecordStores().entrySet())
        {
            // skip excluded outputs
            if (config.excludedOutputs != null && config.excludedOutputs.contains(dsEntry.getKey()))
                continue;
            
            IRecordStoreInfo recordInfo = dsEntry.getValue();
            String recordType = recordInfo.getName();
            
            // keep it if we can find one of the observables
            DataIterator it = new DataIterator(recordInfo.getRecordDescription());
            while (it.hasNext())
            {
                String defUri = it.next().getDefinition();
                if (filter.getObservables().contains(defUri))
                {
                    // prepare record filter
                    IObsFilter storageFilter = new ObsFilter(recordType) {
                        public double[] getTimeStampRange() { return timePeriod; }
                        public Set<String> getFoiIDs() { return filter.getFoiIds(); }
                        public Polygon getRoi() {return filter.getRoi(); }
                    };
                    
                    // check obs count is not too large
                    int obsCount = storage.getNumMatchingRecords(storageFilter, filter.getMaxObsCount());
                    if (obsCount > filter.getMaxObsCount())
                        throw new SOSException(SOSException.response_too_big_code, null, null, TOO_MANY_OBS_MSG);
                    
                    StorageState state = new StorageState();
                    state.recordStruct = recordInfo.getRecordDescription();
                    state.recordEnc = recordInfo.getRecommendedEncoding();
                    state.filter = storageFilter;
                    dataStoresStates.add(state);
                    
                    // break for now since currently we support only requesting data from one store at a time
                    // TODO support case of multiple stores since it is technically possible with GetObservation
                    break;
                }
            }
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
        
        // FOI
        String foiID = SWEConstants.NIL_UNKNOWN;
        if (lastRecordKey instanceof ObsKey)
            foiID = ((ObsKey)lastRecordKey).foiID;
                
        return SOSProviderUtils.buildObservation(getResultStructure(), foiID, storage.getLatestDataSourceDescription().getUniqueIdentifier());
    }
    

    @Override
    public DataBlock getNextResultRecord() throws IOException
    {
        double nextStorageTime = Double.POSITIVE_INFINITY;
        int nextStorageIndex = -1;
        
        // select data store with next earliest time stamp
        for (int i = 0; i < dataStoresStates.size(); i++)
        {
            StorageState state = dataStoresStates.get(i);
            
            // init iterator if needed
            if (state.recordIterator == null)
            {
                state.recordIterator = storage.getRecordIterator(state.filter);
                if (state.recordIterator.hasNext()) // prefetch first record
                    state.nextRecord = state.recordIterator.next();
            }
            
            if (state.nextRecord == null)
                continue;                
            
            double recTime = state.nextRecord.getKey().timeStamp;
            if (recTime < nextStorageTime)
            {
                nextStorageTime = recTime;
                nextStorageIndex = i;
            }
        }
        
        if (nextStorageIndex < 0)
            return null;
        
        // get datablock from selected data store 
        StorageState state = dataStoresStates.get(nextStorageIndex);
        IDataRecord nextRec = state.nextRecord;
        lastRecordKey = nextRec.getKey();
        DataBlock datablk = nextRec.getData();
                
        // prefetch next record
        if (state.recordIterator.hasNext())
            state.nextRecord = state.recordIterator.next();
        else
            state.nextRecord = null;
        
        // wait if replay mode is active
        if (!Double.isNaN(replaySpeedFactor))
        {
            long realEllapsedTime = System.currentTimeMillis() - requestSystemTime;
            long waitTime = (long)((nextStorageTime - requestStartTime) * 1000. / replaySpeedFactor) - realEllapsedTime;
            if (waitTime > 0)
            {
                try { Thread.sleep(waitTime ); }
                catch (InterruptedException e) { }
            }
        }
        
        // return record properly filtered according to selected observables
        return datablk;
    }
    

    @Override
    public DataComponent getResultStructure() throws IOException
    {
        // TODO generate choice if request includes several outputs
        
        return dataStoresStates.get(0).recordStruct;
    }
    

    @Override
    public DataEncoding getDefaultResultEncoding() throws IOException
    {
        return dataStoresStates.get(0).recordEnc;
    }


    @Override
    public void close()
    {
                
    }

}
