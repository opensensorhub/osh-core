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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import org.jglue.fluentjson.JsonBuilderFactory;
import org.junit.Test;
import org.vast.util.TimeExtent;
import com.google.common.collect.Collections2;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;


public class TestSystems extends AbstractTestAllSmlFeatures
{
    public static final String UID_FORMAT = "urn:osh:sys:test%03d";
    
    
    public static class SystemInfo
    {
        public String url;
        public String id;
        public Collection<SystemInfo> subsystems = new ArrayList<>();
        public Collection<String> fois = new ArrayList<>();
        public Collection<String> datastreams = new ArrayList<>();
        public Collection<String> controls = new ArrayList<>();
        
        public SystemInfo(String url)
        {
            this.url = url;
            this.id = getResourceId(url);
        }
    }
    
    
    public TestSystems()
    {
        super(SYSTEM_COLLECTION, UID_FORMAT);
    }
    
    
    @Test
    public void testAddSystemMembersAndGetById() throws Exception
    {
        // add system group
        var groupUrl = addFeature(1);
        
        // add members
        int numMembers = 10;
        var ids = new ArrayList<String>();
        for (int i = 0; i < numMembers; i++)
        {
            var url = addMember(groupUrl, i+2);
            var id = getResourceId(url);
            ids.add(id);
        }
        
        // get list of members
        var jsonResp = sendGetRequestAndParseJson(concat(groupUrl, MEMBER_COLLECTION));
        checkCollectionItemIds(ids, jsonResp);
    }
    
    
    @Test
    public void testAddSystemMembersDepth2AndGet() throws Exception
    {
        var sys1 = addSystemAndSubsystems(1, new int[] {5, 5}, 10);
        var sys2 = addSystemAndSubsystems(2, new int[] {6, 3}, 10);
        
        checkSubSystemIds(sys1);
        checkSubSystemIds(sys2);
        
        // TODO test with searchMembers=true
    }
    
    
    @Test
    public void testAddSystemMembersDepth3AndGet() throws Exception
    {
        var sys1 = addSystemAndSubsystems(1, new int[] {2, 2, 2}, 10);
        var sys2 = addSystemAndSubsystems(3, new int[] {3, 3, 2}, 10);
        var sys3 = addSystemAndSubsystems(5, new int[] {5, 4}, 10);
        
        checkSubSystemIds(sys1);
        checkSubSystemIds(sys2);
        checkSubSystemIds(sys3);
        
        // TODO test with searchMembers=true
    }
    
    
    @Test
    public void testAddSystemMembersAndCheckCannotDeleteParent() throws Exception
    {
        var sys1 = addSystemAndSubsystems(1, new int[] {2, 2, 2}, 10);
        
        sendDeleteRequestAndCheckStatus(sys1.url, 400);
        
        // check system and subsystems have NOT been deleted
        sendGetRequestAndCheckStatus(sys1.url, 200);
        sendGetRequestAndGetItems(SYSTEM_COLLECTION + "?searchMembers=true", 1+2+2*2+2*2*2);
    }
    
    
    @Test
    public void testAddSystemMembersAndDeleteCascade() throws Exception
    {
        var sys1 = addSystemAndSubsystems(1, new int[] {2, 2, 2}, 10);
        
        sendDeleteRequestAndCheckStatus(sys1.url + "?cascade=true", 204);
        
        // check system and subsystems have been deleted
        sendGetRequestAndCheckStatus(sys1.url, 404);
        sendGetRequestAndGetItems(SYSTEM_COLLECTION + "?searchMembers=true", 0);
    }
    
    
    // Non-Test helper methods
    
