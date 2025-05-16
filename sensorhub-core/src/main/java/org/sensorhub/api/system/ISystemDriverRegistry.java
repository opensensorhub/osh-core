/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.

Copyright (C) 2019 Sensia Software LLC. All Rights Reserved.

******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.api.system;

import java.util.Collection;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import org.sensorhub.api.command.IStreamingControlInterface;
import org.sensorhub.api.data.IDataProducer;
import org.sensorhub.api.data.IDataStreamInfo;
import org.sensorhub.api.data.IStreamingDataInterface;
import org.sensorhub.api.database.IObsSystemDatabase;
import org.sensorhub.api.database.ISystemStateDatabase;
import org.sensorhub.api.datastore.obs.DataStreamKey;
import org.sensorhub.api.procedure.IProcedureWithDesc;
import org.vast.ogc.gml.IFeature;


/**
 * <p>
 * There is one system registry per sensor hub that is used to keep the list
 * and state of all systems registered on this hub. (i.e. sensors, actuators,
 * processes, other data sources). The registry generates events when systems
 * are added and removed from the registry. Implementations backed by a
 * persistent store can be used to persist systems state across restarts.
 * </p><p>
 * <i>Note that implementations of this interface may not expose the driver/module
 * objects directly. They usually keep a shadow object reflecting the state of
 * the system instead; thus clients of this interface should not rely on
 * specific functionality of the driver module itself.</i>
 * </p>
 *
 * @author Alex Robin
 * @since Feb 18, 2019
 * @see ISystemDriver
 */
public interface ISystemDriverRegistry
{
    
    /**
     * Asynchronously registers a system driver (e.g. sensor driver, etc.)
     * with this registry. Implementation of this method must take take care of
     * forwarding all events produced by the driver to the event bus.
     * <br/><br/>
     * If the system is a {@link IDataProducer}, this method takes care of
     * registering all FOIs associated with the system at the time of registration
     * (i.e. returned by {@link IDataProducer#getCurrentFeaturesOfInterest()}).
     * <br/><br/>
     * If the system is a {@link ISystemGroupDriver}, this method takes
     * care of registering all subsystems defined by the driver at the time of
     * registration (i.e. returned by {@link ISystemGroupDriver#getMembers()}).
     * <br/><br/>
     * Note that a single {@link SystemAddedEvent} is generated for the parent
     * system. No event is generated for members of a system group. 
     * @param proc The system driver instance
     * @return A future that will be completed when the system is successfully
     * registered or report an exception if an error occurred.
     */
    public CompletableFuture<Boolean> register(ISystemDriver proc);
    
    
    /**
     * Registers a procedure implemented by a system driver.<br/>
     * The procedure can be the datasheet of a piece of equipment (e.g. sensor,
     * platform, etc.), or it can be a methodology implemented by an operator to
     * measure, sample or act on features of interest.<br/>
     * System descriptions (that represent system instances) can then refer to
     * these procedures/datasheets instead of including all information in each
     * instance.
     * @param proc The procedure description
     * @return A future that will be completed when the procedure is
     * successfully unregistered or report an exception if an error occurred.
     */
    public CompletableFuture<Void> register(IProcedureWithDesc proc);
    
    
    /**
     * Asynchronously registers a datastream / data interface.
     * @param dataStream The streaming data interface of a system driver
     * @return A future that will be completed when the datastream is successfully
     * registered or report an exception if an error occurred.
     */
    public CompletableFuture<Boolean> register(IStreamingDataInterface dataStream);
    
    
    /**
     * Asynchronously registers a control interface.
     * @param controlStream The streaming control interface of a system driver
     * @return A future that will be completed when the control interface is successfully
     * registered or report an exception if an error occurred.
     */
    public CompletableFuture<Boolean> register(IStreamingControlInterface controlStream);
    
    
    /**
     * Asynchronously registers a feature of interest.
     * @param proc The system observing or targeting the feature of interest
     * @param foi The feature of interest
     * @return A future that will be completed when the feature of interest is
     * successfully registered or report an exception if an error occurred.
     */
    public CompletableFuture<Boolean> register(ISystemDriver sys, IFeature foi);


    /**
     * Asynchronously registers a feature of interest.
     * @param sysUID The unique identifier of the system hosting the feature of interest
     * @param foi The feature of interest
     * @return A future that will be completed when the feature of interest is
     * successfully registered or report an exception if an error occurred.
     */
    public CompletableFuture<Boolean> register(String sysUID, IFeature foi);
    
    
    /**
     * Unregisters a system driver and all its datastreams, command streams
     * and subsystems, recursively.
     * <br/><br/>
     * Note that unregistering the system doesn't remove it from the
     * data store but only disconnects the system driver and send a
     * {@link SystemDisabledEvent} event.
     * @param sys The system driver instance
     * @return A future that will be completed when the system driver is
     * successfully unregistered or report an exception if an error occurred.
     */
    public CompletableFuture<Void> unregister(ISystemDriver sys);
    
    
    /**
     * Checks if the given system driver is registered on this hub
     * @param uid
     * @return True if a driver has previously registered a system with the
     * given UID, false otherwise
     */
    public boolean isRegistered(String uid);
    
    
    /**
     * @return The database containing the latest state of systems registered
     * on this hub when they are not associated to a historical database.
     * Note that information contained in this database is also accessible as
     * read-only through the federated hub database.
     */
    public ISystemStateDatabase getSystemStateDatabase();
    
    
    /**
     * Configures the registry to write data produced by the system driver with the
     * specified UID to the provided observing system database.
     * <p>This can be called multiple times to register multiple mappings with the
     * same database instance. However, several databases cannot contain data for
     * the same system so a given system UID cannot be mapped to different
     * database instances using this method.</p>
     * <p>Note that the database is not required to contain data for the specified
     * system at the time of the call.</p>
     * @param systemUID Unique ID of system to associate with the database
     * @param db A database instance
     * @throws IllegalArgumentException if the database is not writable
     */
    void registerDatabase(String systemUID, IObsSystemDatabase db);


    /**
     * Helper method to register several system/database mappings at once.
     * @see {@link #register(String, IObsSystemDatabase)}.
     * @param systemUIDs Unique IDs of systems to associate with the database
     * @param db The database instance
     */
    default void registerDatabase(Collection<String> systemUIDs, IObsSystemDatabase db)
    {
        for (String uid: systemUIDs)
            registerDatabase(uid, db);
    }


    /**
     * Unregisters a system to observation database mapping.
     * @param systemUID Unique ID of systems previously associated with
     * the specified database
     * @param db A database instance
     */
    void unregisterDatabase(IObsSystemDatabase db);


    /**
     * Checks if a database is currently handling data generated by the
     * specified system
     * @param systemUID Unique ID of the system
     * @return true if a database has been registered, false otherwise
     */
    boolean hasDatabase(String systemUID);
    
    
    /**
     * Provides direct read/write access to the database that is currently
     * handling observation data from the specified system driver
     * @param systemUID Unique ID of the system
     * @return The database instance or null if none has been registered
     * for the specified system
     */
    IObsSystemDatabase getDatabase(String systemUID);
    
    
    /**
     * Waits for a datastream to be available and return the corresponding database entry
     * @param sysUid UID of parent system
     * @param outputName Output name
     * @return A future providing the database entry for the most recent datastream mapped
     * to the specified output
     */
    CompletableFuture<Entry<DataStreamKey, IDataStreamInfo>> waitForDataStream(String sysUid, String outputName);
    
}
