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

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.sensorhub.api.command.ICommandData;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.datastore.TemporalFilter;
import org.sensorhub.api.datastore.command.CommandFilter;
import org.sensorhub.api.datastore.command.CommandStats;
import org.sensorhub.api.datastore.command.CommandStatsQuery;
import org.sensorhub.api.datastore.command.ICommandStatusStore;
import org.sensorhub.api.datastore.command.ICommandStore;
import org.sensorhub.api.datastore.command.ICommandStreamStore;
import org.sensorhub.api.datastore.feature.IFoiStore;
import org.sensorhub.impl.datastore.DataStoreUtils;
import org.sensorhub.utils.AtomicInitializer;
import org.sensorhub.utils.ObjectUtils;
import org.sensorhub.utils.VarInt;
import org.vast.util.Asserts;


/**
 * <p>
 * In-memory implementation of a command store backed by a {@link NavigableMap}.
 * This implementation is only used to store the latest system state and
 * thus only stores the latest command of each command stream.
 * </p>
 *
 * @author Alex Robin
 * @date Mar 28, 2021
 */
public class InMemoryCommandStore extends InMemoryDataStore implements ICommandStore
{
    static final TemporalFilter ALL_TIMES_FILTER = new TemporalFilter.Builder().withAllTimes().build();
    
    final NavigableMap<CmdKey, ICommandData> map = new ConcurrentSkipListMap<>(new CmdKeyComparator());
    final InMemoryCommandStreamStore cmdStreamStore;
    final InMemoryCommandStatusStore cmdStatusStore;
    final int idScope;
    IFoiStore foiStore;
    AtomicLong cmdCounter = new AtomicLong();
    
    
    private static class CmdKey implements BigId
    {
        int scope;
        long cmdStreamID;
        long foiID;
        Instant issueTime;
        AtomicInitializer<byte[]> cachedId = new AtomicInitializer<>();
        
        CmdKey(int scope, long cmdStreamID, long foiID, Instant issueTime)
        {
            this.scope = scope;
            this.cmdStreamID = cmdStreamID;
            this.foiID = foiID;
            this.issueTime = issueTime;
        }

        @Override
        public int getScope()
        {
            return scope;
        }

        @Override
        public byte[] getIdAsBytes()
        {
            // compute byte[] representation lazily
            return cachedId.get(() -> {
                var sz = VarInt.varLongSize(cmdStreamID)
                       + VarInt.varLongSize(foiID) 
                       + VarInt.varLongSize(issueTime.getEpochSecond())
                       + 4;
                ByteBuffer buf = ByteBuffer.allocate(sz);
                VarInt.putVarLong(cmdStreamID, buf);
                VarInt.putVarLong(foiID, buf);
                VarInt.putVarLong(issueTime.getEpochSecond(), buf);
                buf.putInt(issueTime.getNano());
                return buf.array();
            });
        }
        
        @Override
        public int hashCode()
        {
            return Objects.hash(
                scope, cmdStreamID, foiID, issueTime
            );
        }
        
        @Override
        public boolean equals(Object o)
        {
            if (!(o instanceof CmdKey))
                return false;
            
            var other = (CmdKey)o;
            return scope == other.scope &&
                cmdStreamID == other.cmdStreamID &&
                foiID == other.foiID &&
                Objects.equals(issueTime, other.issueTime);
        }

        @Override
        public String toString()
        {
            return ObjectUtils.toString(this, true);
        }
    }
    
    
    private static class CmdKeyComparator implements Comparator<CmdKey>
    {
        @Override
        public int compare(CmdKey k1, CmdKey k2)
        {
            // first compare command stream IDs
            int comp = Long.compare(k1.cmdStreamID, k2.cmdStreamID);
            if (comp != 0)
                return comp;
            
            // then compare foi IDs
            comp = Long.compare(k1.foiID, k2.foiID);
            if (comp != 0)
                return comp;
            
            // then compare issue times
            return k1.issueTime.compareTo(k2.issueTime);
        }
    }
    
    
    public InMemoryCommandStore(int idScope)
    {
        this.idScope = idScope;
        this.cmdStreamStore = new InMemoryCommandStreamStore(this);
        this.cmdStatusStore = new InMemoryCommandStatusStore(this);
    }
    
    
    CmdKey toInternalKey(Object keyObj)
    {
        Asserts.checkArgument(keyObj instanceof BigId);
        BigId key = (BigId)keyObj;
        
        if (key instanceof CmdKey)
            return (CmdKey)key;

        try
        {
            // parse from BigId
            var buf = ByteBuffer.wrap(key.getIdAsBytes());
            long dsID = VarInt.getVarLong(buf);
            long foiID = VarInt.getVarLong(buf);
            Instant issueTime = Instant.ofEpochSecond(
                VarInt.getVarLong(buf),
                buf.getInt());
            return new CmdKey(idScope, dsID, foiID, issueTime);
        }
        catch (Exception e)
        {
            // invalid BigId key
            // return key object that will never match
            return new CmdKey(0, 0, 0, Instant.MAX);
        }
    }
    
    
    Stream<Entry<CmdKey, ICommandData>> getCommandsByCommandStream(BigId cmdStreamID)
    {
        CmdKey fromKey = new CmdKey(0, cmdStreamID.getIdAsLong(), 0, Instant.MIN);
        CmdKey toKey = new CmdKey(0, cmdStreamID.getIdAsLong(), Long.MAX_VALUE, Instant.MAX);
        
        return map.subMap(fromKey, true, toKey, true).entrySet().stream();
    }