    @Override
    protected JsonObject createFeatureGeoJson(int procNum, TimeExtent validTime, Map<String, Object> props) throws Exception
    {
        var json = JsonBuilderFactory.buildObject()
            .add("type", "Feature")
            .addObject("properties")
              .add("uid", String.format(UID_FORMAT, procNum))
              .add("name", "Test Sensor #" + procNum);
        
        if (validTime != null)
        {
            json.addArray("validTime")
                .add(validTime.begin().toString())
                .add(validTime.endsNow() ? "now" : validTime.end().toString())
            .end();
        }
        
        // add all other properties
        for (var prop: props.entrySet())
        {
            var val = prop.getValue();
            if (val instanceof String)
                json.add(prop.getKey(), (String)val);
            else if (val instanceof Number)
                json.add(prop.getKey(), (Number)val);
            else
                throw new IllegalArgumentException();
        }
        
        return json.end().getJson();
    }
    
    
    @Override
    protected JsonObject createFeatureSmlJson(int procNum) throws Exception
    {
        var numId = String.format("%03d", procNum);
        var sml = "{\n"
            + "  \"type\": \"PhysicalSystem\",\n"
            + "  \"description\": \"Sensor registered using CONSYS API\",\n"
            + "  \"identifier\": \"urn:osh:sensor:test:" + numId + "\",\n"
            + "  \"names\": [\"Test sensor\"],\n"
            + "  \"identifications\": [\n"
            + "    {\n"
            + "      \"type\": \"IdentifierList\",\n"
            + "      \"identifiers\": [\n"
            + "        {\n"
            + "          \"type\": \"Term\",\n"
            + "          \"definition\": \"urn:ogc:def:identifier:OGC:longname\",\n"
            + "          \"label\": \"Long Name\",\n"
            + "          \"value\": \"Test sensor " + numId + " located in my garden\"\n"
            + "        },\n"
            + "        {\n"
            + "          \"type\": \"Term\",\n"
            + "          \"definition\": \"urn:ogc:def:identifier:OGC:shortname\",\n"
            + "          \"label\": \"Short Name\",\n"
            + "          \"value\": \"Test Sensor #" + numId + "\"\n"
            + "        },\n"
            + "        {\n"
            + "          \"type\": \"Term\",\n"
            + "          \"definition\": \"urn:ogc:def:identifier:OGC:manufacturer\",\n"
            + "          \"label\": \"Manufacturer\",\n"
            + "          \"value\": \"SensorMakers Inc.\"\n"
            + "        },\n"
            + "        {\n"
            + "          \"type\": \"Term\",\n"
            + "          \"definition\": \"http://sensorml.com/ont/swe/property/SerialNumber\",\n"
            + "          \"label\": \"Serial Number\",\n"
            + "          \"value\": \"0123456879\"\n"
            + "        }\n"
            + "      ]\n"
            + "    }\n"
            + "  ],\n"
            + "  \"classifications\": [\n"
            + "    {\n"
            + "      \"type\": \"ClassifierList\",\n"
            + "      \"classifiers\": [\n"
            + "        {\n"
            + "          \"type\": \"Term\",\n"
            + "          \"definition\": \"urn:ogc:def:classifier:OGC:application\",\n"
            + "          \"label\": \"Intended Application\",\n"
            + "          \"value\": \"weather\"\n"
            + "        },\n"
            + "        {\n"
            + "          \"type\": \"Term\",\n"
            + "          \"definition\": \"urn:sensor:classifier:sensorType\",\n"
            + "          \"label\": \"Instrument Type\",\n"
            + "          \"codeSpace\": {\n"
            + "            \"href\": \"http://gcmdservices.gsfc.nasa.gov/static/kms/instruments/instruments.xml\"\n"
            + "          },\n"
            + "          \"value\": \"weather station\"\n"
            + "        }\n"
            + "      ]\n"
            + "    }\n"
            + "  ],\n"
            + "  \"positions\": [\n"
            + "    {\n"
            + "      \"type\": \"Point\",\n"
            + "      \"id\": \"stationLocation\",\n"
            + "      \"srsName\": \"http://www.opengis.net/def/crs/EPSG/0/4979\",\n"
            + "      \"srsDimension\": \"3\",\n"
            + "      \"pos\": \"1.2311 43.5678 0\"\n"
            + "    }\n"
            + "  ]\n"
            + "}";
        
        return (JsonObject)JsonParser.parseString(sml);
    }
    
    
    @Override
    protected void assertFeatureEquals(JsonObject expected, JsonObject actual)
    {
        actual.remove("id");
        actual.remove("links");
        assertEquals(expected, actual);
    }
    
    
    protected SystemInfo addSystemAndSubsystems(int num, int[] levelSizes, int maxLevelSize) throws Exception
    {
        int mul = (int)Math.pow(10.0, Math.ceil(Math.log10(maxLevelSize)));
        
        var url = addFeature(num);
        var sysInfo = new SystemInfo(url);
        addSubsystems(sysInfo, 1, mul, num*mul, levelSizes);
        
        return sysInfo;
    }
    
    
    protected void addSubsystems(SystemInfo parent, int level, int mul, int offset, int[] levelSizes) throws Exception
    {
        for (int i = 0; i < levelSizes[0]; i++)
        {
            var idx = i + offset + 1;
            var url = addMember(parent.url, idx);
            var subsysInfo = new SystemInfo(url);
            System.err.println("Added " + url);
            
            // add nested systems if not lowest level
            if (level < levelSizes.length)
            {
                int nextOffset = idx*mul;
                addSubsystems(subsysInfo, level+1, mul, nextOffset, levelSizes);
            }
            
            parent.subsystems.add(subsysInfo);
        }
    }
    
    
    protected String addMember(String parentUrl, int num) throws Exception
    {
        // add group member
        var json = createFeatureGeoJson(num);
        var httpResp = sendPostRequest(concat(parentUrl, MEMBER_COLLECTION), json);
        var url = getLocation(httpResp);
        
        // get it back by id
        var jsonResp = sendGetRequestAndParseJson(url);
        //System.out.println(gson.toJson(jsonResp));
        checkId(url, jsonResp);
        assertFeatureEquals(json, (JsonObject)jsonResp);
        
        return url;
    }
    
    
    protected void checkSubSystemIds(SystemInfo sys) throws Exception
    {
        var jsonResp = sendGetRequestAndParseJson(concat(sys.url, MEMBER_COLLECTION));
        var expectedIds = Collections2.transform(sys.subsystems, s -> s.id);
        checkCollectionItemIds(expectedIds, jsonResp);
        
        for (var subsys: sys.subsystems)
            checkSubSystemIds(subsys);
    }
    
}