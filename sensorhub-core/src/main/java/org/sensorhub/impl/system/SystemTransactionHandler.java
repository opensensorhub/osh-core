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

import java.time.Instant;
import java.util.Map.Entry;
import java.util.Objects;
import org.sensorhub.api.command.CommandStreamAddedEvent;
import org.sensorhub.api.command.CommandStreamInfo;
import org.sensorhub.api.command.CommandStreamWithResultInfo;
import org.sensorhub.api.command.ICommandStreamInfo;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.data.DataStreamAddedEvent;
import org.sensorhub.api.data.DataStreamInfo;
import org.sensorhub.api.data.IDataStreamInfo;
import org.sensorhub.api.datastore.DataStoreException;
import org.sensorhub.api.datastore.command.CommandStreamFilter;
import org.sensorhub.api.datastore.command.CommandStreamKey;
import org.sensorhub.api.datastore.command.ICommandStreamStore;
import org.sensorhub.api.datastore.feature.FeatureKey;
import org.sensorhub.api.datastore.feature.FoiFilter;
import org.sensorhub.api.datastore.feature.IFoiStore;
import org.sensorhub.api.datastore.obs.DataStreamFilter;
import org.sensorhub.api.datastore.obs.DataStreamKey;
import org.sensorhub.api.datastore.obs.IDataStreamStore;
import org.sensorhub.api.datastore.system.ISystemDescStore;
import org.sensorhub.api.datastore.system.SystemFilter;
import org.sensorhub.api.event.EventUtils;
import org.sensorhub.api.feature.FoiAddedEvent;
import org.sensorhub.api.system.ISystemWithDesc;
import org.sensorhub.api.system.SystemAddedEvent;
import org.sensorhub.api.system.SystemChangedEvent;
import org.sensorhub.api.system.SystemId;
import org.sensorhub.api.system.SystemRemovedEvent;
import org.sensorhub.api.system.SystemDisabledEvent;
import org.sensorhub.api.system.SystemEnabledEvent;
import org.sensorhub.api.system.SystemEvent;
import org.sensorhub.api.utils.OshAsserts;
import org.sensorhub.impl.datastore.DataStoreUtils;
import org.sensorhub.utils.DataComponentChecks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vast.ogc.gml.IFeature;
import org.vast.util.Asserts;
import com.google.common.base.Strings;
import com.google.common.cache.LoadingCache;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataEncoding;


/**
 * <p>
 * Helper class for updating the state of a specific observing system.
 * This class handles database transactions and generate proper events when
 * system components are added, removed or modified.
 * </p>
 *
 * @author Alex Robin
 * @since Oct 4, 2021
 */
public class SystemTransactionHandler
{
    static final Logger log = LoggerFactory.getLogger(SystemTransactionHandler.class);
    
