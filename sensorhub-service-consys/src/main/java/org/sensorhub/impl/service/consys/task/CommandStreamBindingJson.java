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
import java.util.ArrayList;
import java.util.Collection;
import net.opengis.swe.v20.BinaryEncoding;
import org.sensorhub.api.command.CommandStreamInfo;
import org.sensorhub.api.command.ICommandStreamInfo;
import org.sensorhub.api.common.IdEncoders;
import org.sensorhub.api.database.IObsSystemDatabase;
import org.sensorhub.api.datastore.command.CommandStreamKey;
import org.sensorhub.api.feature.FeatureId;
import org.sensorhub.impl.service.consys.InvalidRequestException;
import org.sensorhub.impl.service.consys.ResourceParseException;
import org.sensorhub.impl.service.consys.SWECommonUtils;
import org.sensorhub.impl.service.consys.resource.RequestContext;
import org.sensorhub.impl.service.consys.resource.ResourceBindingJson;
import org.sensorhub.impl.service.consys.resource.ResourceFormat;
import org.sensorhub.impl.service.consys.resource.ResourceLink;
import org.sensorhub.impl.service.consys.system.SystemHandler;
import org.vast.data.TextEncodingImpl;
import org.vast.swe.SWEHelper;
import org.vast.swe.SWEStaxBindings;
import org.vast.swe.json.SWEJsonStreamReader;
import org.vast.swe.json.SWEJsonStreamWriter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;


public class CommandStreamBindingJson extends ResourceBindingJson<CommandStreamKey, ICommandStreamInfo>
{
    final CommandStreamAssocs assocs;
    final String rootURL;
    final SWEStaxBindings sweBindings;
    SWEJsonStreamReader sweReader;
    SWEJsonStreamWriter sweWriter;
    
    
    public CommandStreamBindingJson(RequestContext ctx, IdEncoders idEncoders, IObsSystemDatabase db, boolean forReading) throws IOException
    {
        super(ctx, idEncoders, forReading);
        
        this.assocs = new CommandStreamAssocs(db, idEncoders);
        this.rootURL = ctx.getApiRootURL();
        this.sweBindings = new SWEStaxBindings();
        
        if (forReading)
            this.sweReader = new SWEJsonStreamReader(reader);
        else
            this.sweWriter = new SWEJsonStreamWriter(writer);
    }
    
    
    @Override
    public ICommandStreamInfo deserialize(JsonReader reader) throws IOException
    {
        boolean requireSchema = !ctx.isClientSide();

        // if array, prepare to parse first element
        if (reader.peek() == JsonToken.BEGIN_ARRAY)
            reader.beginArray();
        
        if (reader.peek() == JsonToken.END_DOCUMENT || !reader.hasNext())
            return null;
                
        String name = null;
        String description = null;
        String inputName = null;
        FeatureId sysRef = null;
        ICommandStreamInfo csInfo = null;
        
        try
        {
            reader.beginObject();
            while (reader.hasNext())
            {
                var prop = reader.nextName();
                
                if ("name".equals(prop))
                    name = reader.nextString();
                else if ("description".equals(prop))
                    description = reader.nextString();
                else if ("inputName".equals(prop))
                    inputName = reader.nextString();
                //else if ("validTime".equals(prop))
                //    validTime = geojsonBindings.readTimeExtent(reader);
                else if ("system@link".equals(prop))
                    sysRef = readFeatureRef(reader);
                else if ("schema".equals(prop))
                {
                    reader.beginObject();
                    
                    /*// obsFormat must come first!
                    if (!reader.nextName().equals("commandFormat"))
                        throw new ResourceParseException(MISSING_PROP_ERROR_MSG + "schema/obsFormat");
                    var obsFormat = reader.nextString();
                    
                    ResourceBindingJson<DataStreamKey, IDataStreamInfo> schemaBinding = null;
                    if (ResourceFormat.JSON.getMimeType().equals(obsFormat))
                        schemaBinding = new CommandStreamSchemaBindingJson(ctx, idEncoders, reader);
                    
                    if (schemaBinding == null)
                        throw ServiceErrors.unsupportedFormat(obsFormat);*/
                    var schemaBinding = new CommandStreamSchemaBindingJson(ctx, idEncoders, reader);
                    csInfo = schemaBinding.deserialize(reader);
                }
                else
                    reader.skipValue();
            }
            reader.endObject();
        }
        catch (InvalidRequestException | ResourceParseException e)
        {
            throw e;
        }
        catch (IOException e)
        {
            throw new ResourceParseException(INVALID_JSON_ERROR_MSG + e.getMessage());
        }
        
        // check that mandatory properties have been parsed
        if (inputName == null)
            throw new ResourceParseException(MISSING_PROP_ERROR_MSG + "inputName");
        if (requireSchema && csInfo == null)
            throw new ResourceParseException(MISSING_PROP_ERROR_MSG + "schema");

        if (requireSchema)
        {
            // assign inputName to data component
            csInfo.getRecordStructure().setName(inputName);

            // create CommandStreamInfo object
            csInfo = CommandStreamInfo.Builder.from(csInfo)
                    .withSystem(sysRef != null ? sysRef : FeatureId.NULL_FEATURE)
                    .withName(name)
                    .withDescription(description)
                    .build();
        }
        else
        {
            var resultStruct = new SWEHelper().createText()
                    .name(inputName)
                    .build();

            csInfo = new CommandStreamInfo.Builder()
                    .withSystem(sysRef != null ? sysRef : FeatureId.NULL_FEATURE)
                    .withName(name)
                    .withDescription(description)
                    .withRecordDescription(resultStruct)
                    .withRecordEncoding(new TextEncodingImpl())
                    .build();
        }

        // assign outputName to data component
        csInfo.getRecordStructure().setName(inputName);
        
        // create datastream info object
        return CommandStreamInfo.Builder.from(csInfo)
            .withName(name)
            .withDescription(description)
            .build();
    }
    
    
    public void serializeCreate(ICommandStreamInfo csInfo) throws IOException
    {
        writer.beginObject();
        writer.name("name").value(csInfo.getName());
        
        if (csInfo.getDescription() != null)
            writer.name("description").value(csInfo.getDescription());
        
        writer.name("inputName").value(csInfo.getControlInputName());
        
        /*if (dsInfo.getValidTime() != null)
        {
            writer.name("validTime");
            geojsonBindings.writeTimeExtent(writer, dsInfo.getValidTime());
        }*/
        
        writer.name("schema");
        var schemaBinding = new CommandStreamSchemaBindingJson(ctx, idEncoders, writer);
        schemaBinding.serialize(null, csInfo, false);
        
        writer.endObject();
        writer.flush();
    }


