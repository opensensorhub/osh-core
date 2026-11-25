/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2021 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.api.datastore.command;

import java.time.Instant;
import java.util.Comparator;
import org.sensorhub.api.command.ICommandStreamInfo;
import org.sensorhub.api.datastore.DataStoreException;
import org.sensorhub.api.datastore.command.ICommandStreamStore.CommandStreamInfoField;
import org.sensorhub.api.datastore.resource.IResourceStore;
import org.sensorhub.api.datastore.resource.IResourceStore.ResourceField;
import org.sensorhub.api.datastore.system.ISystemDescStore;


/**
 * <p>
 * Generic interface for managing command streams within a command store.<br/>
 * Removal operations also remove all commands associated to a command stream. 
 * </p>
 *
 * @author Alex Robin
 * @date Mar 11, 2021
 */
public interface ICommandStreamStore extends IResourceStore<CommandStreamKey, ICommandStreamInfo, CommandStreamInfoField, CommandStreamFilter>
{
    
    public static class CommandStreamInfoField extends ResourceField
    {
        public static final CommandStreamInfoField SYSTEM_ID = new CommandStreamInfoField("systemID");
        public static final CommandStreamInfoField COMMAND_NAME = new CommandStreamInfoField("commandName");
        public static final CommandStreamInfoField VALID_TIME = new CommandStreamInfoField("validTime");
        public static final CommandStreamInfoField RECORD_DESCRIPTION  = new CommandStreamInfoField("recordDescription");
        public static final CommandStreamInfoField RECORD_ENCODING = new CommandStreamInfoField("recordEncoding");
        
        public CommandStreamInfoField(String name)
        {
            super(name);
        }
    }
    
    
    @Override
    public default CommandStreamFilter.Builder filterBuilder()
    {
        return new CommandStreamFilter.Builder();
    }
    
    
    /**
     * Add a new command stream and generate a new unique key for it.<br/>
     * If the command stream valid time is not set, it will be set to the valid time
     * of the parent system.
     * @param csInfo The command stream info object to be stored
     * @return The key associated with the new command stream
     * @throws DataStoreException if a command stream with the same parent system,
     * taskable parameter and valid time already exists, or if the parent system is unknown.
     */
    public CommandStreamKey add(ICommandStreamInfo csInfo) throws DataStoreException;
    
    
    /**
     * Helper method to retrieve the internal ID of the latest version of the
     * command stream corresponding to the specified system and command input.
     * @param sysUID Unique ID of system receiving the command stream
     * @param commandName Name of taskable parameter associated to the command stream
     * @return The command stream key or null if none was found
     */
    public default CommandStreamKey getLatestVersionKey(String sysUID, String commandName)
    {
        Entry<CommandStreamKey, ICommandStreamInfo> e = getLatestVersionEntry(sysUID, commandName);
        return e != null ? e.getKey() : null;
    }
    
    
    /**
     * Helper method to retrieve the latest version of the command stream
     * corresponding to the specified system and command input.
     * @param sysUID Unique ID of system receiving the command stream
     * @param commandName Name of taskable parameter associated to the command stream
     * @return The command stream info or null if none was found
     */
    public default ICommandStreamInfo getLatestVersion(String sysUID, String commandName)
    {
        var e = getLatestVersionEntry(sysUID, commandName);
        return e != null ? e.getValue() : null;
    }
    
    
    /**
     * Helper method to retrieve the entry for the latest version of the
     * command stream corresponding to the specified system and command input.
     * @param sysUID Unique ID of system receiving the command stream
     * @param controlInput Name of control input associated to the command stream
     * @return The feature entry or null if none was found with this UID
     */
    public default Entry<CommandStreamKey, ICommandStreamInfo> getLatestVersionEntry(String sysUID, String controlInput)
    {
        var entryOpt = selectEntries(new CommandStreamFilter.Builder()
            .withSystems()
                .withUniqueIDs(sysUID)
                .done()
            .withControlInputNames(controlInput)
            .build())
        // results are not guaranteed to be sorted, so select the version with the latest valid time
        .max(Comparator.comparing(e -> getValidTimeBegin(e.getValue())));
        
        return entryOpt.isPresent() ? entryOpt.get() : null;
    }
    
    
    private static Instant getValidTimeBegin(ICommandStreamInfo info)
    {
        var validTime = info.getValidTime();
        return validTime != null ? validTime.begin() : Instant.MIN;
    }
    
    
    /**
     * Remove all command streams that are associated to the given system command input
     * @param sysUID Unique ID of system receiving the command stream
     * @param controlInput Name of control input associated to the command stream
     * @return The number of entries actually removed
     */
    public default long removeAllVersions(String sysUID, String controlInput)
    {
        return removeEntries(new CommandStreamFilter.Builder()
            .withSystems()
                .withUniqueIDs(sysUID)
                .done()
            .withControlInputNames(controlInput)
            .build());
    }
    
    
    /**
     * Link this store to a system store to enable JOIN queries
     * @param systemStore
     */
    public void linkTo(ISystemDescStore systemStore);
    
}
