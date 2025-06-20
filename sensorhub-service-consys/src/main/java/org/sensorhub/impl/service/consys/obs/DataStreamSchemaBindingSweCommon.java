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
import java.util.Collection;
import org.sensorhub.api.common.IdEncoders;
import org.sensorhub.api.data.DataStreamInfo;
import org.sensorhub.api.data.IDataStreamInfo;
import org.sensorhub.api.datastore.obs.DataStreamKey;
import org.sensorhub.api.feature.FeatureId;
import org.sensorhub.impl.service.consys.ResourceParseException;
import org.sensorhub.impl.service.consys.SWECommonUtils;
import org.sensorhub.impl.service.consys.resource.RequestContext;
import org.sensorhub.impl.service.consys.resource.ResourceBindingJson;
import org.sensorhub.impl.service.consys.resource.ResourceFormat;
import org.sensorhub.impl.service.consys.resource.ResourceLink;
import org.vast.data.TextEncodingImpl;
import org.vast.swe.SWEJsonBindings;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataEncoding;
import net.opengis.swe.v20.JSONEncoding;


public class DataStreamSchemaBindingSweCommon extends ResourceBindingJson<DataStreamKey, IDataStreamInfo>
{
    String rootURL;
    ResourceFormat obsFormat;
    SWEJsonBindings sweBindings;
    
    
    public DataStreamSchemaBindingSweCommon(ResourceFormat obsFormat, RequestContext ctx, IdEncoders idEncoders, boolean forReading) throws IOException
    {
        super(ctx, idEncoders, forReading);
        init(obsFormat, ctx, forReading);
    }
    
    
    public DataStreamSchemaBindingSweCommon(ResourceFormat obsFormat, RequestContext ctx, IdEncoders idEncoders, JsonReader reader) throws IOException
    {
        super(ctx, idEncoders, reader);
        init(obsFormat, ctx, true);
    }
    
    
    public DataStreamSchemaBindingSweCommon(ResourceFormat obsFormat, RequestContext ctx, IdEncoders idEncoders, JsonWriter writer) throws IOException
    {
        super(ctx, idEncoders, writer);
        init(obsFormat, ctx, false);
    }
    
    
    void init(ResourceFormat obsFormat, RequestContext ctx, boolean forReading)
    {
        this.rootURL = ctx.getApiRootURL();
        this.obsFormat = obsFormat;
        this.sweBindings = new SWEJsonBindings();
    }
    
    
    @Override
    public IDataStreamInfo deserialize(JsonReader reader) throws IOException
    {
        DataComponent resultStruct = null;
        DataEncoding resultEncoding = new TextEncodingImpl();
        
        try
        {
            // read BEGIN_OBJECT only if not already read by caller
            // this happens when reading embedded schema and auto-detecting obs format
            if (reader.peek() == JsonToken.BEGIN_OBJECT)
                reader.beginObject();
            
            while (reader.hasNext())
            {
                var prop = reader.nextName();
                
                if ("recordSchema".equals(prop))
                {
                    resultStruct = sweBindings.readDataComponent(reader);
                    resultStruct.setName(SWECommonUtils.NO_NAME);
                }
                else if ("recordEncoding".equals(prop))
                {
                    resultEncoding = sweBindings.readEncoding(reader);
                }
                else
                    reader.skipValue();
            }
            reader.endObject();
        }
        catch (IOException e)
        {
            throw new ResourceParseException(INVALID_JSON_ERROR_MSG + e.getMessage());
        }
        catch (IllegalStateException e)
        {
            throw new ResourceParseException(INVALID_JSON_ERROR_MSG + e.getMessage());
        }
        
        // check timestamp is provided as first field
        // I don't think we need to enforce this here since we properly index the phenomenon time field
        //if (!(resultStruct.getComponent(0) instanceof Time))
        //    throw new ResourceParseException(INVALID_JSON_ERROR_MSG + "First record component must be a timestamp");
        
        var dsInfo = new DataStreamInfo.Builder()
            .withName(SWECommonUtils.NO_NAME) // name will be set later
            .withSystem(FeatureId.NULL_FEATURE) // System ID will be set later
            .withRecordDescription(resultStruct)
            .withRecordEncoding(resultEncoding)
            .build();
        
        return dsInfo;
    }


    @Override
    public void serialize(DataStreamKey key, IDataStreamInfo dsInfo, boolean showLinks, JsonWriter writer) throws IOException
    {
        var sweEncoding = SWECommonUtils.getEncoding(dsInfo.getRecordStructure(), dsInfo.getRecordEncoding(), obsFormat);
        
        writer.beginObject();
        writer.name("obsFormat").value(obsFormat.toString());
        
        // result structure & encoding
        try
        {
            writer.name("recordSchema");
            sweBindings.writeDataComponent(writer, dsInfo.getRecordStructure(), false);
        }
        catch (Exception e)
        {
            throw new IOException("Error writing SWE Common record structure", e);
        }
        
        try
        {
            if (!(sweEncoding instanceof JSONEncoding))
            {
                writer.name("recordEncoding");
                sweBindings.writeAbstractEncoding(writer, sweEncoding);
            }
        }
        catch (Exception e)
        {
            throw new IOException("Error writing SWE Common record encoding", e);
        }
        
        writer.endObject();
        writer.flush();
    }


    @Override
    public void startCollection() throws IOException
    {
        startJsonCollection(writer);
    }


    @Override
    public void endCollection(Collection<ResourceLink> links) throws IOException
    {
        endJsonCollection(writer, links);
    }
}
