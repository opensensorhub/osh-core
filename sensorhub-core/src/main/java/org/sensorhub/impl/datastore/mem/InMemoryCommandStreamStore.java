/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUObsData WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.

Copyright (C) 2019 Sensia Software LLC. All Rights Reserved.

******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.datastore.mem;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.Collection;
import java.util.Collections;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.stream.Stream;

import net.opengis.swe.v20.DataComponent;
import org.sensorhub.api.command.ICommandStreamInfo;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.datastore.DataStoreException;
import org.sensorhub.api.datastore.IdProvider;
import org.sensorhub.api.datastore.command.CommandFilter;
import org.sensorhub.api.datastore.command.CommandStatusFilter;
import org.sensorhub.api.datastore.command.CommandStreamFilter;
import org.sensorhub.api.datastore.command.CommandStreamKey;
import org.sensorhub.api.datastore.command.ICommandStore;
import org.sensorhub.api.datastore.command.ICommandStreamStore;
import org.sensorhub.api.datastore.system.ISystemDescStore;
import org.sensorhub.impl.datastore.DataStoreUtils;
import org.sensorhub.impl.datastore.command.CommandStreamInfoWrapper;
import org.vast.util.Asserts;
import org.vast.util.TimeExtent;


/**
 * <p>
 * In-memory implementation of a command stream store backed by a {@link NavigableMap}.
 * </p>
 *
 * @author Alex Robin
 * @date Mar 28, 2021
 */
public class InMemoryCommandStreamStore implements ICommandStreamStore
{
    final NavigableMap<CommandStreamKey, ICommandStreamInfo> map = new ConcurrentSkipListMap<>();
    final NavigableMap<BigId, Set<CommandStreamKey>> procIdToCsKeys = new ConcurrentSkipListMap<>();
    final InMemoryCommandStore cmdStore;
    final IdProvider<ICommandStreamInfo> idProvider;
    ISystemDescStore systemStore;
    
    
    class CommandStreamInfoWithTimeRanges extends CommandStreamInfoWrapper
    {
        BigId id;
        TimeExtent issueTimeRange;
        TimeExtent executionTimeRange;
        
        CommandStreamInfoWithTimeRanges(BigId internalID, ICommandStreamInfo csInfo)
        {
            super(csInfo);
            this.id = internalID;
        }
        
        @Override
        public TimeExtent getIssueTimeRange()
        {
            if (issueTimeRange == null)
            {
                var cmdIt = cmdStore.select(new CommandFilter.Builder()
                    .withCommandStreams(id).build()).iterator();
                
                Instant begin = Instant.MAX;
                Instant end = Instant.MIN;
                while (cmdIt.hasNext())
                {
                    var t = cmdIt.next().getIssueTime();
                    if (t.isBefore(begin))
                        begin = t;
                    if (t.isAfter(end))
                        end = t;
                }
                
                if (begin == Instant.MAX || end == Instant.MIN)
                    issueTimeRange = null;
                else
                    issueTimeRange = TimeExtent.period(begin, end);
            }
            
            return issueTimeRange;
        }
        
        @Override
        public TimeExtent getExecutionTimeRange()
        {
            if (executionTimeRange == null)
            {
                var statusIt = cmdStore.cmdStatusStore.select(new CommandStatusFilter.Builder()
                    .withCommands().withCommandStreams(id).done()
                    .build()).iterator();
                
                Instant begin = Instant.MAX;
                Instant end = Instant.MIN;
                while (statusIt.hasNext())
                {
                    var te = statusIt.next().getExecutionTime();
                    if (te != null)
                    {
                        if (te.begin().isBefore(begin))
                            begin = te.begin();
                        if (te.end().isAfter(end))
                            end = te.end();
                    }
                }
                
                if (begin == Instant.MAX || end == Instant.MIN)
                    executionTimeRange = null;
                else
                    executionTimeRange = TimeExtent.period(begin, end);
            }
            
            return executionTimeRange;
        }
    }


    public InMemoryCommandStreamStore(InMemoryCommandStore cmdStore)
    {
        this.cmdStore = Asserts.checkNotNull(cmdStore, ICommandStore.class);
        this.idProvider = DataStoreUtils.getCommandStreamHashIdProvider(236214785);
    }
    
    
    @Override
    public synchronized CommandStreamKey add(ICommandStreamInfo csInfo) throws DataStoreException
    {
        DataStoreUtils.checkCommandStreamInfo(systemStore, csInfo);
        
        // use valid time of parent system or current time if none was set
        csInfo = DataStoreUtils.ensureValidTime(systemStore, csInfo);

        // create key
        var newKey = generateKey(csInfo);

        // add to store
        put(newKey, csInfo, false);
        return newKey;
    }
    
    
    protected CommandStreamKey generateKey(ICommandStreamInfo csInfo)
    {
        //long internalID = map.isEmpty() ? 1 : map.lastKey().getInternalID()+1;
        //return new CommandStreamKey(internalID);
        
        // make sure that the same system/output combination always returns the same ID
        // this will keep things more consistent across restart
        var hash = idProvider.newInternalID(csInfo);
        return new CommandStreamKey(cmdStore.idScope, hash);
    }


    @Override
    public ICommandStreamInfo get(Object key)
    {
        var csKey = DataStoreUtils.checkCommandStreamKey(key);
        
        var val = map.get(csKey);
        if (val != null)
            return new CommandStreamInfoWithTimeRanges(csKey.getInternalID(), val);
        else
            return null;
    }


