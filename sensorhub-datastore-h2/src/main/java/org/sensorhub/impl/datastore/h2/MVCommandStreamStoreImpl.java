/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2020 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.datastore.h2;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.AbstractMap.SimpleEntry;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.h2.mvstore.DecisionMakerWithError;
import org.h2.mvstore.MVBTreeMap;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.MVVarLongDataType;
import org.h2.mvstore.RangeCursor;
import org.h2.mvstore.MVMap.Decision;
import org.sensorhub.api.command.ICommandStreamInfo;
import org.sensorhub.api.datastore.DataStoreException;
import org.sensorhub.api.datastore.IdProvider;
import org.sensorhub.api.datastore.TemporalFilter;
import org.sensorhub.api.datastore.command.CommandStreamFilter;
import org.sensorhub.api.datastore.command.CommandStreamKey;
import org.sensorhub.api.datastore.command.ICommandStore.CommandField;
import org.sensorhub.api.datastore.system.ISystemDescStore;
import org.sensorhub.api.datastore.command.ICommandStreamStore;
import org.sensorhub.impl.datastore.DataStoreUtils;
import org.sensorhub.impl.datastore.command.CommandStreamInfoWrapper;
import org.sensorhub.impl.datastore.h2.H2Utils.Holder;
import org.sensorhub.impl.datastore.h2.MVDatabaseConfig.IdProviderType;
import org.sensorhub.impl.datastore.h2.index.FullTextIndex;
import org.vast.util.Asserts;
import org.vast.util.TimeExtent;


/**
 * <p>
 * Command stream Store implementation based on H2 MVStore.
 * </p>
 *
 * @author Alex Robin
 * @date Mar 24, 2021
 */
public class MVCommandStreamStoreImpl implements ICommandStreamStore
{
    private static final String CMDSTREAM_MAP_NAME = "cmdstreams_records";
    private static final String CDMSTREAM_SYSTEM_MAP_NAME = "cmdstreams_sys";
    private static final String CMDSTREAM_FULLTEXT_MAP_NAME = "cmdstreams_text";

    protected MVStore mvStore;
    protected MVCommandStoreImpl commandStore;
    protected IdProvider<ICommandStreamInfo> idProvider;
    protected int idScope;
    protected ISystemDescStore systemStore;
    
    /*
     * Main index
     */
    protected MVBTreeMap<CommandStreamKey, ICommandStreamInfo> cmdStreamIndex;
    
    /*
     * System/param index
     * Map of {system ID, param name, validTime} to commandstream ID
     * Everything is stored in the key with no value (use MVVoidDataType for efficiency)
     */
    protected MVBTreeMap<MVTimeSeriesSystemKey, Boolean> cmdStreamBySystemIndex;
    
    /*
     * Full text index pointing to main index
     * Key references are parentID/internalID pairs
     */
    protected FullTextIndex<ICommandStreamInfo, Long> fullTextIndex;
    
    
    /*
     * CommandStreamInfo object wrapper used to compute time ranges lazily
     */
    protected class CommandStreamInfoWithTimeRanges extends CommandStreamInfoWrapper
    {
        long csID;
        TimeExtent validTime;
        TimeExtent executionTimeRange;
        TimeExtent issueTimeRange;
        
        CommandStreamInfoWithTimeRanges(long internalID, ICommandStreamInfo csInfo)
        {
            super(csInfo);
            this.csID = internalID;
        }

        @Override
        public TimeExtent getValidTime()
        {
            if (validTime == null)
            {
                validTime = super.getValidTime();
                
                // if valid time ends at now and there is a more recent version, compute the actual end time
                if (validTime.endsNow())
                {
                    var sysDsKey = new MVTimeSeriesSystemKey(
                        getSystemID().getInternalID().getIdAsLong(),
                        getControlInputName(),
                        getValidTime().begin());
                    
                    var nextKey = cmdStreamBySystemIndex.lowerKey(sysDsKey);  // use lower cause time sorting is reversed
                    if (nextKey != null &&
                        nextKey.systemID == sysDsKey.systemID &&
                        nextKey.signalName.equals(sysDsKey.signalName))
                        validTime = TimeExtent.period(validTime.begin(), Instant.ofEpochSecond(nextKey.validStartTime));
                }
            }
            
            return validTime;
        }     
        
