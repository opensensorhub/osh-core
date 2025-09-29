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
import org.sensorhub.api.feature.FeatureId;
import org.sensorhub.api.feature.FoiAddedEvent;
import org.sensorhub.api.system.ISystemWithDesc;
import org.sensorhub.api.system.SystemAddedEvent;
import org.sensorhub.api.system.SystemChangedEvent;
import org.sensorhub.api.system.SystemRemovedEvent;
import org.sensorhub.api.system.SystemDisabledEvent;
import org.sensorhub.api.system.SystemEnabledEvent;
import org.sensorhub.api.system.SystemEvent;
import org.sensorhub.api.utils.OshAsserts;
import org.sensorhub.impl.datastore.DataStoreUtils;
import org.sensorhub.utils.DataComponentChecks;
import org.sensorhub.utils.Lambdas;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vast.ogc.gml.IFeature;
import org.vast.util.Asserts;
import org.vast.util.TimeExtent;
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
    static final String DELETE_NESTED_ERROR_FORMAT = "System cannot be deleted because it still has %s (use cascade to force delete)";
    
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
        checkParent();
        
        try
        {
            var validTime = system.getValidTime();
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
        }
        catch (IllegalArgumentException e)
        {
            if (e.getCause() instanceof DataStoreException)
                throw (DataStoreException)e.getCause();
            throw e;
        }
        
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
            // error if subsystems still exist
            var sysFilter = new SystemFilter.Builder()
                .withParents(sysKey.getInternalID())
                .withLimit(1)
                .build();
            if (getSystemDescStore().countMatchingEntries(sysFilter) > 0)
                throw new DataStoreException(String.format(DELETE_NESTED_ERROR_FORMAT, "subsystems"));
            
            // error if associated datastreams still exist
            var dsFilter = new DataStreamFilter.Builder()
                .withSystems(sysKey.getInternalID())
                .withLimit(1)
                .build();
            if (getDataStreamStore().countMatchingEntries(dsFilter) > 0)
                throw new DataStoreException(String.format(DELETE_NESTED_ERROR_FORMAT, "datastreams"));
            
            // error if associated command streams still exist
            var csFilter = new CommandStreamFilter.Builder()
                .withSystems(sysKey.getInternalID())
                .withLimit(1)
                .build();
            if (getCommandStreamStore().countMatchingEntries(csFilter) > 0)
                throw new DataStoreException(String.format(DELETE_NESTED_ERROR_FORMAT, "control channels"));
            
            // error if associated fois still exist
            var foiFilter = new FoiFilter.Builder()
                .withParents(sysKey.getInternalID())
                .withLimit(1)
                .build();
            if (getFoiStore().countMatchingEntries(foiFilter) > 0)
                throw new DataStoreException(String.format(DELETE_NESTED_ERROR_FORMAT, "features of interest"));
        }
        else
        {
            // delete all subsystems, recursively
            try
            {
                getSystemDescStore().selectKeys(new SystemFilter.Builder()
                        .withParents(sysKey.getInternalID())
                        .build())
                    .forEach(Lambdas.<FeatureKey>checked(k -> {
                        var nestedSysHandler = rootHandler.getSystemHandler(k.getInternalID());
                        nestedSysHandler.delete(true);
                    }));
            }
            catch (Exception e)
            {
                throw (DataStoreException)Lambdas.unwrap(e);
            }
            
            // delete all datastreams
            getDataStreamStore().removeEntries(new DataStreamFilter.Builder()
                .withSystems(sysKey.getInternalID())
                .build());
            
            // delete all command streams
            getCommandStreamStore().removeEntries(new CommandStreamFilter.Builder()
                .withSystems(sysKey.getInternalID())
                .build());
            
            // delete all fois
            getFoiStore().removeEntries(new FoiFilter.Builder()
                .withParents(sysKey.getInternalID())
                .build());
        }
        
        // remove from datastore
        var deletedSysKey = getSystemDescStore().remove(sysUID);
        if (deletedSysKey != null)
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
            .withSystem(FeatureId.NULL_FEATURE)
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
            .withSystem(new FeatureId(sysKey.getInternalID(), sysUID))
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
            var validTime = dsInfo.getValidTime();
            var oldValidTime = dsEntry.getValue().getValidTime();
            
            // if datastream created with an explicit validTime
            if (validTime != null && !validTime.begin().equals(oldValidTime.begin()))
            {
                dsKey = dataStreamStore.add(dsInfo);
                addedEvent = new DataStreamAddedEvent(sysUID, outputName);
                log.debug("Added datastream {}#{} with valid time {}", sysUID, outputName, validTime);
            }
            
            // compare properties of old and new datastreams
            var sameName = Objects.equals(oldDsInfo.getName(), dsInfo.getName());
            var sameDescription = Objects.equals(oldDsInfo.getDescription(), dsInfo.getDescription());
            var sameRecordStruct = DataComponentChecks.checkStructEquals(oldDsInfo.getRecordStructure(), dsInfo.getRecordStructure());
            var sameRecordEncoding = DataComponentChecks.checkEncodingEquals(oldDsInfo.getRecordEncoding(), dsInfo.getRecordEncoding());
            var recordStructCompatible = sameRecordStruct || DataComponentChecks.checkStructCompatible(oldDsInfo.getRecordStructure(), dsInfo.getRecordStructure());
                        
            // if observations were already recorded and structure has changed, create a new datastream
            if (hasObs && (!recordStructCompatible || !sameRecordEncoding))
            {
                // set validTime to current time
                dsInfo = DataStreamInfo.Builder.from(dsInfo)
                    .withValidTime(TimeExtent.endNow(Instant.now()))
                    .build();
                
                dsKey = dataStreamStore.add(dsInfo);
                addedEvent = new DataStreamAddedEvent(sysUID, outputName);
                log.debug("Added datastream {}#{} with new data structure", sysUID, outputName);
            }
            
            // if something else has changed, update existing datastream
            else if (!sameRecordStruct || !sameRecordEncoding || !sameName || !sameDescription)
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
        ICommandStreamInfo csInfo = new CommandStreamInfo.Builder()
            .withName(fullName)
            .withSystem(FeatureId.NULL_FEATURE)
            .withRecordDescription(dataStruct)
            .withRecordEncoding(dataEncoding)
            .withResultDescription(resultStruct)
            .withResultEncoding(resultEncoding)
            .build();
        
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
        csInfo = CommandStreamInfo.Builder.from(csInfo)
            .withSystem(new FeatureId(sysKey.getInternalID(), sysUID))
            .build();
        
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
            var validTime = csInfo.getValidTime();
            var oldValidTime = csEntry.getValue().getValidTime();

            // if datastream created with an explicit validTime
            if (validTime != null && !validTime.begin().equals(oldValidTime.begin()))
            {
                csKey = commandStreamStore.add(csInfo);
                addedEvent = new CommandStreamAddedEvent(sysUID, commandName);
                log.debug("Added command stream {}#{} with valid time {}", sysUID, commandName, validTime);
            }
            
            // compare properties of old and new command streams
            var sameName = Objects.equals(oldCsInfo.getName(), csInfo.getName());
            var sameDescription = Objects.equals(oldCsInfo.getDescription(), csInfo.getDescription());
            
            var sameParamStruct = DataComponentChecks.checkStructEquals(oldCsInfo.getRecordStructure(), csInfo.getRecordStructure());
            var sameParamEncoding = DataComponentChecks.checkEncodingEquals(oldCsInfo.getRecordEncoding(), csInfo.getRecordEncoding());
            var paramStructCompatible = sameParamStruct || DataComponentChecks.checkStructCompatible(oldCsInfo.getRecordStructure(), csInfo.getRecordStructure());
            
            var sameResultStruct = DataComponentChecks.checkStructEqualsNullAllowed(oldCsInfo.getResultStructure(), csInfo.getResultStructure());
            var sameResultEncoding = DataComponentChecks.checkEncodingEqualsNullAllowed(oldCsInfo.getResultEncoding(), csInfo.getResultEncoding());
            var resultStructCompatible = sameResultStruct || DataComponentChecks.checkStructCompatibleNullAllowed(oldCsInfo.getResultStructure(), csInfo.getResultStructure());
            
            // if observations were already recorded and structure has changed, create a new datastream
            if (hasCommands && (!paramStructCompatible || !sameParamEncoding || !resultStructCompatible || !sameResultEncoding))
            {
                // set validTime to current time
                csInfo = CommandStreamInfo.Builder.from(csInfo)
                    .withValidTime(TimeExtent.endNow(Instant.now()))
                    .build();

                csKey = commandStreamStore.add(csInfo);
                addedEvent = new CommandStreamAddedEvent(sysUID, commandName);
                log.debug("Added command stream {}#{} with new data structure", sysUID, commandName);
            }

            // if something else has changed, update existing command stream
            else if (!sameParamStruct || !sameParamEncoding || !sameResultStruct || !sameResultEncoding || !sameName || !sameDescription)
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
        
        var foiStore = getFoiStore();
        synchronized (foiStore) {
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
    }
    
    
    public synchronized FeatureKey addOrUpdateFoi(IFeature foi) throws DataStoreException
    {
        DataStoreUtils.checkFeatureObject(foi);
        
        // add or update if feature with same ID and valid time exists
        var foiStore = getFoiStore();
        synchronized (foiStore) {
            FeatureKey fk = foiStore.getCurrentVersionKey(foi.getUniqueIdentifier());
            if (fk != null &&
               (foi.getValidTime() == null || Objects.equals(foi.getValidTime().begin(), fk.getValidStartTime())))
            {
                foiStore.put(fk, foi);
                foiIdMap.put(foi.getUniqueIdentifier(), fk.getInternalID());
                log.debug("Updated FOI {}", foi.getUniqueIdentifier());
            }
            else
                fk = addFoi(foi);
            
            return fk;
        }
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