    protected final SystemDatabaseTransactionHandler rootHandler;
    protected final String sysUID;
    protected FeatureKey sysKey; // local DB key
    protected String parentGroupUID;
    protected LoadingCache<String, BigId> foiIdMap;
    protected boolean newlyCreated;
    
    
    /*
     * sysKey must always be the local DB key
     */
    SystemTransactionHandler(FeatureKey sysKey, String sysUID, SystemDatabaseTransactionHandler rootHandler)
    {
        this(sysKey, sysUID, null, rootHandler);
    }
    
    
    /*
     * sysKey must always be the local DB key
     */
    SystemTransactionHandler(FeatureKey sysKey, String sysUID, String parentGroupUID, SystemDatabaseTransactionHandler rootHandler)
    {
        this.sysKey = Asserts.checkNotNull(sysKey, FeatureKey.class);
        this.sysUID = OshAsserts.checkValidUID(sysUID);
        this.parentGroupUID = parentGroupUID;
        this.rootHandler = Asserts.checkNotNull(rootHandler);
        
        // prepare lazy loaded map of FOI UID to full FeatureId
        this.foiIdMap = rootHandler.createFoiIdCache();
    }
    
    
    public synchronized boolean update(ISystemWithDesc system) throws DataStoreException
    {
        OshAsserts.checkSystemObject(system);
        
        if (!getSystemDescStore().contains(system.getUniqueIdentifier()))
            return false;
        
        checkParent();
        
        var validTime = system.getFullDescription().getValidTime();
        if (validTime == null)
        {
            if (sysKey.getValidStartTime().equals(FeatureKey.TIMELESS))
                getSystemDescStore().put(sysKey, system);
            else
                throw new DataStoreException("A previous version of this system description had a valid time");
        }
        else if (sysKey.getValidStartTime().equals(validTime.begin()))
        {
            getSystemDescStore().put(sysKey, system);
        }
        else if (sysKey.getValidStartTime().isBefore(validTime.begin()))
        {
            sysKey = getSystemDescStore().add(system);
            publishSystemEvent(new SystemChangedEvent(sysUID));
        }
        else
            throw new DataStoreException("A version of the system description with a more recent valid time already exists");
        
        return true;
    }
    
    
    /**
     * Delete the system from persistent storage
     * @param cascade If true, delete all datastreams, control channels and
     * features of interest associated to the system
     * @return True if the system was successfully deleted, false otherwise
     * @throws DataStoreException
     */
    public boolean delete(boolean cascade) throws DataStoreException
    {
        checkParent();
        
        if (!cascade)
        {
            // error if associated datastreams still exist
            var dsFilter = new DataStreamFilter.Builder()
                .withSystems(sysKey.getInternalID())
                .build();
            if (getDataStreamStore().countMatchingEntries(dsFilter) > 0)
                throw new DataStoreException("System cannot be deleted because it is referenced by a datastream");
            
            // error if associated command streams still exist
            var csFilter = new CommandStreamFilter.Builder()
                .withSystems(sysKey.getInternalID())
                .build();
            if (getCommandStreamStore().countMatchingEntries(csFilter) > 0)
                throw new DataStoreException("System cannot be deleted because it is referenced by a command stream");
            
            // error if associated fois still exist
            var foiFilter = new FoiFilter.Builder()
                .withParents(sysKey.getInternalID())
                .build();
            if (getFoiStore().countMatchingEntries(foiFilter) > 0)
                throw new DataStoreException("System cannot be deleted because it has nested features of interest");
        }
        else
        {
            getDataStreamStore().removeEntries(new DataStreamFilter.Builder()
                .withSystems(new SystemFilter.Builder()
                    .withUniqueIDs(sysUID)
                    .build())
                .build());
            
            getCommandStreamStore().removeEntries(new CommandStreamFilter.Builder()
                .withSystems(new SystemFilter.Builder()
                    .withUniqueIDs(sysUID)
                    .build())
                .build());
            
            getFoiStore().removeEntries(new FoiFilter.Builder()
                .withParents(new SystemFilter.Builder()
                    .withUniqueIDs(sysUID)
                    .build())
                .build());
        }
        
        // remove from datastore
        var sysKey = getSystemDescStore().remove(sysUID);
        if (sysKey != null)
        {
            // send deleted event
            publishSystemEvent(new SystemRemovedEvent(sysUID, parentGroupUID));
            return true;
        }
        
        return false;
    }
    
    
    public void enable()
    {
        checkParent();
        publishSystemEvent(new SystemEnabledEvent(sysUID, parentGroupUID));
    }
    
    
    public void disable()
    {
        checkParent();
        publishSystemEvent(new SystemDisabledEvent(sysUID, parentGroupUID));
    }
    
    
    
    /*
     * Member helper methods
     */    
    