        @Override
        public TimeExtent getExecutionTimeRange()
        {
            if (executionTimeRange == null)
                executionTimeRange = commandStore.cmdStatusStore.getCommandStreamReportTimeRange(csID);
            
            return executionTimeRange;
        }        
        
        @Override
        public TimeExtent getIssueTimeRange()
        {
            if (issueTimeRange == null)
                issueTimeRange = commandStore.getCommandStreamIssueTimeRange(csID);
            
            return issueTimeRange;
        }
    }


    public MVCommandStreamStoreImpl(MVCommandStoreImpl cmdStore, IdProvider<ICommandStreamInfo> idProvider)
    {
        init(cmdStore, idProvider);
    }


    public MVCommandStreamStoreImpl(MVCommandStoreImpl cmdStore, IdProviderType idProviderType)
    {
        // create ID provider
        IdProvider<ICommandStreamInfo> idProvider;
        switch (idProviderType)
        {
            case UID_HASH:
                idProvider = DataStoreUtils.getCommandStreamHashIdProvider(784122258);
                break;
                
            default:
            case SEQUENTIAL:
                idProvider = dsInfo -> {
                    if (cmdStreamIndex.isEmpty())
                        return 1;
                    else
                        return cmdStreamIndex.lastKey().getInternalID().getIdAsLong()+1;
                };
        }
        
        init(cmdStore, idProvider);
    }
    
    
    public void init(MVCommandStoreImpl cmdStore, IdProvider<ICommandStreamInfo> idProvider)
    {
        this.commandStore = Asserts.checkNotNull(cmdStore, MVCommandStoreImpl.class);
        this.mvStore = Asserts.checkNotNull(cmdStore.mvStore, MVStore.class);
        this.idProvider = Asserts.checkNotNull(idProvider, IdProvider.class);
        this.idScope = cmdStore.idScope;
        
        // persistent class mappings for Kryo
        var kryoClassMap = mvStore.openMap(MVObsSystemDatabase.KRYO_CLASS_MAP_NAME, new MVBTreeMap.Builder<String, Integer>());

        // open command stream map
        String mapName = cmdStore.getDatastoreName() + ":" + CMDSTREAM_MAP_NAME;
        this.cmdStreamIndex = mvStore.openMap(mapName, new MVBTreeMap.Builder<CommandStreamKey, ICommandStreamInfo>()
                .keyType(new MVCommandStreamKeyDataType(cmdStore.idScope))
                .valueType(new CommandStreamInfoDataType(kryoClassMap, idScope)));

        // command stream by system index
        mapName = cmdStore.getDatastoreName() + ":" + CDMSTREAM_SYSTEM_MAP_NAME;
        this.cmdStreamBySystemIndex = mvStore.openMap(mapName, new MVBTreeMap.Builder<MVTimeSeriesSystemKey, Boolean>()
                .keyType(new MVTimeSeriesSystemKeyDataType())
                .valueType(new MVVoidDataType()));
        
        // full-text index
        mapName = cmdStore.getDatastoreName() + ":" + CMDSTREAM_FULLTEXT_MAP_NAME;
        this.fullTextIndex = new FullTextIndex<>(mvStore, mapName, new MVVarLongDataType());

        // ID provider
        if (idProvider == null) // use default if nothing is set
        {
            this.idProvider = csInfo -> {
                if (cmdStreamIndex.isEmpty())
                    return 1;
                else
                    return cmdStreamIndex.lastKey().getInternalID().getIdAsLong()+1;
            };
        }
        else
            this.idProvider = idProvider;
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
        return new CommandStreamKey(idScope, idProvider.newInternalID(csInfo));
    }


