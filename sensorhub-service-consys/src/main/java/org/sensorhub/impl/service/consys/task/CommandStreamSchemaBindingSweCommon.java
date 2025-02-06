/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2020 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys.task;

import java.io.IOException;
import java.util.Collection;
import org.sensorhub.api.command.CommandStreamInfo;
import org.sensorhub.api.command.ICommandStreamInfo;
import org.sensorhub.api.common.IdEncoders;
import org.sensorhub.api.datastore.command.CommandStreamKey;
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


public class CommandStreamSchemaBindingSweCommon extends ResourceBindingJson<CommandStreamKey, ICommandStreamInfo>
{
    String rootURL;
    ResourceFormat cmdFormat;
    SWEJsonBindings sweBindings;
    
    
    public CommandStreamSchemaBindingSweCommon(ResourceFormat cmdFormat, RequestContext ctx, IdEncoders idEncoders, boolean forReading) throws IOException
    {
        super(ctx, idEncoders, forReading);
        init(cmdFormat, ctx, forReading);
    }
    
    
    public CommandStreamSchemaBindingSweCommon(ResourceFormat cmdFormat, RequestContext ctx, IdEncoders idEncoders, JsonReader reader) throws IOException
    {
        super(ctx, idEncoders, reader);
        init(cmdFormat, ctx, true);
    }


    public CommandStreamSchemaBindingSweCommon(ResourceFormat cmdFormat, RequestContext ctx, IdEncoders idEncoders, JsonWriter writer) throws IOException
    {
        super(ctx, idEncoders, writer);
        init(cmdFormat, ctx, false);
    }
    
    
    void init(ResourceFormat cmdFormat, RequestContext ctx, boolean forReading)
    {
        this.rootURL = ctx.getApiRootURL();
        this.cmdFormat = cmdFormat;
        this.sweBindings = new SWEJsonBindings();
    }
    
    
    @Override
    public ICommandStreamInfo deserialize(JsonReader reader) throws IOException
    {
        DataComponent paramStruct = null;
        DataEncoding paramEncoding = new TextEncodingImpl();
        
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
                    paramStruct = sweBindings.readDataComponent(reader);
                }
                else if ("recordEncoding".equals(prop))
                {
                    paramEncoding = sweBindings.readEncoding(reader);
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
        
        var csInfo = new CommandStreamInfo.Builder()
            .withName(SWECommonUtils.NO_NAME) // name will be set later
            .withSystem(FeatureId.NULL_FEATURE) // System ID will be set later
            .withRecordDescription(paramStruct)
            .withRecordEncoding(paramEncoding)
            .build();
        
        return csInfo;
    }


    @Override
    public void serialize(CommandStreamKey key, ICommandStreamInfo csInfo, boolean showLinks, JsonWriter writer) throws IOException
    {
        writer.beginObject();
        
        // param structure & encoding
        try
        {
            writer.name("paramsSchema");
            sweBindings.writeDataComponent(writer, csInfo.getRecordStructure(), false);
            
            var sweEncoding = SWECommonUtils.getEncoding(csInfo.getRecordStructure(), csInfo.getRecordEncoding(), cmdFormat);
            if (!(sweEncoding instanceof JSONEncoding))
            {
                writer.name("paramsEncoding");
                sweBindings.writeAbstractEncoding(writer, sweEncoding);
            }
        }
        catch (Exception e)
        {
            throw new IOException("Error writing SWE Common command structure", e);
        }
        
        // result structure & encoding
        if (csInfo.hasInlineResult())
        {
            try
            {
                writer.name("resultSchema");
                sweBindings.writeDataComponent(writer, csInfo.getResultStructure(), false);
                
                var sweEncoding = SWECommonUtils.getEncoding(csInfo.getResultStructure(), csInfo.getResultEncoding(), cmdFormat);
                if (!(sweEncoding instanceof JSONEncoding))
                {
                    writer.name("resultEncoding");
                    sweBindings.writeAbstractEncoding(writer, csInfo.getRecordEncoding());
                }
            }
            catch (Exception e)
            {
                throw new IOException("Error writing SWE Common result structure", e);
            }
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
