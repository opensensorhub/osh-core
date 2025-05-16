/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2019 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.system;

import org.sensorhub.api.common.BigId;
import org.sensorhub.api.data.DataEvent;
import org.sensorhub.api.data.DataStreamChangedEvent;
import org.sensorhub.api.data.DataStreamDisabledEvent;
import org.sensorhub.api.data.DataStreamEnabledEvent;
import org.sensorhub.api.data.DataStreamEvent;
import org.sensorhub.api.data.DataStreamInfo;
import org.sensorhub.api.data.DataStreamRemovedEvent;
import org.sensorhub.api.data.IDataStreamInfo;
import org.sensorhub.api.data.IObsData;
import org.sensorhub.api.data.ObsData;
import org.sensorhub.api.data.ObsEvent;
import org.sensorhub.api.datastore.DataStoreException;
import org.sensorhub.api.datastore.obs.DataStreamKey;
import org.sensorhub.api.datastore.obs.IDataStreamStore;
import org.sensorhub.api.event.Event;
import org.sensorhub.api.event.EventUtils;
import org.sensorhub.api.event.IEventListener;
import org.sensorhub.api.event.IEventPublisher;
import org.sensorhub.utils.DataComponentChecks;
import org.sensorhub.utils.SWEDataUtils;
import org.vast.swe.SWEHelper;
import org.vast.swe.ScalarIndexer;
import org.vast.util.Asserts;
import com.google.common.cache.LoadingCache;
import net.opengis.swe.v20.DataBlock;


/**
 * <p>
 * Helper class for creating/updating/deleting datastreams and persisting 
 * observations in the associated datastore, as well as publishing the
 * corresponding events.
 * </p>
 *
 * @author Alex Robin
 * @date Dec 21, 2020
 */
public class DataStreamTransactionHandler implements IEventListener
{
    protected final SystemDatabaseTransactionHandler rootHandler;
    protected final DataStreamKey dsKey; // local DB key
    protected IDataStreamInfo dsInfo;
    protected IEventPublisher dataEventPublisher;
    protected ScalarIndexer timeStampIndexer;
    protected LoadingCache<String, BigId> foiUidToIdMap;
    
    
    /*
     * dsKey must always be the local DB key
     */
    DataStreamTransactionHandler(DataStreamKey dsKey, IDataStreamInfo dsInfo, SystemDatabaseTransactionHandler rootHandler)
    {
        this(dsKey, dsInfo, rootHandler.createFoiIdCache(), rootHandler);
    }
    
    
    /*
     * dsKey must always be the local DB key
     */
    DataStreamTransactionHandler(DataStreamKey dsKey, IDataStreamInfo dsInfo, LoadingCache<String, BigId> foiIdMap, SystemDatabaseTransactionHandler rootHandler)
    {
        this.dsKey = dsKey;
        this.dsInfo = dsInfo;
        this.timeStampIndexer = SWEHelper.getTimeStampIndexer(dsInfo.getRecordStructure());
        this.foiUidToIdMap = Asserts.checkNotNull(foiIdMap, "foiIdMap");
        this.rootHandler = rootHandler;
        
        getDataEventPublisher();
    }
    
    
    public boolean update(IDataStreamInfo dsInfo) throws DataStoreException
    {
        var oldDsInfo = this.dsInfo;
        if (oldDsInfo == null)
            return false;
        
        // check output name wasn't changed
        if (!dsInfo.getOutputName().equals(oldDsInfo.getOutputName()))
            throw new DataStoreException("The system output (outputName) associated to a datastream cannot be changed");
        
        // check structure hasn't changed if we already have observations
        var hasObs = oldDsInfo.getResultTimeRange() != null;
        if (hasObs &&
            (!DataComponentChecks.checkStructCompatible(oldDsInfo.getRecordStructure(), dsInfo.getRecordStructure()) ||
             !DataComponentChecks.checkEncodingEquals(oldDsInfo.getRecordEncoding(), dsInfo.getRecordEncoding())))
            throw new DataStoreException("Cannot update the record structure or encoding of a datastream if it already has observations");
        
        // check validTime hasn't changed if we already have observations
        var validTime = dsInfo.getValidTime();
        if (hasObs && validTime != null && !oldDsInfo.getValidTime().equals(validTime))
            throw new DataStoreException("Cannot update the datastream validTime if it already has observations");
        
        // update datastream info
        var newDsInfo = new DataStreamInfo.Builder()
            .withName(dsInfo.getName())
            .withDescription(dsInfo.getDescription())
            .withSystem(oldDsInfo.getSystemID())
            .withRecordDescription(dsInfo.getRecordStructure())
            .withRecordEncoding(dsInfo.getRecordEncoding())
            .withValidTime(validTime != null ? validTime : oldDsInfo.getValidTime())
            .build();
        getDataStreamStore().replace(dsKey, newDsInfo);
        this.dsInfo = newDsInfo;
        
        // send event
        var event = new DataStreamChangedEvent(newDsInfo);
        publishDataStreamEvent(event);
        
        return true;
    }
    
    
    public boolean delete() throws DataStoreException
    {
        var oldDsKey = getDataStreamStore().remove(dsKey);
        if (oldDsKey == null)
            return false;
        
        // if datastream was currently valid, disable it first
        if (dsInfo.getValidTime().endsNow())
            disable();
        
        // send event
        var event = new DataStreamRemovedEvent(dsInfo);
        publishDataStreamEvent(event);
        
        return true;
    }
    
    
    public void enable()
    {
        var event = new DataStreamEnabledEvent(dsInfo);
        publishDataStreamEvent(event);
    }
    
    
    public void disable()
    {
        var event = new DataStreamDisabledEvent(dsInfo);
        publishDataStreamEvent(event);
    }
    
    
    public BigId addObs(DataBlock rec)
    {
        // no checks since this is called at high rate
        //Asserts.checkNotNull(rec, DataBlock.class);
        
        return addObs(new DataEvent(
            System.currentTimeMillis(),
            dsInfo.getSystemID().getUniqueID(),
            dsInfo.getOutputName(),
            rec));
    }
    
    
    /**
     * Add all records attached to the event as observations
     * @param e
     * @return ID of last observation inserted
     */
    public BigId addObs(DataEvent e)
    {
        // no checks since this is called at high rate
        //Asserts.checkNotNull(e, DataEvent.class);
        //checkInitialized();
        
        // if event carries an FOI UID, try to fetch the full Id object
        var foiId = IObsData.NO_FOI;
        String foiUID = e.getFoiUID();
        if (foiUID != null)
        {
            foiId = foiUidToIdMap.getUnchecked(foiUID);
            if (foiId == null)
                throw new IllegalStateException("Unknown FOI: " + foiUID);
        }
        
        // process all records
        var obsArray = new IObsData[e.getRecords().length];
        for (int i = 0; i < obsArray.length; i++)
        {
            var rec = e.getRecords()[i];
            
            // get time stamp
            double time;
            if (timeStampIndexer != null)
                time = timeStampIndexer.getDoubleValue(rec);
            else
                time = e.getTimeStamp() / 1000.;
        
            // create obs
            ObsData obs = new ObsData.Builder()
                .withDataStream(dsKey.getInternalID())
                .withFoi(foiId)
                .withResultTime(e.getResultTime())
                .withPhenomenonTime(SWEDataUtils.toInstant(time))
                .withResult(rec)
                .build();
            
            obsArray[i] = obs;
        }
        
        // first forward to event bus to minimize latency
        getDataEventPublisher().publish(new ObsEvent(
            e.getTimeStamp(),
            e.getSystemUID(),
            e.getOutputName(),
            obsArray));
        
        // then add all obs to store
        BigId obsID = null;
        for (var obs: obsArray)
            obsID = rootHandler.db.getObservationStore().add(obs);
        
        return obsID;
    }
    
    
    public BigId addObs(IObsData obs)
    {
        // no checks since this is called at high rate
        //Asserts.checkNotNull(obs, IObsData.class);
        //checkInitialized();
        var timeStamp = System.currentTimeMillis();
        
        // first send to event bus to minimize latency
        getDataEventPublisher().publish(new ObsEvent(
            timeStamp,
            dsInfo.getSystemID().getUniqueID(),
            dsInfo.getOutputName(),
            obs));
        
        // add to store
        return rootHandler.db.getObservationStore().add(obs);
    }


