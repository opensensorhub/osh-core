/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2020 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys.obs;

import java.io.IOException;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.data.IDataStreamInfo;
import org.sensorhub.api.data.IObsData;
import org.sensorhub.api.data.ObsEvent;
import org.sensorhub.api.database.IObsSystemDatabase;
import org.sensorhub.api.datastore.DataStoreException;
import org.sensorhub.api.datastore.SpatialFilter;
import org.sensorhub.api.datastore.TemporalFilter;
import org.sensorhub.api.datastore.obs.DataStreamFilter;
import org.sensorhub.api.datastore.obs.DataStreamKey;
import org.sensorhub.api.datastore.obs.IObsStore;
import org.sensorhub.api.datastore.obs.ObsFilter;
import org.sensorhub.api.event.EventUtils;
import org.sensorhub.api.event.IEventBus;
import org.sensorhub.impl.service.consys.InvalidRequestException;
import org.sensorhub.impl.datastore.DataStoreUtils;
import org.sensorhub.impl.service.consys.HandlerContext;
import org.sensorhub.impl.service.consys.ServiceErrors;
import org.sensorhub.impl.service.consys.RestApiServlet.ResourcePermissions;
import org.sensorhub.impl.service.consys.resource.BaseResourceHandler;
import org.sensorhub.impl.service.consys.resource.RequestContext;
import org.sensorhub.impl.service.consys.resource.ResourceBinding;
import org.sensorhub.impl.service.consys.resource.ResourceFormat;
import org.sensorhub.impl.service.consys.resource.RequestContext.ResourceRef;
import org.sensorhub.impl.service.consys.stream.StreamHandler;
import org.sensorhub.impl.system.DataStreamTransactionHandler;
import org.sensorhub.impl.system.SystemDatabaseTransactionHandler;
import org.sensorhub.utils.CallbackException;
import org.vast.util.Asserts;
import com.google.common.base.Objects;
import net.opengis.swe.v20.BinaryEncoding;


public class ObsHandler extends BaseResourceHandler<BigId, IObsData, ObsFilter, IObsStore>
{
    public static final int EXTERNAL_ID_SEED = 71145893;
    public static final String[] NAMES = { "observations" };
    
