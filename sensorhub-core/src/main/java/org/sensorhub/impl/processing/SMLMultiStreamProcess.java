/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.

Copyright (C) 2020 Sensia Software LLC. All Rights Reserved.

******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.processing;

import java.util.AbstractCollection;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import org.sensorhub.api.common.Event;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.data.DataEvent;
import org.sensorhub.api.data.FoiEvent;
import org.sensorhub.api.data.IDataProducerModule;
import org.sensorhub.api.data.IMultiSourceDataProducer;
import org.sensorhub.api.data.IStreamingDataInterface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.opengis.gml.v32.AbstractFeature;
import net.opengis.sensorml.v20.AbstractProcess;


/**
 * <p>
 * Implementation of process module fully configured using a SensorML process
 * chain description. This process module is intended to be connected to a
 * IMultiSourceDataProducer and a separate instance of the chain (maintaining
 * its own separate state) is created for each data producer (i.e. entity).
 * </p>
 *
 * @author Alex Robin <alex.robin@sensiasoftware.com>
 * @since Aug 26, 2020
 */
public class SMLMultiStreamProcess extends SMLStreamProcess implements IMultiSourceDataProducer
{
    private static final Logger log = LoggerFactory.getLogger(SMLMultiStreamProcess.class);

    Map<String, SMLStreamProcessExt> processMap = new ConcurrentSkipListMap<>();


    class SMLStreamProcessExt extends SMLStreamProcess
    {
        String entityID;
        AbstractFeature foi;

        public String getEntityID()
        {
            return entityID;
        }
    }


    @Override
    public void init(SMLStreamProcessConfig config) throws SensorHubException
    {
        connectToEventBus = false;
        super.init(config);
        connectToEventBus = true;
        
        // replace output interfaces with multisource outputs
        for (IStreamingDataInterface output: outputInterfaces.values())
            outputInterfaces.put(output.getName(), new SMLMultiSourceOutput(this, output.getRecordDescription()));
    }


    @Override
    public void start() throws SensorHubException
    {
        if (smlProcess == null)
            throw new SensorHubException("No valid SensorML processing chain provided");

        connectToDataSources();
    }


    @Override
    public void stop()
    {
        for (SMLStreamProcessExt subProcess: processMap.values())
            subProcess.stop();
        super.stop();
    }


    protected SMLStreamProcessExt createSubProcess(String entityID) throws SensorHubException
    {
        SMLStreamProcessExt subProcess = new SMLStreamProcessExt();
        subProcess.entityID = entityID;
        subProcess.connectToEventBus = false;
        subProcess.init(this.config);

        // register for data events and forward to corresponding multi source output
        for (final IStreamingDataInterface output: subProcess.getAllOutputs().values())
        {
            SMLMultiSourceOutput muxOutput = (SMLMultiSourceOutput)outputInterfaces.get(output.getName());
            output.registerListener(muxOutput);
        }

        subProcess.smlProcess.setName(subProcess.smlProcess.getName() + " - " + entityID);
        subProcess.start();
        return subProcess;
    }


    @Override
    public void handleEvent(Event<?> e)
    {
        if (paused)
            return;

        if (e instanceof DataEvent)
        {
            IDataProducerModule<?> dataProducer = ((IStreamingDataInterface)e.getSource()).getParentModule();
            String entityID = ((DataEvent) e).getRelatedEntityID();
            SMLStreamProcessExt smlProcess = processMap.get(entityID);

            // create subprocess if no data for this entity has been received yet
            if (smlProcess == null)
            {
                try
                {
                    log.debug("Creating sub process for producer {}", entityID);
                    smlProcess = createSubProcess(entityID);
                    if (dataProducer instanceof IMultiSourceDataProducer)
                        smlProcess.foi = ((IMultiSourceDataProducer)dataProducer).getCurrentFeatureOfInterest(entityID);
                    processMap.put(entityID, smlProcess);
                }
                catch (SensorHubException e1)
                {
                    throw new IllegalStateException("Cannot create subprocess for producer " + entityID, e1);
                }
            }

            smlProcess.handleEvent(e);
        }
        else if (e instanceof FoiEvent)
        {
            // TODO register Fois
        }
    }


    protected SMLStreamProcessExt getSubProcess(String entityID)
    {
        SMLStreamProcessExt subProcess = processMap.get(entityID);
        if (subProcess == null)
            throw new IllegalStateException("Invalid entity ID: " + entityID);
        return subProcess;
    }


    @Override
    public Collection<String> getEntityIDs()
    {
        return Collections.unmodifiableCollection(processMap.keySet());
    }


    @Override
    public AbstractProcess getCurrentDescription(String entityID)
    {
        return getCurrentDescription();
    }


    @Override
    public double getLastDescriptionUpdate(String entityID)
    {
        return getLastDescriptionUpdate();
    }


    @Override
    public AbstractFeature getCurrentFeatureOfInterest(String entityID)
    {
        if (entityID.equals(this.getUniqueIdentifier()))
            return null;
        return getSubProcess(entityID).foi;
    }


    @Override
    public Collection<? extends AbstractFeature> getFeaturesOfInterest()
    {
        return Collections.emptyList();
    }


    @Override
    public Collection<String> getFeaturesOfInterestIDs()
    {
        Iterator<SMLStreamProcessExt> it = processMap.values().iterator();

        return new AbstractCollection<String>() {

            @Override
            public Iterator<String> iterator()
            {
                return new Iterator<String>() {
                    @Override
                    public boolean hasNext()
                    {
                        return it.hasNext();
                    }

                    @Override
                    public String next()
                    {
                        return it.next().foi.getUniqueIdentifier();
                    }
                };
            }

            @Override
            public int size()
            {
                return processMap.size();
            }
        };
    }


    @Override
    public Collection<String> getEntitiesWithFoi(String foiID)
    {
        String entityID = foiID.substring(foiID.lastIndexOf(':')+1);
        return Arrays.asList(entityID);
    }
}