    @Override
    public ICommandStreamInfo get(Object key)
    {
        var csKey = DataStoreUtils.checkCommandStreamKey(key);
        
        var csInfo = cmdStreamIndex.get(csKey);
        if (csInfo == null)
            return null;
        
        return new CommandStreamInfoWithTimeRanges(csKey.getInternalID().getIdAsLong(), csInfo);
    }


    Stream<Long> getCommandStreamIdsBySystem(long sysID, Set<String> outputNames, TemporalFilter validTime)
    {
        MVTimeSeriesSystemKey first = new MVTimeSeriesSystemKey(sysID, "", Instant.MIN);
        RangeCursor<MVTimeSeriesSystemKey, Boolean> cursor = new RangeCursor<>(cmdStreamBySystemIndex, first);

        Stream<MVTimeSeriesSystemKey> keyStream = cursor.keyStream()
            .takeWhile(k -> k.systemID == sysID);

        // we post filter output names and versions during the scan
        // since number of outputs and versions is usually small, this should
        // be faster than looking up each output separately
        return postFilterKeyStream(keyStream, outputNames, validTime)
            .map(k -> k.internalID);
    }


    Stream<Long> getCommandStreamIdsFromAllSystems(Set<String> outputNames, TemporalFilter validTime)
    {
        Stream<MVTimeSeriesSystemKey> keyStream = cmdStreamBySystemIndex.keySet().stream();

        // yikes we're doing a full index scan here!
        return postFilterKeyStream(keyStream, outputNames, validTime)
            .map(k -> k.internalID);
    }


    Stream<MVTimeSeriesSystemKey> postFilterKeyStream(Stream<MVTimeSeriesSystemKey> keyStream, Set<String> outputNames, TemporalFilter validTime)
    {
        if (outputNames != null)
            keyStream = keyStream.filter(k -> outputNames.contains(k.signalName));

        if (validTime != null)
        {
            // handle special case of current time & latest time
            long filterStartTime, filterEndTime;
            if (validTime.isCurrentTime()) {
                filterStartTime = filterEndTime = Instant.now().getEpochSecond();
            } else if (validTime.isLatestTime()) {
                filterStartTime = filterEndTime = Instant.MAX.getEpochSecond();
            } else {
                filterStartTime = validTime.getMin().getEpochSecond();
                filterEndTime = validTime.getMax().getEpochSecond();
            }
            
            // get all datastream with validStartTime within the filter range + 1 before
            // recall that datastreams are ordered in reverse valid time order
            Holder<MVTimeSeriesSystemKey> lastKey = new Holder<>();
            keyStream = keyStream
                .filter(k -> {
                    MVTimeSeriesSystemKey saveLastKey = lastKey.value;
                    lastKey.value = k;
                                            
                    if (k.validStartTime > filterEndTime)
                        return false;
                    
                    if (k.validStartTime >= filterStartTime)
                        return true;
                    
                    if (saveLastKey == null ||
                        k.systemID != saveLastKey.systemID ||
                        !k.signalName.equals(saveLastKey.signalName) ||
                        saveLastKey.validStartTime > filterStartTime) {
                        return true;
                    }
                    
                    return false;
                });
        }

        return keyStream;
    }


