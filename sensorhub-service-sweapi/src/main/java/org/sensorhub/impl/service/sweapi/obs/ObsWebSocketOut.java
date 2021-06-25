/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2021 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.sweapi.obs;

import java.io.IOException;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.TimeUnit;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.sensorhub.api.data.DataEvent;
import org.sensorhub.api.event.EventUtils;
import org.sensorhub.api.feature.FeatureId;
import org.sensorhub.impl.procedure.DataEventToObsConverter;
import org.sensorhub.impl.service.WebSocketOutputStream;
import org.sensorhub.impl.service.WebSocketUtils;
import org.sensorhub.impl.service.sweapi.InvalidRequestException;
import org.sensorhub.impl.service.sweapi.obs.ObsHandler.ObsHandlerContextData;
import org.sensorhub.impl.service.sweapi.resource.ResourceContext;
import org.sensorhub.utils.CallbackException;
import org.slf4j.Logger;
import org.vast.util.Asserts;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;


/**
 * <p>
 * Output only websocket for sending real-time observations
 * </p>
 *
 * @author Alex Robin
 * @since Feb 5, 2021
 */
public class ObsWebSocketOut implements WebSocketListener
{
    ObsHandler obsHandler;
    ResourceContext ctx;
    Logger log;
    long dsID;
    Session session;
    Subscription subscription;
    
    
    public ObsWebSocketOut(ObsHandler obsHandler, ResourceContext ctx) throws IOException
    {
        this.obsHandler = Asserts.checkNotNull(obsHandler, ObsHandler.class);
        this.ctx = Asserts.checkNotNull(ctx, ResourceContext.class);
        
        this.dsID = ctx.getParentID();
        if (dsID <= 0)
            throw new InvalidRequestException("Websocket streaming is only supported on a specific datastream");
        
        this.log = ctx.getLogger();
    }
    
    
    @Override
    public void onWebSocketConnect(Session session)
    {
        this.session = session;
        WebSocketUtils.logOpen(session, log);
        
        try
        {
            // create websocket output and binding
            var wsOutputStream = new WebSocketOutputStream(session, 1024, false, log);
            ctx.setWebsocketOutputStream(wsOutputStream);
            var binding = obsHandler.getBinding(ctx, false);
            
            // prepare lazy loaded map of FOI UID to full FeatureId
            var foiIdCache = CacheBuilder.newBuilder()
                .maximumSize(100)
                .expireAfterAccess(1, TimeUnit.MINUTES)
                .build(new CacheLoader<String, FeatureId>() {
                    @Override
                    public FeatureId load(String uid) throws Exception
                    {
                        var fk = obsHandler.db.getFoiStore().getCurrentVersionKey(uid);
                        return new FeatureId(fk.getInternalID(), uid);
                    }                    
                });
            
            // get datastream info and init event to obs converter
            var dsInfo = ((ObsHandlerContextData)ctx.getData()).dsInfo;
            var obsConverter = new DataEventToObsConverter(dsID, dsInfo, uid -> foiIdCache.getUnchecked(uid));
                        
            // subscribe to event bus
            var topic = EventUtils.getDataStreamDataTopicID(dsInfo);            
            obsHandler.eventBus.newSubscription(DataEvent.class)
                .withTopicID(topic)
                .withEventType(DataEvent.class)
                .subscribe(new Subscriber<DataEvent>() {

                    @Override
                    public void onSubscribe(Subscription subscription)
                    {
                        ObsWebSocketOut.this.subscription = subscription;
                        subscription.request(Long.MAX_VALUE);
                    }

                    @Override
                    public void onNext(DataEvent item)
                    {
                        obsConverter.toObs(item, obs -> {
                            try
                            {
                                binding.serialize(null, obs, false);
                                wsOutputStream.send();
                            }
                            catch (IOException e)
                            {
                                throw new CallbackException(e);
                            } 
                        });                       
                    }

                    @Override
                    public void onError(Throwable e)
                    {
                        log.error("Error sending websocket data", e);                        
                    }

                    @Override
                    public void onComplete()
                    {
                        if (wsOutputStream != null)
                            wsOutputStream.close();
                    }
                    
                });
        }
        catch (Exception e)
        {
            WebSocketUtils.closeSession(session, StatusCode.SERVER_ERROR, WebSocketUtils.INIT_ERROR, log);
        }
    }


    @Override
    public void onWebSocketClose(int statusCode, String reason)
    {
        WebSocketUtils.logClose(session, statusCode, reason, log);
        
        if (subscription != null)
            subscription.cancel();
        
        session = null;
    }
    
    
    @Override
    public void onWebSocketError(Throwable e)
    {
        log.error(WebSocketUtils.PROTOCOL_ERROR_MSG, e);
    }
    
    
    @Override
    public void onWebSocketBinary(byte[] payload, int offset, int len)
    {
        WebSocketUtils.closeSession(session, StatusCode.BAD_DATA, WebSocketUtils.INPUT_NOT_SUPPORTED, log);
    }


    @Override
    public void onWebSocketText(String msg)
    {
        WebSocketUtils.closeSession(session, StatusCode.BAD_DATA, WebSocketUtils.INPUT_NOT_SUPPORTED, log);
    }
}