    @Override
    public Stream<Entry<CommandStreamKey, ICommandStreamInfo>> selectEntries(CommandStreamFilter filter, Set<CommandStreamInfoField> fields)
    {
        Stream<CommandStreamKey> keyStream = null;
        Stream<Entry<CommandStreamKey, ICommandStreamInfo>> resultStream;

        if (filter.getInternalIDs() != null)
        {
            keyStream = filter.getInternalIDs().stream()
                .map(id -> new CommandStreamKey(id));
        }
        
        // or filter on selected systems
        else if (filter.getSystemFilter() != null)
        {
            keyStream = DataStoreUtils.selectSystemIDs(systemStore, filter.getSystemFilter()) 
                .flatMap(procId -> {
                    var csKeys = procIdToCsKeys.get(procId);
                    return csKeys != null ? csKeys.stream() : Stream.empty();
                });
        }        
        
        if (keyStream != null)
        {
            resultStream = keyStream.map(key -> {
                var csInfo = map.get(key);
                if (csInfo == null)
                    return null;
                return (Entry<CommandStreamKey, ICommandStreamInfo>)new AbstractMap.SimpleEntry<>(key, csInfo);
            })
            .filter(Objects::nonNull);
        }
        else
        {
            // stream all entries
            resultStream = map.entrySet().stream();
        }
        
        // filter with predicate, apply limit and wrap with CommandStreamInfoWithTimeRanges
        return resultStream
            .filter(e -> filter.test(e.getValue()))
            .limit(filter.getLimit()).map(e -> {
                ICommandStreamInfo val = new CommandStreamInfoWithTimeRanges(e.getKey().getInternalID(), e.getValue());
                return (Entry<CommandStreamKey, ICommandStreamInfo>)new AbstractMap.SimpleEntry<>(e.getKey(), val);
            });
    }


    @Override
    public ICommandStreamInfo put(CommandStreamKey key, ICommandStreamInfo csInfo)
    {
        DataStoreUtils.checkCommandStreamKey(key);
        
        try
        {
            DataStoreUtils.checkCommandStreamInfo(systemStore, csInfo);
            return put(key, csInfo, true);
        }
        catch (DataStoreException e)
        {
            throw new IllegalArgumentException(e);
        }
    }
    
    
    protected synchronized ICommandStreamInfo put(CommandStreamKey csKey, ICommandStreamInfo csInfo, boolean replace) throws DataStoreException
    {
        // if needed, add a new command stream keyset for the specified system
        var procDsKeys = procIdToCsKeys.compute(csInfo.getSystemID().getInternalID(), (id, keys) -> {
            if (keys == null)
                keys = new ConcurrentSkipListSet<>();
            return keys;
        });
        
        // scan existing command streams associated to the same system
        for (var key: procDsKeys)
        {
            var prevCsInfo = map.get(key);
            
            if (prevCsInfo != null &&
                prevCsInfo.getSystemID().getInternalID() == csInfo.getSystemID().getInternalID() &&
                prevCsInfo.getControlInputName().equals(csInfo.getControlInputName()))
            {    
                var prevValidTime = prevCsInfo.getValidTime().begin();
                var newValidTime = csInfo.getValidTime().begin();
                
                // error if command stream with same system/name/validTime already exists
                if (prevValidTime.equals(newValidTime))
                    throw new DataStoreException(DataStoreUtils.ERROR_EXISTING_COMMANDSTREAM);
                
                // don't add if previous entry had a more recent valid time
                // or if new entry is dated in the future
                if (prevValidTime.isAfter(newValidTime) || newValidTime.isAfter(Instant.now()))
                    return prevCsInfo;
                
                // otherwise remove existing command stream and associated commands
                map.remove(key);
                cmdStore.removeEntries(new CommandFilter.Builder()
                    .withCommandStreams(key.getInternalID())
                    .build());
                break;
            }
        }
        
        // add new command stream
        var oldDsInfo = map.put(csKey, csInfo);
        procDsKeys.add(csKey);
        return oldDsInfo;
    }


    @Override
    public ICommandStreamInfo remove(Object key)
    {
        var csKey = DataStoreUtils.checkCommandStreamKey(key);
        var oldValue = map.remove(csKey);
        if (oldValue != null)
            procIdToCsKeys.get(oldValue.getSystemID().getInternalID()).remove(csKey);
        return oldValue;
    }


    @Override
    public long getNumRecords()
    {
        return map.size();
    }


    @Override
    public void clear()
    {
        map.clear();
    }


    @Override
    public boolean containsKey(Object key)
    {
        var csKey = DataStoreUtils.checkCommandStreamKey(key);
        return map.containsKey(csKey);
    }


    @Override
    public boolean containsValue(Object val)
    {
        return map.containsValue(val);
    }


    @Override
    public Set<Entry<CommandStreamKey, ICommandStreamInfo>> entrySet()
    {
        return map.entrySet();
    }


    @Override
    public boolean isEmpty()
    {
        return map.isEmpty();
    }


    @Override
    public Set<CommandStreamKey> keySet()
    {
        return Collections.unmodifiableSet(map.keySet());
    }


    @Override
    public int size()
    {
        return map.size();
    }


    @Override
    public Collection<ICommandStreamInfo> values()
    {
        return Collections.unmodifiableCollection(map.values());
    }


    @Override
    public String getDatastoreName()
    {
        return getClass().getSimpleName();
    }


    @Override
    public void commit()
    {        
    }


    @Override
    public void backup(OutputStream is) throws IOException
    {
        throw new UnsupportedOperationException();
    }


    @Override
    public void restore(InputStream os) throws IOException
    {
        throw new UnsupportedOperationException();
    }


    @Override
    public boolean isReadOnly()
    {
        return false;
    }
    
    
    @Override
    public void linkTo(ISystemDescStore systemStore)
    {
        this.systemStore = Asserts.checkNotNull(systemStore, ISystemDescStore.class);
    }
}
