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
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.sensorhub.api.command.ICommandStatus;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.datastore.TemporalFilter;
import org.sensorhub.api.datastore.command.CommandStatusFilter;
import org.sensorhub.api.datastore.command.ICommandStatusStore;
import org.sensorhub.utils.AtomicInitializer;
import org.sensorhub.utils.ObjectUtils;
import org.vast.util.Asserts;


/**
 * <p>
 * In-memory implementation of a command status store backed by a {@link NavigableMap}.
 * This implementation is only used to store the status reports for the
 * latest received command in each command stream.
 * </p>
 *
 * @author Alex Robin
 * @date Jan 4, 2022
 */
public class InMemoryCommandStatusStore extends InMemoryDataStore implements ICommandStatusStore
{
    static final TemporalFilter ALL_TIMES_FILTER = new TemporalFilter.Builder().withAllTimes().build();
    
    final NavigableMap<StatusKey, ICommandStatus> map = new ConcurrentSkipListMap<>(new StatusKeyComparator());
    final InMemoryCommandStore cmdStore;
    
    
    static class StatusKey implements BigId
    {
        int scope;
        BigId cmdID;
        Instant reportTime = null;
        AtomicInitializer<byte[]> cachedId = new AtomicInitializer<>();
        
        StatusKey(int scope, BigId cmdID, Instant reportTime)
        {
            this.scope = scope;
            this.cmdID = cmdID;
            this.reportTime = reportTime;
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
                byte[] cmdIDBytes = cmdID.getIdAsBytes();
                ByteBuffer buf = ByteBuffer.allocate(cmdIDBytes.length + 13); // 1+8+4
                buf.put((byte)cmdIDBytes.length);
                buf.put(cmdIDBytes);
                buf.putInt((int)(reportTime.getEpochSecond()));
                buf.putInt(reportTime.getNano());
                return buf.array();
            });
        }
        
        @Override
        public int hashCode()
        {
            return Objects.hash(
                scope, cmdID, reportTime
            );
        }
        
        @Override
        public boolean equals(Object o)
        {
            if (!(o instanceof StatusKey))
                return false;
            
            var other = (StatusKey)o;
            return scope == other.scope &&
                Objects.equals(cmdID, other.cmdID) &&
                Objects.equals(reportTime, other.reportTime);
        }

        @Override
        public String toString()
        {
            return ObjectUtils.toString(this, true);
        }
    }
    
    
    private static class StatusKeyComparator implements Comparator<StatusKey>
    {
        @Override
        public int compare(StatusKey k1, StatusKey k2)
        {
            // first compare command IDs
            int comp = BigId.compare(k1.cmdID, k2.cmdID);
            if (comp != 0)
                return comp;
            
            // then compare report times
            return k1.reportTime.compareTo(k2.reportTime);
        }
    }
    
    
    public InMemoryCommandStatusStore(InMemoryCommandStore cmdStore)
    {
        this.cmdStore = Asserts.checkNotNull(cmdStore, InMemoryCommandStore.class);
    }
    
    
    StatusKey toInternalKey(Object keyObj)
    {
        Asserts.checkArgument(keyObj instanceof BigId);
        BigId key = (BigId)keyObj;

        try
        {
            // parse from BigInt
            ByteBuffer buf = ByteBuffer.wrap(key.getIdAsBytes());
            int cmdIdLen = buf.get();
            BigId cmdID = BigId.fromBytes(0, buf.array(), 1, cmdIdLen);
            buf.position(cmdIdLen+1);
            Instant reportTime = Instant.ofEpochSecond(buf.getInt(), buf.getInt());
            return new StatusKey(0, cmdID, reportTime);
        }
        catch (Exception e)
        {
            // invalid bigint key
            // return key object that will never match
            return new StatusKey(0, BigId.NONE, Instant.MAX);
        }
    }
    
    
    Stream<Entry<StatusKey, ICommandStatus>> getStatusByCommand(BigId cmdKey)
    {
        StatusKey fromKey = new StatusKey(0, cmdKey, Instant.MIN);
        StatusKey toKey = new StatusKey(0, cmdKey, Instant.MAX);
        return map.subMap(fromKey, true, toKey, true).entrySet().stream();
    }


    @Override
    public Stream<Entry<BigId, ICommandStatus>> selectEntries(CommandStatusFilter filter, Set<CommandStatusField> fields)
    {
        Stream<Entry<StatusKey, ICommandStatus>> resultStream = null;
        
        // if no command filter used, scan all status reports
        if (filter.getCommandFilter() == null)
        {
            resultStream = map.entrySet().stream();
        }
        
        else
        {
            // stream from list of selected commands
            resultStream = cmdStore.selectEntries(filter.getCommandFilter())
                .flatMap(entry -> getStatusByCommand(entry.getKey()));
        }
            
        // create comparator for time sort
        Comparator<Entry<StatusKey, ICommandStatus>> comparator = Comparator.comparing(e -> e.getKey().reportTime);
        if (filter.getReportTime() != null && filter.getReportTime().isDescendingOrder())
            comparator = comparator.reversed();
            
        // filter with predicate and apply limit
        resultStream = resultStream
            .filter(e -> filter.test(e.getValue()))
            .sorted(comparator)
            .limit(filter.getLimit());
        
        // casting is ok since keys are subtypes of BigId
        @SuppressWarnings({ "unchecked", })
        var castedResultStream = (Stream<Entry<BigId, ICommandStatus>>)(Stream<?>)resultStream;
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


    public Set<Entry<BigId, ICommandStatus>> entrySet()
    {
        return new AbstractSet<Entry<BigId, ICommandStatus>>()
        {
            @Override
            public Iterator<Entry<BigId, ICommandStatus>> iterator()
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
    public ICommandStatus get(Object key)
    {
        var k = toInternalKey(key);
        return map.get(k);
    }


    @Override
    public ICommandStatus put(BigId key, ICommandStatus cmd)
    {
        StatusKey cmdKey = toInternalKey(key);
        ICommandStatus oldCmd = map.replace(cmdKey, cmd);
        
        if (oldCmd == null)
            throw new IllegalArgumentException("put can only be used to update status entries");
        
        return oldCmd;
    }


    @Override
    public BigId add(ICommandStatus status)
    {
        StatusKey key = new StatusKey(
            cmdStore.idScope,
            status.getCommandID(),
            status.getReportTime());
        map.remove(key);
        map.put(key, status);
        return key;
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
    public Collection<ICommandStatus> values()
    {
        return Collections.unmodifiableCollection(map.values());
    }


    public boolean remove(Object key, Object val)
    {
        return map.remove(key, val);
    }


    @Override
    public ICommandStatus remove(Object key)
    {
        return map.remove(toInternalKey(key));
    }
    
    
    protected void removeAllStatus(BigId cmdID)
    {
        // remove all series and commands
        var first = new StatusKey(0, cmdID, Instant.MIN);
        var last = new StatusKey(0, cmdID, Instant.MAX);
        map.subMap(first, last).clear();
    }


    @Override
    public long getNumRecords()
    {
        return map.size();
    }
    
}