    @Override
    public Stream<Entry<CommandStreamKey, ICommandStreamInfo>> selectEntries(CommandStreamFilter filter, Set<CommandStreamInfoField> fields)
    {
        Stream<Long> idStream = null;
        Stream<Entry<CommandStreamKey, ICommandStreamInfo>> resultStream;
        boolean fullTextFilterApplied = false;
        
        // if filtering by internal IDs, use these IDs directly
        if (filter.getInternalIDs() != null)
        {
            idStream = filter.getInternalIDs().stream()
                .map(id -> id.getIdAsLong());
        }

        // if system filter is used
        else if (filter.getSystemFilter() != null)
        {
            // first select systems and fetch corresponding command streams
            idStream = DataStoreUtils.selectSystemIDs(systemStore, filter.getSystemFilter())
                .flatMap(id -> getCommandStreamIdsBySystem(id.getIdAsLong(), filter.getControlInputNames(), filter.getValidTimeFilter()));
        }

        // if command filter is used
        else if (filter.getCommandFilter() != null)
        {
            // get all command stream IDs referenced by commands matching the filter
            idStream = commandStore.select(filter.getCommandFilter(), CommandField.COMMANDSTREAM_ID)
                .map(cmd -> cmd.getCommandStreamID().getIdAsLong());
        }
        
        // if full-text filter is used, use full-text index as primary
        else if (filter.getFullTextFilter() != null)
        {
            idStream = fullTextIndex.selectKeys(filter.getFullTextFilter());
            fullTextFilterApplied = true;
        }

        // else filter command stream only by input name and valid time
        else
        {
            idStream = getCommandStreamIdsFromAllSystems(filter.getControlInputNames(), filter.getValidTimeFilter());
        }

        resultStream = idStream
            .map(id -> cmdStreamIndex.getEntry(new CommandStreamKey(idScope, id)))
            .filter(Objects::nonNull);
        
        if (filter.getFullTextFilter() != null && !fullTextFilterApplied)
            resultStream = resultStream.filter(e -> filter.testFullText(e.getValue()));
        
        if (filter.getTaskableProperties() != null)
            resultStream = resultStream.filter(e -> filter.testTaskableProperty(e.getValue()));

        if (filter.getValuePredicate() != null)
            resultStream = resultStream.filter(e -> filter.testValuePredicate(e.getValue()));

        // apply limit
        if (filter.getLimit() < Long.MAX_VALUE)
            resultStream = resultStream.limit(filter.getLimit());

        // always wrap with dynamic command stream object
        resultStream = resultStream.map(e -> {
            return new SimpleEntry<CommandStreamKey, ICommandStreamInfo>(
                e.getKey(),
                new CommandStreamInfoWithTimeRanges(e.getKey().getInternalID().getIdAsLong(), e.getValue())
            ); 
        });
        
        // apply time filter once validTime is computed correctly
        if (filter.getValidTimeFilter() != null)
            resultStream = resultStream.filter(e -> filter.testValidTime(e.getValue()));
        
        return resultStream;
    }


    @Override
    public synchronized ICommandStreamInfo put(CommandStreamKey key, ICommandStreamInfo csInfo)
    {
        DataStoreUtils.checkCommandStreamKey(key);
        try {
            
            DataStoreUtils.checkCommandStreamInfo(systemStore, csInfo);
            return put(key, csInfo, true);
        }
        catch (DataStoreException e) 
        {
            throw new IllegalArgumentException(e);
        }
    }
    
    
    protected ICommandStreamInfo put(CommandStreamKey key, ICommandStreamInfo csInfo, boolean replace) throws DataStoreException
    {
        var csID = key.getInternalID().getIdAsLong();
        
        // abort if another commandstream already exists with same sys/input/validtime combination
        MVTimeSeriesSystemKey sysCsAssocKey = new MVTimeSeriesSystemKey(csID,
            csInfo.getSystemID().getInternalID().getIdAsLong(),
            csInfo.getControlInputName(),
            csInfo.getValidTime().begin().getEpochSecond());
        if (!replace && cmdStreamBySystemIndex.containsKey(sysCsAssocKey))
            throw new DataStoreException(DataStoreUtils.ERROR_EXISTING_COMMANDSTREAM);
        
        // add to main index, conditionally
        var decisionMaker = new DecisionMakerWithError<ICommandStreamInfo>() {
            @Override
            public Decision decide(ICommandStreamInfo existingValue, ICommandStreamInfo providedValue)
            {
                if (existingValue != null && !replace) {
                    this.error = new DataStoreException(DataStoreUtils.ERROR_EXISTING_COMMANDSTREAM);
                    return Decision.ABORT;
                }
                
                return Decision.PUT;
            }
        };
        
        var oldValue = cmdStreamIndex.operate(key, csInfo, decisionMaker);
        boolean isNewEntry = (oldValue == null);
            
        // update sys/input index
        // remove old entry if needed
        if (oldValue != null && replace)
        {
            MVTimeSeriesSystemKey oldSysCsAssocKey = new MVTimeSeriesSystemKey(csID,
                oldValue.getSystemID().getInternalID().getIdAsLong(),
                oldValue.getControlInputName(),
                oldValue.getValidTime().begin().getEpochSecond());
            cmdStreamBySystemIndex.remove(oldSysCsAssocKey);
        }

        // add new system assoc
        cmdStreamBySystemIndex.put(sysCsAssocKey, Boolean.TRUE);
        
        // update full-text index
        if (isNewEntry)
            fullTextIndex.add(csID, csInfo);
        else
            fullTextIndex.update(csID, oldValue, csInfo);
        
        return oldValue;
    }


