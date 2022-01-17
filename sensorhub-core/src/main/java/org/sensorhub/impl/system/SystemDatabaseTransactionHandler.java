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

import org.sensorhub.api.database.IObsSystemDatabase;
import org.sensorhub.api.datastore.DataStoreException;
import org.sensorhub.api.datastore.command.CommandStreamKey;
import org.sensorhub.api.datastore.feature.FeatureKey;
import org.sensorhub.api.datastore.obs.DataStreamKey;
import org.sensorhub.api.event.EventUtils;
import org.sensorhub.api.event.IEventBus;
import org.sensorhub.api.system.ISystemWithDesc;
import org.sensorhub.api.system.SystemAddedEvent;
import org.sensorhub.api.utils.OshAsserts;
import org.vast.util.Asserts;


/**
 * <p>
 * Helper class for creating/updating/deleting (observing) systems and their
 * components in the associated database and publishing the corresponding
 * events.
 * </p>
 *
 * @author Alex Robin
 * @date Dec 21, 2020
 */
public class SystemDatabaseTransactionHandler
{
    final protected IEventBus eventBus;
    final protected IObsSystemDatabase db;
    
    
    public SystemDatabaseTransactionHandler(IEventBus eventBus, IObsSystemDatabase db)
    {
        this.eventBus = Asserts.checkNotNull(eventBus, IEventBus.class);
        this.db = Asserts.checkNotNull(db, IObsSystemDatabase.class);
    }
    
    
    /**
     * Add a new system with the provided description
     * @param system System description
     * @return The transaction handler linked to the system
     * @throws DataStoreException if a system with the same UID already exists
     */
    public SystemTransactionHandler addSystem(ISystemWithDesc system) throws DataStoreException
    {
        OshAsserts.checkProcedureObject(system);
        
        // add system to store
        var systemKey = db.getSystemDescStore().add(system);
        var sysUID = system.getUniqueIdentifier();
        
        // send event
        var topic = EventUtils.getSystemRegistryTopicID();
        var eventPublisher = eventBus.getPublisher(topic);
        eventPublisher.publish(new SystemAddedEvent(sysUID, null));
        
        // create new system handler
        return createSystemHandler(systemKey, sysUID);
    }
    
    
    /**
     * Update the description of an existing system
     * @param system
     * @return The transaction handler linked to the system
     * @throws DataStoreException if the system doesn't exist or cannot be updated
     */
    public SystemTransactionHandler updateSystem(ISystemWithDesc system) throws DataStoreException
    {
        OshAsserts.checkProcedureObject(system);
        
        var systemHandler = getSystemHandler(system.getUniqueIdentifier());
        systemHandler.update(system);
        return systemHandler;
    }
    
    
    /**
     * Add or update a system.
     * If no system with the same UID already exists, a new one will be created,
     * otherwise the existing one will be updated or versioned depending if the the validity
     * period was changed.
     * @param system New system description
     * @return The transaction handler linked to the system
     * @throws DataStoreException if the system couldn't be added or updated
     */
    public SystemTransactionHandler addOrUpdateSystem(ISystemWithDesc system) throws DataStoreException
    {
        OshAsserts.checkProcedureObject(system);
        
        var systemHandler = getSystemHandler(system.getUniqueIdentifier());
        if (systemHandler != null)
        {
            systemHandler.update(system);
            return systemHandler;
        }
        else
            return addSystem(system);
    }
    
    
    /**
     * Create a handler for an existing system with the specified ID
     * @param id system internal ID
     * @return The new system handler or null if system doesn't exist
     */
    public SystemTransactionHandler getSystemHandler(long id)
    {
        OshAsserts.checkValidInternalID(id);
        
        // load system object from DB
        var systemEntry = db.getSystemDescStore().getCurrentVersionEntry(id);
        if (systemEntry == null)
            return null;
        
        // create new system handler
        var systemKey = systemEntry.getKey();
        var sysUID = systemEntry.getValue().getUniqueIdentifier();
        return createSystemHandler(systemKey, sysUID);
    }
    
    
    /**
     * Create a handler for an existing system with the specified unique ID
     * @param sysUID system unique ID
     * @return The new system handler or null if system doesn't exist
     */
    public SystemTransactionHandler getSystemHandler(String sysUID)
    {
        OshAsserts.checkValidUID(sysUID);
        
        // load system object from DB
        var systemKey = db.getSystemDescStore().getCurrentVersionKey(sysUID);
        if (systemKey == null)
            return null;
        
        // create new system handler
        return createSystemHandler(systemKey, sysUID);
    }
    
    
    /**
     * Create a handler for an existing datastream with the specified ID
     * @param id Datastream internal ID
     * @return The new datastream handler or null if datastream doesn't exist
     */
    public DataStreamTransactionHandler getDataStreamHandler(long id)
    {
        OshAsserts.checkValidInternalID(id);
        
        // load datastream info from DB
        var dsKey = new DataStreamKey(id);
        var dsInfo = db.getDataStreamStore().get(dsKey);
        if (dsInfo == null)
            return null;
        
        // create new datastream handler
        return new DataStreamTransactionHandler(dsKey, dsInfo, this);
    }
    
    
    protected SystemTransactionHandler createSystemHandler(FeatureKey systemKey, String sysUID)
    {
        return new SystemTransactionHandler(systemKey, sysUID, this);
    }
    
    
    /**
     * Create a handler for an existing datastream with the specified system and output name
     * @param sysUID system unique ID
     * @param outputName Output name
     * @return The new datastream handler or null if datastream doesn't exist
     */
    public DataStreamTransactionHandler getDataStreamHandler(String sysUID, String outputName)
    {
        OshAsserts.checkValidUID(sysUID);
        Asserts.checkNotNullOrEmpty(outputName, "outputName");
        
        // load datastream info from DB
        var dsEntry = db.getDataStreamStore().getLatestVersionEntry(sysUID, outputName);
        if (dsEntry == null)
            return null;
        
        // create new datastream handler
        return new DataStreamTransactionHandler(dsEntry.getKey(), dsEntry.getValue(), this);
    }
    
    
    /**
     * Create a handler for an existing command stream with the specified ID
     * @param id Command stream internal ID
     * @return The new command stream handler or null if command stream doesn't exist
     */
    public CommandStreamTransactionHandler getCommandStreamHandler(long id)
    {
        OshAsserts.checkValidInternalID(id);
        
        // load command stream info from DB
        var csKey = new CommandStreamKey(id);
        var csInfo = db.getCommandStreamStore().get(csKey);
        if (csInfo == null)
            return null;
        
        // create new command stream handler
        return new CommandStreamTransactionHandler(csKey, csInfo, this);
    }
    
    
    /**
     * Create a handler for an existing command stream with the specified system
     * and control input name
     * @param sysUID system unique ID
     * @param controlInputName Control input name
     * @return The new command stream handler or null if command stream doesn't exist
     */
    public CommandStreamTransactionHandler getCommandStreamHandler(String sysUID, String controlInputName)
    {
        OshAsserts.checkValidUID(sysUID);
        Asserts.checkNotNullOrEmpty(controlInputName, "controlInputName");
        
        // load command stream info from DB
        var csEntry = db.getCommandStreamStore().getLatestVersionEntry(sysUID, controlInputName);
        if (csEntry == null)
            return null;
        
        // create new command stream handler
        return new CommandStreamTransactionHandler(csEntry.getKey(), csEntry.getValue(), this);
    }
}