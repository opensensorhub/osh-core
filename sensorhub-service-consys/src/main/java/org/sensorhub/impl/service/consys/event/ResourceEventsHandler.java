/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2022 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys.event;

import java.io.IOException;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import org.sensorhub.api.event.Event;
import org.sensorhub.api.event.IEventBus;
import org.sensorhub.api.event.ISubscriptionBuilder;
import org.sensorhub.impl.service.consys.BaseHandler;
import org.sensorhub.impl.service.consys.HandlerContext;
import org.sensorhub.impl.service.consys.InvalidRequestException;
import org.sensorhub.impl.service.consys.ServiceErrors;
import org.sensorhub.impl.service.consys.InvalidRequestException.ErrorCode;
import org.sensorhub.impl.service.consys.RestApiServlet.ResourcePermissions;
import org.sensorhub.impl.service.consys.resource.RequestContext;
import org.sensorhub.impl.service.consys.resource.ResourceBindingJson;
import org.sensorhub.utils.CallbackException;


public abstract class ResourceEventsHandler<T extends Event> extends BaseHandler
{
    public static final String[] NAMES = { "events" };
    
    public static final String EVENTS_STREAM_ONLY_ERROR_MSG = "Only streaming requests supported on this resource";
    public static final String RESOURCE_ADDED_EVENT_TYPE = "ADDED";
    public static final String RESOURCE_DELETED_EVENT_TYPE = "REMOVED";
    public static final String RESOURCE_UPDATED_EVENT_TYPE = "UPDATED";
    public static final String RESOURCE_ENABLED_EVENT_TYPE = "ENABLED";
    public static final String RESOURCE_DISABLED_EVENT_TYPE = "DISABLED";
    
    protected final String resourceType;
    protected final IEventBus eventBus;
    protected final ResourcePermissions permissions;
    
    
    protected ResourceEventsHandler(String resourceType, HandlerContext ctx, ResourcePermissions permissions)
    {
        super(ctx);
        this.resourceType = resourceType;
        this.eventBus = ctx.getEventBus();
        this.permissions = permissions;
    }
    
    
    public abstract void subscribe(final RequestContext ctx) throws InvalidRequestException, IOException;
    
    
    protected void subscribe(final RequestContext ctx, final ISubscriptionBuilder<T> sub, final ResourceBindingJson<Long, T> serializer) throws InvalidRequestException, IOException
    {
        // continue when stream start is requested
        var streamHandler = ctx.getStreamHandler();
        streamHandler.setStartCallback(() -> {
            // create subscriber
            var subscriber = new Subscriber<T>() {
                Subscription subscription;
                
                @Override
                public void onSubscribe(Subscription subscription)
                {
                    this.subscription = subscription;
                    subscription.request(Long.MAX_VALUE);
                    ctx.getLogger().debug("Starting " + resourceType + " events subscription #{}", System.identityHashCode(subscription));
                    
                    // cancel subscription if streaming is stopped by client
                    ctx.getStreamHandler().setCloseCallback(() -> {
                        subscription.cancel();
                        ctx.getLogger().debug("Cancelling " + resourceType + " events subscription #{}", System.identityHashCode(subscription));
                    });
                }

                @Override
                public void onNext(T item)
                {
                    try
                    {
                        serializer.serialize(0L, item, false);
                        streamHandler.sendPacket();
                    }
                    catch (IOException e)
                    {
                        throw new CallbackException(e);
                    }
                }

                @Override
                public void onError(Throwable e)
                {
                    ctx.getLogger().error("Error while publishing " + resourceType + " event", e);
                }

                @Override
                public void onComplete()
                {
                    ctx.getLogger().debug("Ending " + resourceType + " events subscription #{}", System.identityHashCode(subscription));
                    streamHandler.close();
                }
            };
            
            sub.subscribe(subscriber);
        });
    }


    @Override
    public String[] getNames()
    {
        return NAMES;
    }


    @Override
    public void doGet(RequestContext ctx) throws InvalidRequestException, IOException, SecurityException
    {
        // if requesting from this resource collection
        if (ctx.isEndOfPath())
        {
            ctx.getSecurityHandler().checkPermission(permissions.stream);
            if (!ctx.isStreamRequest())
                throw new InvalidRequestException(ErrorCode.BAD_REQUEST, EVENTS_STREAM_ONLY_ERROR_MSG);
            
            subscribe(ctx);
            return;
        }
        
        throw ServiceErrors.badRequest(INVALID_URI_ERROR_MSG);
    }


    @Override
    public void doPost(RequestContext ctx) throws InvalidRequestException, IOException, SecurityException
    {
        ServiceErrors.unsupportedOperation("");
    }


    @Override
    public void doPut(RequestContext ctx) throws InvalidRequestException, IOException, SecurityException
    {
        ServiceErrors.unsupportedOperation("");
    }


    @Override
    public void doDelete(RequestContext ctx) throws InvalidRequestException, IOException, SecurityException
    {
        ServiceErrors.unsupportedOperation("");
    }
}