    @Override
    public synchronized ICommandStreamInfo remove(Object key)
    {
        var csKey = DataStoreUtils.checkCommandStreamKey(key);
        var csID = csKey.getInternalID().getIdAsLong();

        // remove all commands
        if (commandStore != null)
            commandStore.removeAllCommandsAndSeries(csID);
        
        // remove from main index
        ICommandStreamInfo oldValue = cmdStreamIndex.remove(csKey);
        if (oldValue == null)
            return null;

        // remove entry in secondary index
        cmdStreamBySystemIndex.remove(new MVTimeSeriesSystemKey(
            oldValue.getSystemID().getInternalID().getIdAsLong(),
            oldValue.getControlInputName(),
            oldValue.getValidTime().begin()));
        
        // remove from full-text index
        fullTextIndex.remove(csID, oldValue);

        return oldValue;
    }


    @Override
    public synchronized void clear()
    {
        commandStore.clear();
        cmdStreamBySystemIndex.clear();
        fullTextIndex.clear();
        cmdStreamIndex.clear();
    }


    @Override
    public boolean containsKey(Object key)
    {
        var dsKey = DataStoreUtils.checkCommandStreamKey(key);
        return cmdStreamIndex.containsKey(dsKey);
    }


    @Override
    public boolean containsValue(Object val)
    {
        return cmdStreamIndex.containsValue(val);
    }


    @Override
    public Set<Entry<CommandStreamKey, ICommandStreamInfo>> entrySet()
    {
        return cmdStreamIndex.entrySet();
    }


    @Override
    public boolean isEmpty()
    {
        return cmdStreamIndex.isEmpty();
    }


    @Override
    public Set<CommandStreamKey> keySet()
    {
        return cmdStreamIndex.keySet();
    }


    @Override
    public String getDatastoreName()
    {
        return commandStore.getDatastoreName();
    }


    @Override
    public long getNumRecords()
    {
        return cmdStreamIndex.sizeAsLong();
    }


    @Override
    public int size()
    {
        return cmdStreamIndex.size();
    }


    @Override
    public Collection<ICommandStreamInfo> values()
    {
        return cmdStreamIndex.values();
    }


    @Override
    public void commit()
    {
        commandStore.commit();
    }


    @Override
    public void backup(OutputStream is) throws IOException
    {
        throw new UnsupportedOperationException("Call backup on the parent observation store");
    }


    @Override
    public void restore(InputStream os) throws IOException
    {
        throw new UnsupportedOperationException("Call restore on the parent observation store");
    }


    @Override
    public boolean isReadOnly()
    {
        return mvStore.isReadOnly();
    }
    
    
    @Override
    public void linkTo(ISystemDescStore systemStore)
    {
        this.systemStore = Asserts.checkNotNull(systemStore, ISystemDescStore.class);
    }
}