    final IEventBus eventBus;
    final IObsSystemDatabase db;
    final SystemDatabaseTransactionHandler transactionHandler;
    final ScheduledExecutorService threadPool;
    final Map<String, CustomObsFormat> customFormats;
    
    
    public static class ObsHandlerContextData
    {
        public BigId dsID;
        public IDataStreamInfo dsInfo;
        public BigId foiId;
        public DataStreamTransactionHandler dsHandler;
    }
    
    
    public ObsHandler(HandlerContext ctx, ScheduledExecutorService threadPool, ResourcePermissions permissions, Map<String, CustomObsFormat> customFormats)
    {
        super(ctx.getReadDb().getObservationStore(), ctx.getObsIdEncoder(), ctx, permissions);
        
        this.eventBus = ctx.getEventBus();
        this.db = ctx;
        this.transactionHandler = new SystemDatabaseTransactionHandler(eventBus, ctx.getWriteDb());
        this.threadPool = threadPool;
        this.customFormats = Asserts.checkNotNull(customFormats);
    }
    
    
    @Override
    protected ResourceBinding<BigId, IObsData> getBinding(RequestContext ctx, boolean forReading) throws IOException
    {
        var contextData = new ObsHandlerContextData();
        ctx.setData(contextData);
        
        // try to fetch datastream since it's needed to configure binding
        var dsID = getParentDataStreamID(ctx);
        if (dsID != null)
            contextData.dsInfo = db.getDataStreamStore().get(new DataStreamKey(dsID));
                
        if (forReading)
        {
            // when ingesting obs, datastream should be known at this stage
            Asserts.checkNotNull(contextData.dsInfo, IDataStreamInfo.class);
            
            // create transaction handler here so it can be reused multiple times
            contextData.dsID = dsID;
            contextData.dsHandler = transactionHandler.getDataStreamHandler(dsID);
            if (contextData.dsHandler == null)
                throw ServiceErrors.notWritable();
            
            // try to parse featureOfInterest argument
            String foiArg = ctx.getParameter("foi");
            if (foiArg != null)
            {
                var foiID = decodeID(foiArg);
                if (!db.getFoiStore().contains(foiID))
                    throw ServiceErrors.badRequest("Invalid FOI ID");
                contextData.foiId = foiID;
            }
            else
                contextData.foiId = BigId.NONE;
        }
        
        // select binding depending on format
        // use a custom format if auto-detected or available for selected mime type
        // do this only if data from single datastream is requested
        if (contextData.dsInfo != null)
        {
            var binding = getCustomFormatBinding(ctx, contextData.dsInfo);
            if (binding != null)
                return binding;
        }
        
        // otherwise use standard formats
        // default to OM JSON
        var format = ctx.getFormat();
        if (format.equals(ResourceFormat.AUTO))
        {
            if (contextData.dsInfo != null && contextData.dsInfo.getRecordEncoding() instanceof BinaryEncoding)
                format = ResourceFormat.SWE_BINARY;
            else
                format = ResourceFormat.JSON;
            ctx.setFormat(format);
        }
        
        if (format.isOneOf(ResourceFormat.JSON, ResourceFormat.OM_JSON))
            return new ObsBindingOmJson(ctx, idEncoders, forReading, dataStore);
        else
            return new ObsBindingSweCommon(ctx, idEncoders, forReading, dataStore);
    }
    
    
    protected ResourceBinding<BigId, IObsData> getCustomFormatBinding(RequestContext ctx, IDataStreamInfo dsInfo) throws IOException
    {
        var format = ctx.getFormat();
        CustomObsFormat obsFormat = null;
        
        // try to auto select format when requesting from browser
        if (format.equals(ResourceFormat.AUTO) && isHttpRequestFromBrowser(ctx))
        {
            for (var entry: customFormats.entrySet())
            {
                var mimeType = entry.getKey();
                var formatImpl = entry.getValue();
                
                if (formatImpl.isAutoSelectable(dsInfo))
                {
                    obsFormat = formatImpl;
                    ctx.getLogger().info("Auto-selecting format {}", mimeType);
                    break;
                }
            }
        }
        
        // otherwise just use format implementation for selected mime type
        if (obsFormat == null)
            obsFormat = customFormats.get(format.getMimeType());
        
        return obsFormat != null ? obsFormat.getObsBinding(ctx, idEncoders, dsInfo) : null;
    }
    
    
    /*
     * Check if request comes from a compatible browser
     */
    protected boolean isHttpRequestFromBrowser(RequestContext ctx)
    {
        // don't do multipart with websockets or MQTT!
        if (ctx.isStreamRequest())
            return false;
        
        String userAgent = ctx.getRequestHeader("User-Agent");
        if (userAgent == null)
            return false;

        if (userAgent.contains("Firefox") ||
            userAgent.contains("Chrome") ||
            userAgent.contains("Safari"))
            return true;

        return false;
    }
    
    
    /*
     * Observations are also mounted under sampling features, so the parent
     * is only a datastream when it was reached through the datastream handler
     */
    protected BigId getParentDataStreamID(RequestContext ctx)
    {
        var parent = ctx.getParentRef();
        return parent.type instanceof DataStreamHandler ? parent.internalID : null;
    }
    
    
    @Override
    public void doPost(RequestContext ctx) throws IOException
    {
        if (ctx.isEndOfPath() &&
            !(ctx.getParentRef().type instanceof DataStreamHandler))
            throw ServiceErrors.unsupportedOperation("Observations can only be created within a Datastream");
        
        super.doPost(ctx);
    }
    
    
    protected void subscribe(final RequestContext ctx) throws InvalidRequestException, IOException
    {
        ctx.getSecurityHandler().checkPermission(permissions.stream);
        Asserts.checkNotNull(ctx.getStreamHandler(), StreamHandler.class);
        
        var dsID = getParentDataStreamID(ctx);
        if (dsID == null)
            throw ServiceErrors.badRequest("Streaming is only supported on a specific datastream");
        
        var queryParams = ctx.getParameterMap();
        var filter = getFilter(ctx.getParentRef(), queryParams, 0, Long.MAX_VALUE);
        var responseFormat = parseFormat(queryParams);
        ctx.setFormatOptions(responseFormat, parseSelectArg(queryParams));
        
        // detect if real-time request
        boolean isRealTime = (filter.getPhenomenonTime() == null && filter.getResultTime() == null) ||
                             (filter.getResultTime() != null && filter.getResultTime().beginsNow()) ||
                             (filter.getPhenomenonTime() != null && filter.getPhenomenonTime().beginsNow());
        boolean includeLatest = (filter.getPhenomenonTime() != null && filter.getPhenomenonTime().isLatestTime()) ||
                                (filter.getResultTime() != null && filter.getResultTime().isLatestTime());
        
        // parse replaySpeed param
        var replaySpeedOrNull = parseDoubleArg("replaySpeed", ctx.getParameterMap());
        var replaySpeed = replaySpeedOrNull != null ? replaySpeedOrNull.doubleValue() : 1.0;
        
        // continue when streaming actually starts
        ctx.getStreamHandler().setStartCallback(() -> {
            
            try
            {
                // init binding and get datastream info
                var binding = getBinding(ctx, false);
                
                if (isRealTime)
                    startRealTimeStream(ctx, dsID, filter, binding, includeLatest);
                else
                    startReplayStream(ctx, dsID, filter, replaySpeed, binding);
            }
            catch (IOException e)
            {
                throw new IllegalStateException("Error initializing binding", e);
            }
        });
    }
    
    
    protected void startRealTimeStream(final RequestContext ctx, final BigId dsID, final ObsFilter filter, final ResourceBinding<BigId, IObsData> binding, boolean includeLatest)
    {
        // init event to obs converter
        var dsInfo = ((ObsHandlerContextData)ctx.getData()).dsInfo;
        var streamHandler = ctx.getStreamHandler();
        
        // if FOI filter present, lookup acceptable feature IDs
        var foiIDs = filter.getFoiFilter() != null ?
            DataStoreUtils.selectFeatureIDs(db.getFoiStore(), filter.getFoiFilter()).collect(Collectors.toSet()) : null;
        
        // create subscriber
        var subscriber = new Subscriber<ObsEvent>() {
            volatile Subscription subscription;
            volatile Instant latestObsTime;
            volatile boolean needDedup;
            
            @Override
            public void onSubscribe(Subscription subscription)
            {
                this.subscription = subscription;
                ctx.getLogger().debug("Starting real-time obs subscription #{}", System.identityHashCode(subscription));
                
                // first publish latest obs if requested
                if (includeLatest)
                {
                    db.getObservationStore().select(new ObsFilter.Builder()
                        .withDataStreams(dsID)
                        .withFois(filter.getFoiFilter())
                        .withLatestResult()
                        .build()).findFirst().ifPresent(latestObs -> {
                            latestObsTime = latestObs.getResultTime();
                            sendObs(latestObs);
                            needDedup = true;
                        });
                }
                
                // then request further obs
                subscription.request(Long.MAX_VALUE);
                
                // cancel subscription if streaming is stopped by client
                ctx.getStreamHandler().setCloseCallback(() -> {
                    subscription.cancel();
                    ctx.getLogger().debug("Cancelling real-time obs subscription #{}", System.identityHashCode(subscription));
                });
            }

            @Override
            public void onNext(ObsEvent event)
            {
                for (var obs: event.getObservations())
                {
                    if (foiIDs == null || foiIDs.contains(obs.getFoiID()))
                        sendObs(obs);
                }
            }
            
            protected void sendObs(IObsData obs)
            {
                try
                {
                    // dedup to avoid getting latest obs twice
                    // this can happen if latest obs is added to db concurrently with us starting the stream!
                    if (needDedup)
                    {
                        needDedup = false;
                        if (Objects.equal(latestObsTime, obs.getResultTime()))
                            return;
                    }
                    
                    binding.serialize(null, obs, false);
                    streamHandler.sendPacket();
                }
                catch (IOException e)
                {
                    subscription.cancel();
                    throw new CallbackException(e);
                }
            }

            @Override
            public void onError(Throwable e)
            {
                ctx.getLogger().error("Error while publishing real-time obs data", e);
            }

            @Override
            public void onComplete()
            {
                ctx.getLogger().debug("Ending real-time obs subscription #{}", System.identityHashCode(subscription));
                streamHandler.close();
            }
        };
        
        var topic = EventUtils.getDataStreamDataTopicID(dsInfo);
        eventBus.newSubscription(ObsEvent.class)
            .withTopicID(topic)
            .withEventType(ObsEvent.class)
            .subscribe(subscriber);
    }
    
    
    protected void startReplayStream(final RequestContext ctx, final BigId dsID, final ObsFilter filter, final double replaySpeed, final ResourceBinding<BigId, IObsData> binding)
    {
        var streamHandler = ctx.getStreamHandler();
        var dsInfo = ((ObsHandlerContextData)ctx.getData()).dsInfo;
        
        int batchSize = 100;
        var itemQueue = new ConcurrentLinkedQueue<Entry<BigId, IObsData>>();
        var obsIterator = dataStore.selectEntries(filter).iterator();
        if (!obsIterator.hasNext())
        {
            streamHandler.close();
            return;
        }
        
        // init time params
        var startTime = filter.getPhenomenonTime() != null ?
            filter.getPhenomenonTime().getMin() : 
            filter.getResultTime().getMin();
        if (startTime == Instant.MIN)
            startTime = dsInfo.getPhenomenonTimeRange().begin();
        var requestStartTime = startTime.toEpochMilli();
        var requestSystemTime = System.currentTimeMillis();
        
        // cancel timer task if streaming is stopped by client
        var future = new AtomicReference<ScheduledFuture<?>>();
        ctx.getStreamHandler().setCloseCallback(() -> {
            var f = future.get();
            if (f != null)
            {
                future.get().cancel(true);
                ctx.getLogger().debug("Cancelling obs replay stream #{}", System.identityHashCode(streamHandler));
            }
        });
        
        ctx.getLogger().debug("Starting obs replay stream #{}", System.identityHashCode(streamHandler));
        future.set(threadPool.scheduleWithFixedDelay(() -> {
            
            try
            {
                //ctx.getLogger().debug("Replay loop called");
                
                if (itemQueue.size() <= batchSize)
                {
                    int i;
                    for (i = 0; i < batchSize && obsIterator.hasNext(); i++)
                        itemQueue.add(obsIterator.next());
                    //if (i > 0)
                    //    ctx.getLogger().debug("fetched batch of {} items", i);
                }
                
                // send all obs that are due
                while (!itemQueue.isEmpty())
                {
                    var nextItem = itemQueue.peek();
                    var nextId = nextItem.getKey();
                    var nextObs = nextItem.getValue();
                    
                    // slow down item dispatch at required replay speed
                    var deltaClockTime = (System.currentTimeMillis() - requestSystemTime) * replaySpeed;
                    var deltaObsTime = nextObs.getPhenomenonTime().toEpochMilli() - requestStartTime;
                    //ctx.getLogger().debug("delta clock time = {}ms", deltaClockTime);
                    //ctx.getLogger().debug("delta obs time = {}ms", deltaObsTime);
                    
                    // skip if it's not time to send this record yet
                    if (deltaObsTime > deltaClockTime)
                        break;
                
                    //ctx.getLogger().debug("sending obs at {}", nextItem.getPhenomenonTime());
                    itemQueue.poll();
                    binding.serialize(nextId, nextObs, false);
                    streamHandler.sendPacket();
                }
                
                // stop streaming if done
                if (itemQueue.isEmpty() && !obsIterator.hasNext())
                {
                    future.get().cancel(false);
                    streamHandler.close();
                    ctx.getLogger().debug("Ending obs replay stream #{}", System.identityHashCode(streamHandler));
            }
            }
            catch (IOException e)
            {
                throw new CompletionException(e);
            }
            
        }, 0, 10, TimeUnit.MILLISECONDS));
    }


