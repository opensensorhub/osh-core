/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2022 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys;

import static org.junit.Assert.assertEquals;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import org.junit.Before;
import org.junit.Test;
import org.sensorhub.api.common.SensorHubException;
import org.vast.swe.SWEHelper;
import org.vast.swe.SWEJsonBindings;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonWriter;
import net.opengis.swe.v20.BinaryEncoding;
import net.opengis.swe.v20.DataComponent;


public class TestControlStreams extends AbstractTestApiBase
{
    TestSystems systemsTest = new TestSystems();
    
    
    @Before
    public void setup() throws IOException, SensorHubException
    {
        super.setup();
        systemsTest.apiRootUrl = apiRootUrl;
    }
    
    
    @Test
    public void testAddSystemAndControls() throws Exception
    {
        // add system
        var sysUrl = systemsTest.addFeature(1);
        
        // add control channels
        int numControls = 10;
        var ids = new ArrayList<String>();
        for (int i = 0; i < numControls; i++)
        {
            var url = addControlJson(sysUrl, i);
            var id = url.substring(url.lastIndexOf('/')+1);
            ids.add(id);
        }
        
        // get list of controls
        var jsonResp = sendGetRequestAndParseJson(concat(sysUrl, CONTROL_COLLECTION));
        System.out.println(gson.toJson(jsonResp));
        checkCollectionItemIds(ids, jsonResp);
    }
    
    
    protected String addControlJson(String procUrl, int num) throws Exception
    {
        var swe = new SWEHelper();
        var rec = swe.createRecord()
            .name(String.format("input%03d", num))
            .addSamplingTimeIsoUTC("time")
            .addField("f1", swe.createQuantity()
                .label("Component 1")
                .uomCode("Cel"))
            .addField("f2", swe.createText()
                .label("Component 2")
                .pattern("[0-9]{5-10}"))
            .build();
        
        // add datastream
        var json = createControlJson(rec);
        var httpResp = sendPostRequest(concat(procUrl, CONTROL_COLLECTION), json);
        var url = getLocation(httpResp);
        
        // get it back by id
        var jsonResp = sendGetRequestAndParseJson(url);
        //System.out.println(gson.toJson(jsonResp));
        checkId(url, jsonResp);
        //assertDatastreamEquals(json, (JsonObject)jsonResp);
        
        return url;
    }
    
    
    protected JsonObject createControlJson(DataComponent paramStruct) throws Exception
    {
        var buffer = new StringWriter();
        var writer = new JsonWriter(buffer);
        
        writer.beginObject();
        writer.name("name").value("Name of control " + paramStruct.getName());
        writer.name("inputName").value(paramStruct.getName());
        
        // result schema & encoding
        try
        {
            var sweBindings = new SWEJsonBindings();
            writer.name("schema").beginObject();
            writer.name("parametersSchema");
            sweBindings.writeDataComponent(writer, paramStruct, false);
            
            writer.endObject();
        }
        catch (Exception e)
        {
            throw new IOException("Error writing SWE Common params structure", e);
        }
        
        writer.endObject();
        
        return (JsonObject)JsonParser.parseString(buffer.getBuffer().toString());
    }
    
    
    protected JsonObject createControlSweBinary(DataComponent resultStruct, BinaryEncoding resultEncoding) throws Exception
    {
        var buffer = new StringWriter();
        var writer = new JsonWriter(buffer);
        
        writer.beginObject();
        writer.name("name").value("Name of control " + resultStruct.getName());
        writer.name("inputName").value(resultStruct.getName());
        
        // result schema & encoding
        try
        {
            var sweBindings = new SWEJsonBindings();
            
            writer.name("schema").beginObject();
            
            writer.name("resultSchema");
            sweBindings.writeDataComponent(writer, resultStruct, false);
            
            writer.name("resultEncoding");
            sweBindings.writeAbstractEncoding(writer, resultEncoding);
            
            writer.endObject();
        }
        catch (Exception e)
        {
            throw new IOException("Error writing SWE Common result structure", e);
        }
        
        writer.endObject();
        
        return (JsonObject)JsonParser.parseString(buffer.getBuffer().toString());
    }
    
    
    protected void assertDatastreamEquals(JsonObject expected, JsonObject actual)
    {
        // remove some fields not present in POST request before comparison
        actual.remove("id");
        actual.remove("system");
        actual.remove("validTime");
        actual.remove("phenomenonTime");
        actual.remove("resultTime");
        actual.remove("links");
        assertEquals(expected, actual);
    }
}