    @Override
    public void handleEvent(Event e)
    {
        if (e instanceof DataEvent)
        {
            addObs((DataEvent)e);
        }
        else if (e instanceof ObsEvent)
        {
            for (var obs: ((ObsEvent)e).getObservations())
                addObs(obs);
        }
    }
    
    
    protected synchronized IEventPublisher getDataEventPublisher()
    {
        // create event publisher if needed
        // cache it because we need it often
        if (dataEventPublisher == null)
        {
            var topic = EventUtils.getDataStreamDataTopicID(dsInfo);
            dataEventPublisher = rootHandler.eventBus.getPublisher(topic);
        }
         
        return dataEventPublisher;
    }
    
    
    protected void publishDataStreamEvent(DataStreamEvent event)
    {
        String topic;
        
        // assign internal ID before event is dispatched
        event.assignSystemID(dsInfo.getSystemID().getInternalID());
        event.assignDataStreamID(dsKey.getInternalID());
        
        // publish on this datastream status channel
        topic = EventUtils.getDataStreamStatusTopicID(dsInfo);
        rootHandler.eventBus.getPublisher(topic).publish(event);
        
        // publish on system status channel
        var sysUid = dsInfo.getSystemID().getUniqueID();
        topic = EventUtils.getSystemStatusTopicID(sysUid);
        rootHandler.eventBus.getPublisher(topic).publish(event);
        
        // publish on parent systems status recursively
        //Long parentId = rootHandler.db.getSystemDescStore().getCurrentVersionKey(sysUid).getInternalID();
        var parentId = dsInfo.getSystemID().getInternalID();
        while ((parentId = rootHandler.db.getSystemDescStore().getParent(parentId)) != null)
        {
            sysUid = rootHandler.db.getSystemDescStore().getCurrentVersion(parentId).getUniqueIdentifier();
            topic = EventUtils.getSystemStatusTopicID(sysUid);
            rootHandler.eventBus.getPublisher(topic).publish(event);
        }
        
        // publish on systems root
        topic = EventUtils.getSystemRegistryTopicID();
        rootHandler.eventBus.getPublisher(topic).publish(event);
    }
    
    
    protected IDataStreamStore getDataStreamStore()
    {
        return rootHandler.db.getDataStreamStore();
    }
    
    
    public DataStreamKey getDataStreamKey()
    {
        return dsKey;
    }
    
    
    public IDataStreamInfo getDataStreamInfo()
    {
        return dsInfo;
    }
}