    @Override
    protected BigId getKey(RequestContext ctx, String id) throws InvalidRequestException
    {
        return decodeID(id);
    }
    
    
    @Override
    protected String encodeKey(final RequestContext ctx, BigId key)
    {
        return idEncoder.encodeID(key);
    }


    @Override
    protected ObsFilter getFilter(ResourceRef parent, Map<String, String[]> queryParams, long offset, long limit) throws InvalidRequestException
    {
        var builder = new ObsFilter.Builder();
        
        // filter on parent if needed
        BigId parentDsID = null;
        boolean foiParent = false;
        if (parent.internalID != null)
        {
            if (parent.type instanceof DataStreamHandler)
                parentDsID = parent.internalID;
            else
            {
                builder.withFois(parent.internalID);
                foiParent = true;
            }
        }

        var phenomenonTimeFilterBuilder = new TemporalFilter.Builder();

        // phenomenonTime param
        var phenomenonTime = parseTimeStampArgToBuilder("phenomenonTime", queryParams);
        if (phenomenonTime != null)
            phenomenonTimeFilterBuilder = phenomenonTime;

        // chronological order, attached to phenomenonTime filter
        boolean isDescendingOrder = parseDescendingOrderArg(queryParams);
        phenomenonTimeFilterBuilder.descendingOrder(isDescendingOrder);

        if (phenomenonTime != null || isDescendingOrder)
            builder.withPhenomenonTime(phenomenonTimeFilterBuilder.build());

        // resultTime param
        var resultTime = parseTimeStampArg("resultTime", queryParams);
        if (resultTime != null)
            builder.withResultTime(resultTime);
        
        // foi param
        var foiIDs = parseResourceIdsOrUids("foi", queryParams, idEncoders.getFoiIdEncoder());
        if (foiIDs != null && !foiIDs.isEmpty() && !foiParent)
        {
            if (foiIDs.isUids())
                builder.withFois().withUniqueIDs(foiIDs.getUids()).done();
            else
                builder.withFois(foiIDs.getBigIds());
        }

        // the parent datastream and the system, dataStream and observedProperty params
        // all restrict the same datastream filter, so they are combined with AND
        var sysIDs = parseResourceIdsOrUids("system", queryParams, idEncoders.getSystemIdEncoder());
        var dsIDs = parseResourceIdsOrUids("dataStream", queryParams, idEncoders.getDataStreamIdEncoder());
        var obsProps = parseMultiValuesArg("observedProperty", queryParams);
        boolean hasSysIDs = sysIDs != null && !sysIDs.isEmpty();
        boolean hasObsProps = obsProps != null && !obsProps.isEmpty();
        
        // explicit datastream IDs: the parent, the dataStream param, or their intersection
        // (null means no datastream ID was given)
        Set<BigId> selectedDsIDs = null;
        if (parentDsID != null)
            selectedDsIDs = new HashSet<>(Set.of(parentDsID));
        if (dsIDs != null && !dsIDs.isEmpty())
        {
            if (selectedDsIDs == null)
                selectedDsIDs = new HashSet<>(dsIDs.getBigIds());
            else // an empty intersection selects no observations
                selectedDsIDs.retainAll(dsIDs.getBigIds());
        }
        
        // other datastream criteria, by system and observed property
        var dsFilter = new DataStreamFilter.Builder();
        if (hasSysIDs)
        {
            if (sysIDs.isUids())
                dsFilter.withSystems().withUniqueIDs(sysIDs.getUids()).done();
            else
                dsFilter.withSystems(sysIDs.getBigIds());
        }
        if (hasObsProps)
            dsFilter.withObservedProperties(obsProps);
        
        if (selectedDsIDs != null)
        {
            // stores use datastream IDs alone when they are given,
            // so check the other criteria against each selected datastream here
            if (hasSysIDs || hasObsProps)
            {
                var propFilter = dsFilter.build();
                selectedDsIDs.removeIf(id -> {
                    // drop unknown datastreams
                    var dsInfo = db.getDataStreamStore().get(new DataStreamKey(id));
                    if (dsInfo == null)
                        return true;
                    
                    // drop datastreams of other systems (matched by UID or ID)
                    if (hasSysIDs && !(sysIDs.isUids() ?
                        sysIDs.getUids().contains(dsInfo.getSystemID().getUniqueID()) :
                        sysIDs.getBigIds().contains(dsInfo.getSystemID().getInternalID())))
                        return true;
                    
                    // drop datastreams without one of the observed properties
                    return !propFilter.testObservedProperty(dsInfo);
                });
            }
            
            builder.withDataStreams(selectedDsIDs);
        }
        
        // no datastream IDs given, so the store can select datastreams by the other criteria
        else if (hasSysIDs || hasObsProps)
            builder.withDataStreams(dsFilter.build());
        
        // use opensearch bbox param to filter spatially
        var bbox = parseBboxArg("bbox", queryParams);
        if (bbox != null)
        {
            builder.withPhenomenonLocation(new SpatialFilter.Builder()
                .withBbox(bbox)
                .build());
        }
        
        // geom param
        var geom = parseGeomArg("location", queryParams);
        if (geom != null)
        {
            builder.withPhenomenonLocation(new SpatialFilter.Builder()
                .withRoi(geom)
                .build());
        }
        
        // limit
        // need to limit to offset+limit+1 since we rescan from the beginning for now
        if (limit != Long.MAX_VALUE)
            builder.withLimit(offset+limit+1);
        
        return builder.build();
    }


    @Override
    protected BigId addEntry(RequestContext ctx, IObsData res) throws DataStoreException
    {
        try
        {
            var dsHandler = ((ObsHandlerContextData)ctx.getData()).dsHandler;
            return dsHandler.addObs(res);
        }
        catch (IllegalStateException e)
        {
            throw new DataStoreException("Invalid FOI ID");
        }
    }

    @Override
    protected boolean updateEntry(RequestContext ctx, BigId key, IObsData res) throws DataStoreException {
        // Don't allow insertion with PUT
        if(db.getObservationStore().get(key) == null)
            return false;

        try
        {
            db.getObservationStore().put(key, res);
            return true;
        }
        catch (IllegalArgumentException e)
        {
            throw new DataStoreException(e.getMessage());
        }
    }

    @Override
    protected boolean deleteEntry(final RequestContext ctx, final BigId key) throws DataStoreException
    {
        return db.getObservationStore().remove(key) != null;
    }
    
    
    @Override
    protected boolean isValidID(BigId internalID)
    {
        return false;
    }


    @Override
    protected void validate(IObsData resource)
    {
        // TODO Auto-generated method stub
        
    }
    
    
    @Override
    public String[] getNames()
    {
        return NAMES;
    }
}
