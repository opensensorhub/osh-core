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


public class CommandStreamSchemaBindingJson extends ResourceBindingJson<CommandStreamKey, ICommandStreamInfo>
{
    String rootURL;
    SWEJsonBindings sweBindings;
    
    
    public CommandStreamSchemaBindingJson(RequestContext ctx, IdEncoders idEncoders, boolean forReading) throws IOException
    {
        super(ctx, idEncoders, forReading);
        init(ctx, forReading);
    }
    
    
    CommandStreamSchemaBindingJson(RequestContext ctx, IdEncoders idEncoders, JsonReader reader) throws IOException
    {
        super(ctx, idEncoders, reader);
        init(ctx, true);
    }
    
    
    CommandStreamSchemaBindingJson(RequestContext ctx, IdEncoders idEncoders, JsonWriter writer) throws IOException
    {
        super(ctx, idEncoders, writer);
        init(ctx, false);
    }
    
    
    void init(RequestContext ctx, boolean forReading)
    {
        this.rootURL = ctx.getApiRootURL();
        this.sweBindings = new SWEJsonBindings();
    }
    
    
    @Override
    public ICommandStreamInfo deserialize(JsonReader reader) throws IOException
    {
        DataComponent commandStruct = null;
        DataComponent resultStruct = null;
        DataComponent feasibilityResultStruct = null;
        DataEncoding commandEncoding = new TextEncodingImpl();
        DataEncoding resultEncoding = new TextEncodingImpl();
        DataEncoding feasibilityResultEncoding = new TextEncodingImpl();
        
        try
        {
            // read BEGIN_OBJECT only if not already read by caller
            // this happens when reading embedded schema and auto-detecting command format
            if (reader.peek() == JsonToken.BEGIN_OBJECT)
                reader.beginObject();
            
            while (reader.hasNext())
            {
                var prop = reader.nextName();
                
                if ("parametersSchema".equals(prop))
                {
                    commandStruct = sweBindings.readDataComponent(reader);
                    commandStruct.setName(SWECommonUtils.NO_NAME);
                }
                else if ("paramsEncoding".equals(prop))
                {
                    commandEncoding = sweBindings.readEncoding(reader);
                }
                else if ("resultSchema".equals(prop))
                {
                    resultStruct = sweBindings.readDataComponent(reader);
                    resultStruct.setName(SWECommonUtils.NO_NAME);
                }
                else if ("resultEncoding".equals(prop))
                {
                    resultEncoding = sweBindings.readEncoding(reader);
                }
                else if("feasibilityResultSchema".equals(prop))
                {
                    // TODO: feasibility results are dropped currently
                    feasibilityResultStruct = sweBindings.readDataComponent(reader);
                    feasibilityResultStruct.setName(SWECommonUtils.NO_NAME);
                }
                else if("feasibilityResultEncoding".equals(prop))
                {
                    feasibilityResultEncoding = sweBindings.readEncoding(reader);
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
        
        return new CommandStreamInfo.Builder()
            .withName(SWECommonUtils.NO_NAME) // name will be set later
            .withSystem(FeatureId.NULL_FEATURE) // System ID will be set later
            .withRecordDescription(commandStruct)
            .withRecordEncoding(commandEncoding)
            .withResultDescription(resultStruct)
            .withResultEncoding(resultEncoding)
            .withFeasibilityResultDescription(feasibilityResultStruct)
            .withFeasibilityResultEncoding(feasibilityResultEncoding)
            .build();
    }


    @Override
    public void serialize(CommandStreamKey key, ICommandStreamInfo dsInfo, boolean showLinks, JsonWriter writer) throws IOException
    {
        writer.beginObject();
        writer.name("commandFormat").value(ResourceFormat.JSON.toString());
        
        // param structure & encoding
        try
        {
            writer.name("parametersSchema");
            sweBindings.writeDataComponent(writer, dsInfo.getRecordStructure(), false);
        }
        catch (Exception e)
        {
            throw new IOException("Error writing command structure", e);
        }
        
        // result structure & encoding
        if (dsInfo.hasInlineResult())
        {
            try
            {
                writer.name("resultSchema");
                sweBindings.writeDataComponent(writer, dsInfo.getResultStructure(), false);
            }
            catch (Exception e)
            {
                throw new IOException("Error writing command structure", e);
            }
        }

        // feasibility result schema
        if (dsInfo.hasFeasibilityResult())
        {
            try
            {
                writer.name("feasibilityResultSchema");
                sweBindings.writeDataComponent(writer, dsInfo.getFeasibilityResultStructure(), false);
            }
            catch (Exception e)
            {
                throw new IOException("Error writing feasibility result structure", e);
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