    @Override
    public void serialize(CommandStreamKey key, ICommandStreamInfo csInfo, boolean showLinks, JsonWriter writer) throws IOException
    {
        serialize(key, csInfo, showLinks, false, writer);
    }

    public void serialize(CommandStreamKey key, ICommandStreamInfo csInfo, boolean showLinks, boolean expandSchema, JsonWriter writer) throws IOException
    {
        var dsId = key != null ?
                idEncoders.getCommandStreamIdEncoder().encodeID(key.getInternalID()) : null;
        var sysId = (csInfo.getSystemID() != null && csInfo.getSystemID() != FeatureId.NULL_FEATURE) ?
                idEncoders.getSystemIdEncoder().encodeID(csInfo.getSystemID().getInternalID()) : null;

        writer.beginObject();
        if (dsId != null)
            writer.name("id").value(dsId);

        writer.name("name").value(csInfo.getName());

        if (csInfo.getDescription() != null)
            writer.name("description").value(csInfo.getDescription());

        if (sysId != null)
        {
            writer.name("system@id").value(sysId);
            writer.name("system@link");
            writeLink(writer, csInfo.getSystemID(), SystemHandler.class);
        }

        writer.name("inputName").value(csInfo.getControlInputName());

        if (csInfo.getValidTime() != null)
        {
            writer.name("validTime");
            geojsonBindings.writeTimeExtent(writer, csInfo.getValidTime());
        }

        if (csInfo.getIssueTimeRange() != null)
        {
            writer.name("issueTime");
            geojsonBindings.writeTimeExtent(writer, csInfo.getIssueTimeRange());
        }

        if (csInfo.getExecutionTimeRange() != null)
        {
            writer.name("executionTime");
            geojsonBindings.writeTimeExtent(writer, csInfo.getExecutionTimeRange());
        }

        if (ctx.isClientSide() || expandSchema)
        {
            writer.name("schema");
            ResourceBindingJson<CommandStreamKey, ICommandStreamInfo> schemaBinding;
            if (csInfo.getRecordEncoding() instanceof BinaryEncoding)
                schemaBinding = new CommandStreamSchemaBindingSweCommon(ResourceFormat.SWE_BINARY, ctx, idEncoders, writer);
            else
                schemaBinding = new CommandStreamSchemaBindingJson(ctx, idEncoders, writer);

            schemaBinding.serialize(null, csInfo, false);
        }
        else
        {
            // observed properties
            writer.name("controlledProperties").beginArray();
            for (var prop : SWECommonUtils.getProperties(csInfo.getRecordStructure()))
            {
                writer.beginObject();
                if (prop.getDefinition() != null)
                    writer.name("definition").value(prop.getDefinition());
                if (prop.getLabel() != null)
                    writer.name("label").value(prop.getLabel());
                if (prop.getDescription() != null)
                    writer.name("description").value(prop.getDescription());
                writer.endObject();
            }
            writer.endArray();

            // available formats
            writer.name("formats").beginArray();
            writer.value(ResourceFormat.JSON.getMimeType());
            if (ResourceFormat.allowNonBinaryFormat(csInfo.getRecordEncoding()))
            {
                writer.value(ResourceFormat.SWE_JSON.getMimeType());
                writer.value(ResourceFormat.SWE_TEXT.getMimeType());
                writer.value(ResourceFormat.SWE_XML.getMimeType());
            }
            writer.value(ResourceFormat.SWE_BINARY.getMimeType());
            writer.endArray();
        }

        // links
        if (showLinks)
        {
            var links = new ArrayList<ResourceLink>();
            links.add(assocs.getCanonicalLink(dsId));
            links.add(assocs.getAlternateLink(dsId, ResourceFormat.HTML, "HTML"));
            links.add(assocs.getParentLink(csInfo, ResourceFormat.JSON));
            links.add(assocs.getCommandsLink(dsId, ResourceFormat.JSON));
            writeLinksAsJson(writer, links);
        }
        
        writer.endObject();
        writer.flush();
    }


    @Override
    public void startCollection() throws IOException
    {
        if (reader != null)
            startJsonCollection(reader);
        else
            startJsonCollection(writer);
    }


    @Override
    public void endCollection(Collection<ResourceLink> links) throws IOException
    {
        endJsonCollection(writer, links);
    }
}
