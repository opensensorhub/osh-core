/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2023 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.data.DataStreamInfo;
import org.sensorhub.api.data.IDataStreamInfo;
import org.sensorhub.api.feature.FeatureId;
import org.sensorhub.api.feature.FeatureLink;
import org.sensorhub.impl.service.consys.resource.ResourceFormat;
import org.vast.data.TextEncodingImpl;
import org.vast.swe.helper.GeoPosHelper;
import com.google.common.base.Strings;
import net.opengis.swe.v20.DataComponent;


public class TestClientDataStreams extends TestClientBase
{
    TestClientSystems systemTests;
    
    
    public TestClientDataStreams() {}
    
    
    TestClientDataStreams(String apiRootUrl)
    {
        this.apiRootUrl = apiRootUrl;
    }
    
    
    @Before
    public void setup() throws IOException, SensorHubException
    {
        super.setup();
        //this.apiRootUrl = "http://localhost:8181/sensorhub/api";
        systemTests = new TestClientSystems(apiRootUrl);
    }
    
    
    @Test
    public void testAddDataStream() throws Exception
    {
        var sysId = systemTests.addSystem(1, true);
        
        var swe = new GeoPosHelper();
        var recordStruct = swe.createLocationVectorLLA()
            .name("pos")
            .build();
        
        addDataStream(sysId, 1, recordStruct, true);
    }
    
    
    @Test
    public void testAddDataStreamBatch() throws Exception
    {
        var sysId = systemTests.addSystem(1, true);
        
        var swe = new GeoPosHelper();
        var recordStruct = swe.createLocationVectorLLA()
            .name("pos")
            .build();
        
        addDataStreamBatch(sysId, 20, recordStruct, true);
    }
    
    
    @Test
    public void testAddDataStreamAndGetById() throws Exception
    {
        var sysId = systemTests.addSystem(1, true);
        
        var swe = new GeoPosHelper();
        var recordStruct = swe.createLocationVectorLLA()
            .name("pos")
            .build();
        
        var dsId = addDataStream(sysId, 1, recordStruct, false);
        
        var client = ConSysApiClient
            .newBuilder(apiRootUrl)
            .build();
        
        var dsInfo = client.getDatastreamById(dsId, ResourceFormat.JSON, false).get();
        
        assertEquals(recordStruct.getName(), dsInfo.getOutputName());
        assertEquals(systemTests.getSystemUid(1), dsInfo.getSystemID().getUniqueID());
        assertTrue(((FeatureLink)dsInfo.getSystemID()).getLink().getHref().contains(sysId));
    }
    
    

    protected String addDataStream(String sysId, int num, DataComponent recordStruct, boolean checkHead) throws Exception
    {
        // insert one datastream
        var dsInfo = new DataStreamInfo.Builder()
            .withSystem(FeatureId.NULL_FEATURE)
            .withName("Test Datastream #" + num)
            .withDescription("Test datastream " + recordStruct.getName())
            .withRecordDescription(recordStruct)
            .withRecordEncoding(new TextEncodingImpl())
            .build();
        
        var f = ConSysApiClient
            .newBuilder(apiRootUrl)
            .build()
            .addDataStream(sysId, dsInfo);
        
        var id = f.get();
        assertFalse(Strings.isNullOrEmpty(id));
        
        if (checkHead)
            checkDatastreamIdExists(id);
        
        return id;
    }
    
    
    protected Set<String> addDataStreamBatch(String sysId, int batchSize, DataComponent recordStruct, boolean checkHead) throws Exception
    {
        // insert multiple systems
        var dsList = new ArrayList<IDataStreamInfo>();
        for (int i = 0; i < batchSize; i++)
        {
            var num = i+1000;
            var struct = recordStruct.copy();
            struct.setName("output" + num);
            
            var dsInfo = new DataStreamInfo.Builder()
                .withSystem(FeatureId.NULL_FEATURE)
                .withName("Test Datastream #" + num)
                .withDescription("Test datastream " + struct.getName())
                .withRecordDescription(struct)
                .withRecordEncoding(new TextEncodingImpl())
                .build();
            dsList.add(dsInfo);
        }
        
        var f = ConSysApiClient
            .newBuilder(apiRootUrl)
            .build()
            .addDataStreams(sysId, dsList);
        
        var idList = f.get();
        System.out.println("Ingested " + batchSize + " datastreams");
        
        if (checkHead)
        {
            for (var id: idList)
                checkDatastreamIdExists(id);
        }
        
        return idList;
    }
    
    
    protected void checkDatastreamIdExists(String id) throws Exception
    {
        checkUriExists(apiRootUrl + "/" + DATASTREAM_COLLECTION + "/" + id);
    }
}
