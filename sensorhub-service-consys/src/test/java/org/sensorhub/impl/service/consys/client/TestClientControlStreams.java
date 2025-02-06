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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import org.sensorhub.api.command.CommandStreamInfo;
import org.sensorhub.api.command.ICommandStreamInfo;
import org.sensorhub.api.common.SensorHubException;
import org.sensorhub.api.feature.FeatureId;
import org.sensorhub.api.feature.FeatureLink;
import org.sensorhub.impl.service.consys.resource.ResourceFormat;
import org.vast.data.TextEncodingImpl;
import org.vast.swe.helper.GeoPosHelper;
import com.google.common.base.Strings;
import net.opengis.swe.v20.DataComponent;

import static org.junit.Assert.*;


public class TestClientControlStreams extends TestClientBase
{
    TestClientSystems systemTests;
    
    
    @Before
    public void setup() throws IOException, SensorHubException
    {
        super.setup();
        //this.apiRootUrl = "http://localhost:8181/sensorhub/api";
        systemTests = new TestClientSystems(apiRootUrl);
    }
    
    
    @Test
    public void testAddControlStream() throws Exception
    {
        var sysId = systemTests.addSystem(1, true);
        
        var swe = new GeoPosHelper();
        var recordStruct = swe.createRecord()
            .name("ptz_control")
            .addField("pan", swe.createQuantity())
            .addField("tilt", swe.createQuantity())
            .addField("zoom", swe.createQuantity())
            .build();
        
        addControlStream(sysId, 1, recordStruct, true);
    }
    
    
    @Test
    public void testAddControlStreamBatch() throws Exception
    {
        var sysId = systemTests.addSystem(1, true);
        
        var swe = new GeoPosHelper();
        var recordStruct = swe.createQuantity()
            .name("sampling_rate")
            .build();
        
        addControlStreamBatch(sysId, 10, recordStruct, true);
    }


    @Test
    public void testAddControlStreamAndGetById() throws Exception
    {
        var sysId = systemTests.addSystem(1, true);

        var swe = new GeoPosHelper();
        var recordStruct = swe.createRecord()
                .name("ptz_control")
                .addField("pan", swe.createQuantity())
                .addField("tilt", swe.createQuantity())
                .addField("zoom", swe.createQuantity())
                .build();

        var csId = addControlStream(sysId, 1, recordStruct, false);

        var client = ConSysApiClient
                .newBuilder(apiRootUrl)
                .build();

        var csInfo = client.getControlStreamById(csId, ResourceFormat.JSON, false).get();

        assertEquals(recordStruct.getName(), csInfo.getControlInputName());
        assertEquals(systemTests.getSystemUid(1), csInfo.getSystemID().getUniqueID());
        assertTrue(((FeatureLink)csInfo.getSystemID()).getLink().getHref().contains(sysId));
    }
    

    protected String addControlStream(String sysId, int num, DataComponent recordStruct, boolean checkHead) throws Exception
    {
        // insert one datastream
        var dsInfo = new CommandStreamInfo.Builder()
            .withSystem(FeatureId.NULL_FEATURE)
            .withName("Test Control #" + num)
            .withDescription("Test controlstream " + recordStruct.getName())
            .withRecordDescription(recordStruct)
            .withRecordEncoding(new TextEncodingImpl())
            .build();
        
        var f = ConSysApiClient
            .newBuilder(apiRootUrl)
            .build()
            .addControlStream(sysId, dsInfo);
        
        var id = f.get();
        assertFalse(Strings.isNullOrEmpty(id));
        
        if (checkHead)
            checkControlStreamIdExists(id);
        
        return id;
    }
    
    
    protected Set<String> addControlStreamBatch(String sysId, int batchSize, DataComponent recordStruct, boolean checkHead) throws Exception
    {
        // insert multiple systems
        var dsList = new ArrayList<ICommandStreamInfo>();
        for (int i = 0; i < batchSize; i++)
        {
            var num = i+1000;
            var struct = recordStruct.copy();
            struct.setName("control" + num);
            
            var dsInfo = new CommandStreamInfo.Builder()
                .withSystem(FeatureId.NULL_FEATURE)
                .withName("Test Control #" + num)
                .withDescription("Test controlstream " + struct.getName())
                .withRecordDescription(struct)
                .withRecordEncoding(new TextEncodingImpl())
                .build();
            dsList.add(dsInfo);
        }
        
        var f = ConSysApiClient
            .newBuilder(apiRootUrl)
            .build()
            .addControlStreams(sysId, dsList);
        
        var idList = f.get();
        System.out.println("Ingested " + batchSize + " control streams");
        
        if (checkHead)
        {
            for (var id: idList)
                checkControlStreamIdExists(id);
        }
        
        return idList;
    }
    
    
    protected void checkControlStreamIdExists(String id) throws Exception
    {
        checkUriExists(apiRootUrl + "/" + CONTROL_COLLECTION + "/" + id);
    }
}
