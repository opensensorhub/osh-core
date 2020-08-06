/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.test.service.sos;

import static org.junit.Assert.*;
import java.net.URL;
import java.util.concurrent.Future;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sensorhub.api.client.ClientException;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.module.ModuleEvent.ModuleState;
import org.sensorhub.api.sensor.ISensorModule;
import org.sensorhub.api.sensor.SensorConfig;
import org.sensorhub.impl.client.sost.SOSTClient;
import org.sensorhub.impl.client.sost.SOSTClientConfig;
import org.sensorhub.impl.security.ClientAuth;
import org.sensorhub.impl.service.HttpServer;
import org.sensorhub.impl.service.sos.SOSProviderConfig;
import org.sensorhub.impl.service.sos.SOSService;
import org.sensorhub.impl.service.sos.SensorDataProviderConfig;
import org.sensorhub.test.sensor.FakeSensor;
import org.sensorhub.test.sensor.FakeSensorData;
import org.vast.ows.GetCapabilitiesRequest;
import org.vast.ows.OWSUtils;
import org.vast.ows.sos.SOSOfferingCapabilities;
import org.vast.ows.sos.SOSServiceCapabilities;
import org.vast.ows.sos.SOSUtils;


public class TestSOSTClient
{
    static final int TIMEOUT = 10000;
    static final String SENSOR_UID = "urn:test:newsensor:0002";
    static final double SAMPLING_PERIOD = 0.2;
    static final int NUM_GEN_SAMPLES = 4;
    TestSOSService sosTest;
    Exception asyncError;
    int recordCounter = 0;
    
    
    @Before
    public void setup() throws Exception
    {
        sosTest = new TestSOSService();
        sosTest.setup();
        ClientAuth.createInstance(null);
    }
    
    
    protected ISensorModule<?> buildSensor1(int numSamples) throws Exception
    {
        // create test sensor
        SensorConfig sensorCfg = new SensorConfig();
        sensorCfg.autoStart = false;
        sensorCfg.moduleClass = FakeSensorNetWithFoi.class.getCanonicalName();
        sensorCfg.name = "Sensor1";
        FakeSensor sensor = (FakeSensor)sosTest.registry.loadModule(sensorCfg);
        sensor.setSensorUID(SENSOR_UID);
        sensor.setDataInterfaces(new FakeSensorData(sensor, TestSOSService.NAME_OUTPUT1, 10, SAMPLING_PERIOD, numSamples));
        sensor.requestInit(false);
        sensor.setStartedState(); // fake started state but don't send data yet
        return sensor;
    }
    
    
    protected void startClient(String sensorID, boolean async) throws Exception
    {
        startClient(sensorID, async, false, 4);
    }
    
    
    protected SOSTClient startClient(String sensorID, boolean async, boolean persistent, int maxAttempts) throws Exception
    {
        URL sosUrl = new URL(TestSOSService.HTTP_ENDPOINT);
        
        SOSTClientConfig config = new SOSTClientConfig();
        config.id = "SOST";
        config.name = "SOS-T Client";
        config.dataSourceID = sensorID;
        config.sos.remoteHost = sosUrl.getHost();
        config.sos.remotePort = (sosUrl.getPort() > 0) ? sosUrl.getPort() : 80;
        config.sos.resourcePath = sosUrl.getPath();
        config.connection.connectTimeout = 1000;
        config.connection.reconnectPeriod = 500;
        config.connection.reconnectAttempts = maxAttempts;
        config.connection.checkReachability = false;
        config.connection.usePersistentConnection = persistent;
        config.connection.maxConnectErrors = 2;
        
        final SOSTClient client = new SOSTClient();
        client.setConfiguration(config);
        client.requestInit(false);
        
        if (async)
        {
            new Thread() {
                public void run()
                {
                    try
                    {
                        client.requestStart();
                    }
                    catch (SensorHubException e)
                    {
                        asyncError = e;
                    }
                }
            }.start();
        }
        else
            client.start();
        
        return client;
    }
    
    
    protected SOSOfferingCapabilities getCapabilities(int offeringIndex, int waitTimeOut) throws Exception
    {
        OWSUtils utils = new OWSUtils();
        
        // check capabilities has one more offering
        GetCapabilitiesRequest getCap = new GetCapabilitiesRequest();
        getCap.setService(SOSUtils.SOS);
        getCap.setVersion("2.0");
        getCap.setGetServer(TestSOSService.HTTP_ENDPOINT);
        
        long maxWait = System.currentTimeMillis() + waitTimeOut;
        SOSServiceCapabilities caps = null;
        int numOffering = 0;
        do
        {
            caps = (SOSServiceCapabilities)utils.sendRequest(getCap, false);
            numOffering = caps.getLayers().size();
            if (numOffering >= offeringIndex+1)
                break;
            if (waitTimeOut > 0)
                Thread.sleep(1000);
        }
        while (System.currentTimeMillis() < maxWait);
        
        //utils.writeXMLResponse(System.out, caps);
        assertTrue("No offering added", numOffering >= offeringIndex+1);
        return (SOSOfferingCapabilities)caps.getLayers().get(offeringIndex);
    }
    
    
    @Test
    public void testRegisterSync() throws Exception
    {
        // start service with SOS-T support
        sosTest.deployService(true, new SOSProviderConfig[0]);
     
        // start client
        ISensorModule<?> sensor = buildSensor1(NUM_GEN_SAMPLES);
        startClient(sensor.getLocalID(), false);
        
        // check capabilities content
        SOSOfferingCapabilities newOffering = getCapabilities(0, 0);
        assertEquals(SENSOR_UID, newOffering.getMainProcedure());
    }
    
    
    @Test(expected = ClientException.class)
    public void testRegisterErrorNoTransactionalServer() throws Exception
    {
        // start service w/o SOS-T support
        sosTest.deployService(false, new SOSProviderConfig[0]);
        
        // start client
        ISensorModule<?> sensor = buildSensor1(NUM_GEN_SAMPLES);
        startClient(sensor.getLocalID(), false);
    }
    
    
    @Test
    public void testRegisterAsyncReconnect() throws Exception
    {
        // start client
        ISensorModule<?> sensor = buildSensor1(NUM_GEN_SAMPLES);
        startClient(sensor.getLocalID(), true);
        Thread.sleep(100);
        
        // start service
        sosTest.deployService(true, new SOSProviderConfig[0]);
        
        // check capabilities content
        SOSOfferingCapabilities newOffering = getCapabilities(0, TIMEOUT);
        assertEquals(SENSOR_UID, newOffering.getMainProcedure());
    }
    
    
    @Test
    public void testRegisterAsyncReconnectNoServer() throws Exception
    {
        // start client
        ISensorModule<?> sensor = buildSensor1(NUM_GEN_SAMPLES);
        SOSTClient client = startClient(sensor.getLocalID(), true, false, 3);
        
        // wait for exception
        long maxWait = System.currentTimeMillis() + TIMEOUT;
        while (client.getCurrentError() == null)
        {
            Thread.sleep(500);
            if (System.currentTimeMillis() > maxWait)
                fail("No connection error reported");
        }
    }
    
    
    @Test
    public void testInsertResultPost() throws Exception
    {
        // start service with SOS-T support
        SOSService sos = sosTest.deployService(true, new SOSProviderConfig[0]);
        
        // start client
        ISensorModule<?> sensor = buildSensor1(NUM_GEN_SAMPLES);
        startClient(sensor.getLocalID(), false, false, 1);
        
        // reduce liveDataTimeout of new provider
        SensorDataProviderConfig provider = (SensorDataProviderConfig)sos.getConfiguration().dataProviders.get(0);
        provider.liveDataTimeout = 1.0;
        
        // send getResult request
        Future<String[]> f = sosTest.sendGetResultAsync(SENSOR_UID + "-sos", 
                TestSOSService.URI_PROP1, TestSOSService.TIMERANGE_FUTURE, false);
        
        // start sensor
        sensor.start();
        
        sosTest.checkGetResultResponse(f.get(), NUM_GEN_SAMPLES, 4);
    }
    
    
    @Test
    public void testInsertResultPersistent() throws Exception
    {
        // start service with SOS-T support
        SOSService sos = sosTest.deployService(true, new SOSProviderConfig[0]);
        
        // start client
        ISensorModule<?> sensor = buildSensor1(NUM_GEN_SAMPLES);
        startClient(sensor.getLocalID(), false, true, 1);
        
        // reduce liveDataTimeout of new provider
        SensorDataProviderConfig provider = (SensorDataProviderConfig)sos.getConfiguration().dataProviders.get(0);
        provider.liveDataTimeout = 1.0;
        
        // send getResult request
        Future<String[]> f = sosTest.sendGetResultAsync(SENSOR_UID + "-sos", 
                TestSOSService.URI_PROP1, TestSOSService.TIMERANGE_FUTURE, false);
        
        // start sensor
        sensor.start();
        
        sosTest.checkGetResultResponse(f.get(), NUM_GEN_SAMPLES, 4);
        
        //client.stop();
    }
    
    
    @Test
    public void testInsertResultReconnect() throws Exception
    {
        // start service with SOS-T support
        sosTest.deployService(true, new SOSProviderConfig[0]);
        
        // start client
        ISensorModule<?> sensor = buildSensor1(25);
        SOSTClient client = startClient(sensor.getLocalID(), false, true, 2);
        
        // start sensor
        sensor.start();
        
        // stop server
        HttpServer.getInstance().stop();
        
        if (!client.waitForState(ModuleState.STOPPING, TIMEOUT))
            fail("SOS-T client was not stopped");
        
        if (!client.waitForState(ModuleState.STARTING, TIMEOUT))
            fail("SOS-T client was not restarted");
        
        if (!client.waitForState(ModuleState.STOPPED, TIMEOUT))
            fail("SOS-T client was not stopped after 3 tries");
        
        assertTrue("Client should have an error", client.getCurrentError() != null);
    }
    
   
    @After
    public void cleanup()
    {
        sosTest.cleanup();
    }
}