    public synchronized SystemTransactionHandler addMember(ISystemWithDesc system) throws DataStoreException
    {
        OshAsserts.checkSystemObject(system);
        
        var parentGroupUID = this.sysUID;
        var parentGroupID = this.sysKey.getInternalID();
        
        // add to datastore
        var memberKey = getSystemDescStore().add(parentGroupID, system);
        var memberUID = system.getUniqueIdentifier();
        
        // send event
        publishSystemEvent(new SystemAddedEvent(memberUID, parentGroupUID));
        
        // create the new system handler
        return createMemberHandler(memberKey, memberUID);
    }
    
    
    public synchronized SystemTransactionHandler addOrUpdateMember(ISystemWithDesc system) throws DataStoreException
    {
        var uid = OshAsserts.checkSystemObject(system);
        
        var memberKey = getSystemDescStore().getCurrentVersionKey(uid);
        if (memberKey != null &&
           (system.getValidTime() == null || Objects.equals(system.getValidTime().begin(), memberKey.getValidStartTime())))
        {
            var memberHandler = createMemberHandler(memberKey, uid);
            memberHandler.update(system);
            return memberHandler;
        }
        else
            return addMember(system);
    }
    
    
    public DataStreamTransactionHandler addOrUpdateDataStream(String outputName, DataComponent dataStruct, DataEncoding dataEncoding) throws DataStoreException
    {
        Asserts.checkNotNullOrEmpty(outputName, "outputName");
        Asserts.checkNotNull(dataStruct, DataComponent.class);
        Asserts.checkNotNull(dataEncoding, DataEncoding.class);
        
        // retrieve parent system name
        var sysName = getSystemDescStore().get(sysKey).getName();
        var dsName = Strings.isNullOrEmpty(dataStruct.getLabel()) ? outputName : dataStruct.getLabel();
        
        // create datastream info
        dataStruct.setName(outputName);
        var dsInfo = new DataStreamInfo.Builder()
            .withName(sysName + " - " + dsName)
            .withSystem(SystemId.NO_SYSTEM_ID)
            .withRecordDescription(dataStruct)
            .withRecordEncoding(dataEncoding)
            .build();
        
        return addOrUpdateDataStream(dsInfo);
    }
    
    
    public synchronized DataStreamTransactionHandler addOrUpdateDataStream(IDataStreamInfo dsInfo) throws DataStoreException
    {
        Asserts.checkNotNull(dsInfo, IDataStreamInfo.class);
        
        // check output name == data component name
        String outputName = dsInfo.getOutputName();
        if (!outputName.equals(dsInfo.getRecordStructure().getName()))
            throw new IllegalStateException("Inconsistent output name");
        
        // add system ID
        dsInfo = DataStreamInfo.Builder.from(dsInfo)
            .withSystem(new SystemId(sysKey.getInternalID(), sysUID))
            .build();
        
        // try to retrieve existing data stream
        IDataStreamStore dataStreamStore = getDataStreamStore();
        Entry<DataStreamKey, IDataStreamInfo> dsEntry = dataStreamStore.getLatestVersionEntry(sysUID, outputName);
        DataStreamKey dsKey;
        DataStreamAddedEvent addedEvent = null;
        
        if (dsEntry == null)
        {
            // add new data stream
            dsKey = dataStreamStore.add(dsInfo);
            
            // create added event
            addedEvent = new DataStreamAddedEvent(sysUID, outputName);
            log.debug("Added new datastream {}#{}", sysUID, outputName);
        }
        else
        {
            // if an output with the same name already exists
            dsKey = dsEntry.getKey();
            IDataStreamInfo oldDsInfo = dsEntry.getValue();
            
            // check if datastream already has observations
            var hasObs = oldDsInfo.getResultTimeRange() != null;
            
            // 3 cases
            // if observations were already recorded and structure has changed, create a new datastream
            if (hasObs &&
               (!DataComponentChecks.checkStructCompatible(oldDsInfo.getRecordStructure(), dsInfo.getRecordStructure()) ||
                !DataComponentChecks.checkEncodingEquals(oldDsInfo.getRecordEncoding(), dsInfo.getRecordEncoding())))
            {
                dsKey = dataStreamStore.add(dsInfo);
                addedEvent = new DataStreamAddedEvent(sysUID, outputName);
                log.debug("Created new version of datastream {}#{}", sysUID, outputName);
            }
            
            // if something else has changed, update existing datastream
            else if (!DataComponentChecks.checkStructEquals(oldDsInfo.getRecordStructure(), dsInfo.getRecordStructure()) ||
                     !DataComponentChecks.checkEncodingEquals(oldDsInfo.getRecordEncoding(), dsInfo.getRecordEncoding()))
            {
                var dsHandler = new DataStreamTransactionHandler(dsKey, oldDsInfo, rootHandler);
                dsHandler.update(dsInfo);
                dsInfo = dsHandler.getDataStreamInfo();
                log.debug("Updated datastream {}#{}", sysUID, outputName);
            }
            
            // else don't update and return existing key
            else
            {
                dsInfo = oldDsInfo;
                log.debug("No changes to datastream {}#{}", sysUID, outputName);
            }
        }
        
        // create the new datastream handler
        var dsHandler = new DataStreamTransactionHandler(dsKey, dsInfo, foiIdMap, rootHandler);
        if (addedEvent != null)
            dsHandler.publishDataStreamEvent(addedEvent);
        return dsHandler;
    }
    
    
    public CommandStreamTransactionHandler addOrUpdateCommandStream(String commandName, DataComponent dataStruct, DataEncoding dataEncoding) throws DataStoreException
    {
        return addOrUpdateCommandStream(commandName, dataStruct, dataEncoding, null, null);
    }
    
    
    public CommandStreamTransactionHandler addOrUpdateCommandStream(String commandName, DataComponent dataStruct, DataEncoding dataEncoding, DataComponent resultStruct, DataEncoding resultEncoding) throws DataStoreException
    {
        Asserts.checkNotNullOrEmpty(commandName, "commandName");
        Asserts.checkNotNull(dataStruct, DataComponent.class);
        Asserts.checkNotNull(dataEncoding, DataEncoding.class);
        
        // retrieve parent system name
        var sysName = getSystemDescStore().get(sysKey).getName();
        var csName = Strings.isNullOrEmpty(dataStruct.getLabel()) ? commandName : dataStruct.getLabel();
        var fullName = sysName + " - " + csName;
        dataStruct.setName(commandName);
        
        // create command stream info
        // with or without command result
        ICommandStreamInfo csInfo;
        if (resultStruct != null && resultEncoding != null)
        {
            csInfo = new CommandStreamWithResultInfo.Builder()
                .withName(fullName)
                .withSystem(SystemId.NO_SYSTEM_ID)
                .withRecordDescription(dataStruct)
                .withRecordEncoding(dataEncoding)
                .withResultDescription(resultStruct)
                .withResultEncoding(resultEncoding)
                .build();
        }
        else
        {
            csInfo = new CommandStreamInfo.Builder()
                .withName(fullName)
                .withSystem(SystemId.NO_SYSTEM_ID)
                .withRecordDescription(dataStruct)
                .withRecordEncoding(dataEncoding)
                .build();
        }
        
        return addOrUpdateCommandStream(csInfo);
    }
    
    
    public synchronized CommandStreamTransactionHandler addOrUpdateCommandStream(ICommandStreamInfo csInfo) throws DataStoreException
    {
        Asserts.checkNotNull(csInfo, ICommandStreamInfo.class);
        
        // check command name == data component name
        String commandName = csInfo.getControlInputName();
        if (!commandName.equals(csInfo.getRecordStructure().getName()))
            throw new IllegalStateException("Inconsistent command name");
        
        // add system ID
        if (csInfo.getResultStructure() != null)
        {
            csInfo = CommandStreamWithResultInfo.Builder.from(csInfo)
                .withSystem(new SystemId(sysKey.getInternalID(), sysUID))
                .build();
        }
        else
        {
            csInfo = CommandStreamInfo.Builder.from(csInfo)
                .withSystem(new SystemId(sysKey.getInternalID(), sysUID))
                .build();
        }
        
        // try to retrieve existing command stream
        ICommandStreamStore commandStreamStore = getCommandStreamStore();
        Entry<CommandStreamKey, ICommandStreamInfo> csEntry = commandStreamStore.getLatestVersionEntry(sysUID, commandName);
        CommandStreamKey csKey;
        CommandStreamAddedEvent addedEvent = null;
        
        if (csEntry == null)
        {
            // add new command stream
            csKey = commandStreamStore.add(csInfo);
            
            // send event
            addedEvent = new CommandStreamAddedEvent(sysUID, commandName);
            log.debug("Added new command stream {}#{}", sysUID, commandName);
        }
        else
        {
            // if a command input with the same name already exists
            csKey = csEntry.getKey();
            ICommandStreamInfo oldCsInfo = csEntry.getValue();
            
            // check if command stream already has commands
            var hasCommands = oldCsInfo.getIssueTimeRange() != null;
            
            // 3 cases
            // if commands were already recorded and either param or result structure has changed, create a new command stream
            if (hasCommands &&
               (!DataComponentChecks.checkStructCompatible(oldCsInfo.getRecordStructure(), csInfo.getRecordStructure()) ||
                !DataComponentChecks.checkEncodingEquals(oldCsInfo.getRecordEncoding(), csInfo.getRecordEncoding()) ||
                !DataComponentChecks.checkStructCompatibleNullAllowed(oldCsInfo.getResultStructure(), csInfo.getResultStructure()) ||
                !DataComponentChecks.checkEncodingEqualsNullAllowed(oldCsInfo.getResultEncoding(), csInfo.getResultEncoding())))
            {
                csKey = commandStreamStore.add(csInfo);
                addedEvent = new CommandStreamAddedEvent(sysUID, commandName);
                log.debug("Created new version of command stream {}#{}", sysUID, commandName);
            }
            
            // if something else has changed, update existing command stream
            else if (!DataComponentChecks.checkStructEquals(oldCsInfo.getRecordStructure(), csInfo.getRecordStructure()) ||
                     !DataComponentChecks.checkEncodingEquals(oldCsInfo.getRecordEncoding(), csInfo.getRecordEncoding()) ||
                     !DataComponentChecks.checkStructEqualsNullAllowed(oldCsInfo.getResultStructure(), csInfo.getResultStructure()) ||
                     !DataComponentChecks.checkEncodingEqualsNullAllowed(oldCsInfo.getResultEncoding(), csInfo.getResultEncoding()))
            {
                var csHandler = new CommandStreamTransactionHandler(csKey, oldCsInfo, rootHandler);
                csHandler.update(csInfo);
                csInfo = csHandler.getCommandStreamInfo();
                log.debug("Updated command stream {}#{}", sysUID, commandName);
            }
            
            // else don't update and return existing key
            else
            {
                csInfo = oldCsInfo;
                log.debug("No changes to command stream {}#{}", sysUID, commandName);
            }
        }
        
        // create the new command stream handler
        var csHandler = new CommandStreamTransactionHandler(csKey, csInfo, rootHandler);
        if (addedEvent != null)
            csHandler.publishCommandStreamEvent(addedEvent);
        return csHandler;
    }
    
    
    public synchronized FeatureKey addFoi(IFeature foi) throws DataStoreException
    {
        DataStoreUtils.checkFeatureObject(foi);
        
        var fk = getFoiStore().add(sysKey.getInternalID(), foi);
        log.debug("Added FOI {}", foi.getUniqueIdentifier());
        
        publishSystemEvent(new FoiAddedEvent(
            System.currentTimeMillis(),
            sysUID,
            foi.getUniqueIdentifier(),
            Instant.now()));
        
        foiIdMap.put(foi.getUniqueIdentifier(), fk.getInternalID());
        return fk;
    }
    
    
    public synchronized FeatureKey addOrUpdateFoi(IFeature foi) throws DataStoreException
    {
        DataStoreUtils.checkFeatureObject(foi);
        var uid = foi.getUniqueIdentifier();
        
        // add or update if feature with same ID and valid time exists
        FeatureKey fk = rootHandler.db.getFoiStore().getCurrentVersionKey(uid);
        if (fk != null &&
           (foi.getValidTime() == null || Objects.equals(foi.getValidTime().begin(), fk.getValidStartTime())))
        {
            getFoiStore().put(fk, foi);
            log.debug("Updated FOI {}", foi.getUniqueIdentifier());
            foiIdMap.put(uid, fk.getInternalID());
        }
        else
            fk = addFoi(foi);
        
        return fk;
    }
    
    
    protected ISystemDescStore getSystemDescStore()
    {
        return rootHandler.db.getSystemDescStore();
    }
    
    
    protected IDataStreamStore getDataStreamStore()
    {
        return rootHandler.db.getDataStreamStore();
    }
    
    
    protected ICommandStreamStore getCommandStreamStore()
    {
        return rootHandler.db.getCommandStreamStore();
    }
    
    
    protected IFoiStore getFoiStore()
    {
        return rootHandler.db.getFoiStore();
    }
    
    
    protected void publishSystemEvent(SystemEvent event)
    {
        String topic;
        
        // assign internal ID before event is dispatched
        event.assignSystemID(sysKey.getInternalID());
        
        // publish on system status channel
        topic = EventUtils.getSystemStatusTopicID(sysUID);
        rootHandler.eventBus.getPublisher(topic).publish(event);
        
        // publish on parent systems status recursively
        if (parentGroupUID != null)
        {
            var parentKey = rootHandler.db.getSystemDescStore().getCurrentVersionKey(parentGroupUID);
            var parentId = parentKey != null ? parentKey.getInternalID() : null;
            while (parentId != null)
            {
                var sysUid = rootHandler.db.getSystemDescStore().getCurrentVersion(parentId).getUniqueIdentifier();
                topic = EventUtils.getSystemStatusTopicID(sysUid);
                rootHandler.eventBus.getPublisher(topic).publish(event);
                
                parentId = rootHandler.db.getSystemDescStore().getParent(parentId);
            }
        }
        
        // publish on systems root
        topic = EventUtils.getSystemRegistryTopicID();
        rootHandler.eventBus.getPublisher(topic).publish(event);
    }
    
    
    protected void checkParent()
    {
        if (parentGroupUID == null)
        {
            var parentID = getSystemDescStore().getParent(sysKey.getInternalID());
            if (parentID != null && parentID.getIdAsLong() > 0)
                parentGroupUID = getSystemDescStore().getCurrentVersion(parentID).getUniqueIdentifier();
        }
    }
    
    
    protected SystemTransactionHandler createMemberHandler(FeatureKey memberKey, String memberUID)
    {
        return new SystemTransactionHandler(memberKey, memberUID, sysUID, rootHandler);
    }
    
    
    public FeatureKey getSystemKey()
    {
        return sysKey;
    }
    
    
    public String getSystemUID()
    {
        return sysUID;
    }


    public boolean isNew()
    {
        return newlyCreated;
    }
}