    @Override
    public Stream<Entry<BigId, ICommandData>> selectEntries(CommandFilter filter, Set<CommandField> fields)
    {
        Stream<Entry<CmdKey, ICommandData>> resultStream = null;
        
        // fetch commands directly in case of filtering by internal IDs
        if (filter.getInternalIDs() != null)
        {
            resultStream = filter.getInternalIDs().stream()
                .map(k -> {
                    var cmdKey = toInternalKey(k);
                    var cmd = map.get(cmdKey);
                    return cmd != null ?
                        (Entry<CmdKey, ICommandData>)new AbstractMap.SimpleEntry<>(cmdKey, cmd) :
                        null;
                })
                .filter(Objects::nonNull);
        }
        
        // if no command stream filter used, scan all commands
        else if (filter.getCommandStreamFilter() == null)
        {
            resultStream = map.entrySet().stream();
        }
        
        else
        {
            // stream from list of selected command streams
            resultStream = DataStoreUtils.selectCommandStreamIDs(cmdStreamStore, filter.getCommandStreamFilter())
                .flatMap(id -> getCommandsByCommandStream(id));
        }
        
        // post filter on status
        if (filter.getStatusFilter() != null)
        {
            resultStream = resultStream.filter(cmd -> {
                return cmdStatusStore.getStatusByCommand(cmd.getKey())
                    .anyMatch(status -> filter.getStatusFilter().test(status.getValue()));
            });
        }
        
        // create comparator for time sort
        Comparator<Entry<CmdKey, ICommandData>> comparator = Comparator.comparing(e -> e.getKey().issueTime);
        if (filter.getIssueTime() != null && filter.getIssueTime().isDescendingOrder())
            comparator = comparator.reversed();
            
        // filter with predicate, sort by time and apply limit
        resultStream = resultStream
            .filter(e -> filter.test(e.getValue()))
            .sorted(comparator)
            .limit(filter.getLimit());
        
        // casting is ok since keys are subtypes of BigId
        @SuppressWarnings({ "unchecked", })
        var castedResultStream = (Stream<Entry<BigId, ICommandData>>)(Stream<?>)resultStream;
        return castedResultStream;
    }


    @Override
    public void clear()
    {
        map.clear();
    }


    @Override
    public boolean containsKey(Object key)
    {
        return key instanceof BigId && map.containsKey(toInternalKey(key));
    }


    @Override
    public boolean containsValue(Object val)
    {
        return map.containsValue(val);
    }


    public Set<Entry<BigId, ICommandData>> entrySet()
    {
        return new AbstractSet<Entry<BigId, ICommandData>>()
        {
            @Override
            public Iterator<Entry<BigId, ICommandData>> iterator()
            {
                // TODO Auto-generated method stub
                return null;
            }

            @Override
            public int size()
            {
                return map.size();
            }
        };
    }


    @Override
    public ICommandData get(Object key)
    {
        var k = toInternalKey(key);
        return map.get(k);
    }


    @Override
    public ICommandData put(BigId key, ICommandData cmd)
    {
        var cmdKey = toInternalKey(key);
        var oldCmd = map.replace(cmdKey, cmd);
        
        if (oldCmd == null)
            throw new IllegalArgumentException("put can only be used to update existing keys");
        
        return oldCmd;
    }


    @Override
    public BigId add(ICommandData cmd)
    {
        CmdKey key = new CmdKey(
            idScope,
            cmd.getCommandStreamID().getIdAsLong(),
            cmd.getFoiID().getIdAsLong(),
            cmd.getIssueTime());
        
        // add new command and remove older ones atomically
        map.compute(key, (k,v) -> {
            removeOlderCommands(key, cmd);
            return cmd;
        });
        
        return key;
    }
    
    
    protected void removeOlderCommands(CmdKey newKey, ICommandData newCmd)
    {
        var first = new CmdKey(0,
            newCmd.getCommandStreamID().getIdAsLong(),
            newCmd.getFoiID().getIdAsLong(),
            Instant.MIN);
        
        var last = new CmdKey(0,
            newCmd.getCommandStreamID().getIdAsLong(),
            newCmd.getFoiID().getIdAsLong(),
            Instant.MAX);
        
        // remove all other commands for same stream/foi combination
        var it = map.subMap(first, last).keySet().iterator();
        while (it.hasNext())
        {
            var oldKey = it.next();
            if (oldKey != newKey)
            {
                cmdStatusStore.removeAllStatus(oldKey);
                it.remove();
            }
        }
    }


    @Override
    public boolean isEmpty()
    {
        return map.isEmpty();
    }


    @Override
    public Set<BigId> keySet()
    {
        return map.keySet().stream()
            .collect(Collectors.toSet());
    }


    @Override
    public int size()
    {
        return map.size();
    }


    @Override
    public Collection<ICommandData> values()
    {
        return Collections.unmodifiableCollection(map.values());
    }


    public boolean remove(Object key, Object val)
    {
        return map.remove(key, val);
    }


    @Override
    public ICommandData remove(Object key)
    {
        return map.remove(toInternalKey(key));
    }


    @Override
    public long getNumRecords()
    {
        return map.size();
    }


    @Override
    public Stream<CommandStats> getStatistics(CommandStatsQuery query)
    {
        // TODO Auto-generated method stub
        return null;
    }


    @Override
    public ICommandStatusStore getStatusReports()
    {
        return cmdStatusStore;
    }


    @Override
    public ICommandStreamStore getCommandStreams()
    {
        return cmdStreamStore;
    }
    
    
    @Override
    public void linkTo(IFoiStore foiStore)
    {
        this.foiStore = Asserts.checkNotNull(foiStore, IFoiStore.class);
    }
    
}
