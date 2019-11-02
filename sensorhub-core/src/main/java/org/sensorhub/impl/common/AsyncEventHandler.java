/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.common;

import java.util.Map;
import java.util.Queue;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import org.sensorhub.api.common.Event;
import org.sensorhub.api.common.IEventHandler;
import org.sensorhub.api.common.IEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * <p>
 * Asynchronous event handler implementation.<br/>
 * This implementation keeps one queue per listener to avoid slowing down dispatching
 * events to other listeners in case one listener has a higher processing time.
 * It also ensures that events are delivered to each listener in the order they
 * were received.
 * </p>
 *
 * @author Alex Robin <alex.robin@sensiasoftware.com>
 * @since Aug 30, 2013
 */
public class AsyncEventHandler implements IEventHandler
{
    private static final Logger log = LoggerFactory.getLogger(AsyncEventHandler.class);
    private Map<IEventListener, ListenerQueue> listeners;
    private ExecutorService threadPool;
    
    
    // helper class to use one queue per listener so they don't slow down each other
    class ListenerQueue
    {
        IEventListener listener;
        Queue<Event<?>> eventQueue = new ConcurrentLinkedQueue<>();
        volatile boolean dispatching = false;
        volatile Thread dispatchThread;
        
        ListenerQueue(IEventListener listener)
        {
            this.listener = listener;
        }
        
        synchronized void dispatchNextEvent()
        {
            // dispatch next event from queue
            final Event<?> e = eventQueue.poll();
            if (e != null)
            {
                dispatching = true;
                
                try
                {
                    threadPool.execute(new Runnable() {
                        @Override
                        public void run()
                        {
                            try
                            {
                                dispatchThread = Thread.currentThread();
                                
                                long dispatchDelay = System.currentTimeMillis() - e.getTimeStamp();
                                if (dispatchDelay > 100)
                                {
                                    String srcName = e.getSource().getClass().getSimpleName();
                                    String destName = listener.getClass().getSimpleName();
                                    log.warn("{} Event from {} to {} @ {}, dispatch delay={}, queue size={}", e.getType(), srcName, destName, e.getTimeStamp(), dispatchDelay, eventQueue.size());
                                }                            
                                
                                //String srcName = e.getSource().getClass().getSimpleName();
                                //String destName = listener.getClass().getSimpleName();
                                //log.debug("Thread {}: Dispatching {} event from {} to {} @ {}, dispatch delay={}, queue size={}", Thread.currentThread().getId(), e.getType(), srcName, destName, e.getTimeStamp(), dispatchDelay, eventQueue.size());
                                
                                listener.handleEvent(e);
                            }
                            catch (Throwable ex)
                            {
                                String srcName = e.getSource().getClass().getSimpleName();
                                String destName = listener.getClass().getSimpleName();
                                log.error("Uncaught exception while dispatching event from {} to {}", srcName, destName, ex);
                            }   
                            finally
                            {
                                dispatchNextEvent();                                
                            }
                        }                        
                    });
                }
                catch (Exception ex)
                {
                    log.error("Cannot use event dispatch thread pool", ex);
                    dispatching = false;
                }
            }
            else
            {
                dispatching = false;
            }
            
            notifyAll();
        }
        
        synchronized void pushEvent(final Event<?> e)
        {
            if (eventQueue.offer(e))
            {            
                // start dispatching if needed
                if (!dispatching)
                    dispatchNextEvent();
            }
            else
            {
                String srcName = e.getSource().getClass().getSimpleName();
                String destName = listener.getClass().getSimpleName();
                log.error("Max queue size reached when dispatching event from {} to {}. Clearing queue", srcName, destName);
                eventQueue.clear();
            }
        }
        
        synchronized void cancel()
        {
            eventQueue.clear();
            
            // don't wait if this is called from the listener itself
            if (Thread.currentThread() == dispatchThread)
                return;
                
            // otherwise wait until current event is processed
            // this insures that no more event will be dispatched after this call
            long t0 = System.currentTimeMillis();
            while (dispatching)
            {
                try
                {
                    wait(1000L);
                    if (System.currentTimeMillis() - t0 >= 1000L)
                        return;
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
    
    
    public AsyncEventHandler(ExecutorService threadPool)
    {
        this.threadPool = threadPool;
        this.listeners = new WeakHashMap<>();
    }
    
    
    @Override
    public void publishEvent(final Event<?> e)
    {
        synchronized (listeners)
        {
            // add event to all registered listener queues
            for (ListenerQueue queue: listeners.values())
                queue.pushEvent(e);
        }
    }
   

    @Override
    public void registerListener(IEventListener listener)
    {
        synchronized (listeners)
        {
            if (!listeners.containsKey(listener))
                listeners.put(listener, new ListenerQueue(listener));
        }
    }


    @Override
    public void unregisterListener(IEventListener listener)
    {
        synchronized (listeners)
        {
            ListenerQueue queue = listeners.remove(listener);
            if (queue != null)
                queue.cancel();
        }
    }
    
    
    @Override
    public int getNumListeners()
    {
        synchronized (listeners)
        {
            return listeners.size();
        }
    }
    
    
    @Override
    public void clearAllListeners()
    {
        synchronized (listeners)
        {
            for (ListenerQueue queue: listeners.values())
                queue.cancel();
            listeners.clear();
        }
    }
}
