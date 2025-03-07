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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import org.sensorhub.api.common.IdEncoders;
import org.sensorhub.api.data.DataStreamInfo;
import org.sensorhub.api.data.IDataStreamInfo;
import org.sensorhub.api.database.IObsSystemDatabase;
import org.sensorhub.api.datastore.obs.DataStreamKey;
import org.sensorhub.api.feature.FeatureId;
import org.sensorhub.impl.service.consys.InvalidRequestException;
import org.sensorhub.impl.service.consys.ResourceParseException;
import org.sensorhub.impl.service.consys.SWECommonUtils;
import org.sensorhub.impl.service.consys.ServiceErrors;
import org.sensorhub.impl.service.consys.deployment.DeploymentHandler;
import org.sensorhub.impl.service.consys.feature.FeatureHandler;
import org.sensorhub.impl.service.consys.feature.FoiHandler;
import org.sensorhub.impl.service.consys.procedure.ProcedureHandler;
import org.sensorhub.impl.service.consys.resource.RequestContext;
import org.sensorhub.impl.service.consys.resource.ResourceBindingJson;
import org.sensorhub.impl.service.consys.resource.ResourceFormat;
import org.sensorhub.impl.service.consys.resource.ResourceLink;
import org.sensorhub.impl.service.consys.system.SystemHandler;
import org.vast.data.DataIterator;
import org.vast.data.TextEncodingImpl;
import org.vast.swe.SWEHelper;
import org.vast.swe.SWEStaxBindings;
import org.vast.swe.json.SWEJsonStreamReader;
import org.vast.swe.json.SWEJsonStreamWriter;
import org.vast.util.Asserts;
import org.vast.util.TimeExtent;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import net.opengis.swe.v20.BinaryEncoding;
import net.opengis.swe.v20.DataArray;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataRecord;
import net.opengis.swe.v20.Vector;


public class DataStreamBindingJson extends ResourceBindingJson<DataStreamKey, IDataStreamInfo>
{
    final DataStreamAssocs assocs;
    final String rootURL;
    final SWEStaxBindings sweBindings;
    final Map<String, CustomObsFormat> customFormats;
    SWEJsonStreamReader sweReader;
    SWEJsonStreamWriter sweWriter;
    
    enum ResultType {
        measure,
        vector,
        record,
        coverage1D,
        coverage2D,
        coverage3D,
        coverage
    }
    
    
    public DataStreamBindingJson(RequestContext ctx, IdEncoders idEncoders, IObsSystemDatabase db, boolean forReading, Map<String, CustomObsFormat> customFormats) throws IOException
    {
        super(ctx, idEncoders, forReading);
        
        this.assocs = new DataStreamAssocs(db, idEncoders);
        this.rootURL = ctx.getApiRootURL();
        this.sweBindings = new SWEStaxBindings();
        this.customFormats = Asserts.checkNotNull(customFormats);
        
        if (forReading)
            this.sweReader = new SWEJsonStreamReader(reader);
        else
            this.sweWriter = new SWEJsonStreamWriter(writer);
    }
    
    
    @Override
    public IDataStreamInfo deserialize(JsonReader reader) throws IOException
    {
        boolean requireSchema = !ctx.isClientSide();
        
        // if array, prepare to parse first element
        if (reader.peek() == JsonToken.BEGIN_ARRAY)
            reader.beginArray();
        
        if (reader.peek() == JsonToken.END_DOCUMENT || !reader.hasNext())
            return null;
                
        String name = null;
        String description = null;
        String outputName = null;
        TimeExtent validTime = null;
        FeatureId sysRef = null;
        FeatureId deplRef = null;
        FeatureId procRef = null;
        FeatureId foiRef = null;
        FeatureId sfRef = null;
        IDataStreamInfo dsInfo = null;
        
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
                else if ("outputName".equals(prop))
                    outputName = reader.nextString();
                else if ("validTime".equals(prop))
                    validTime = geojsonBindings.readTimeExtent(reader);
                else if ("system@link".equals(prop))
                    sysRef = readFeatureRef(reader);
                else if ("procedure@link".equals(prop))
                    procRef = readFeatureRef(reader);
                else if ("deployment@link".equals(prop))
                    deplRef = readFeatureRef(reader);
                else if ("featureOfInterest@link".equals(prop))
                    foiRef = readFeatureRef(reader);
                else if ("samplingFeature@link".equals(prop))
                    sfRef = readFeatureRef(reader);
                else if ("schema".equals(prop))
                {
                    reader.beginObject();
                    
                    // obsFormat must come first!
                    if (!reader.nextName().equals("obsFormat"))
                        throw new ResourceParseException(MISSING_PROP_ERROR_MSG + "schema/obsFormat");
                    var obsFormat = reader.nextString();
                    
                    ResourceBindingJson<DataStreamKey, IDataStreamInfo> schemaBinding = null;
                    if (ResourceFormat.OM_JSON.getMimeType().equals(obsFormat))
                        schemaBinding = new DataStreamSchemaBindingOmJson(ctx, idEncoders, reader);
                    else if (obsFormat.startsWith(ResourceFormat.SWE_FORMAT_PREFIX))
                        schemaBinding = new DataStreamSchemaBindingSweCommon(ResourceFormat.fromMimeType(obsFormat), ctx, idEncoders, reader);
                    if (schemaBinding == null)
                        throw ServiceErrors.unsupportedFormat(obsFormat);
                    
                    dsInfo = schemaBinding.deserialize(reader);
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
        if (outputName == null)
            throw new ResourceParseException(MISSING_PROP_ERROR_MSG + "outputName");
        if (requireSchema && dsInfo == null)
            throw new ResourceParseException(MISSING_PROP_ERROR_MSG + "schema");
        
        if (requireSchema)
        {
            // assign outputName to data component
            dsInfo.getRecordStructure().setName(outputName);
            
            // create datastream info object
            dsInfo = DataStreamInfo.Builder.from(dsInfo)
                .withSystem(sysRef != null ? sysRef : FeatureId.NULL_FEATURE)
                .withName(name)
                .withDescription(description)
                .withValidTime(validTime)
                .withDeployment(deplRef)
                .withProcedure(procRef)
                .withFeatureOfInterest(foiRef)
                .withSamplingFeature(sfRef)
                .build();
        }
        else
        {
            var resultStruct = new SWEHelper().createText()
                .name(outputName)
                .build();
            
            dsInfo = new DataStreamInfo.Builder()
                .withSystem(sysRef != null ? sysRef : FeatureId.NULL_FEATURE)
                .withName(name)
                .withDescription(description)
                .withValidTime(validTime)
                .withDeployment(deplRef)
                .withProcedure(procRef)
                .withFeatureOfInterest(foiRef)
                .withSamplingFeature(sfRef)
                .withRecordDescription(resultStruct)
                .withRecordEncoding(new TextEncodingImpl())
                .build();
        }
        
        return dsInfo;
    }


    @Override
    public void serialize(DataStreamKey key, IDataStreamInfo dsInfo, boolean showLinks, JsonWriter writer) throws IOException
    {
        serialize(key, dsInfo, showLinks, false, writer);
    }
    
    
    public void serialize(DataStreamKey key, IDataStreamInfo dsInfo, boolean showLinks, boolean expandSchema, JsonWriter writer) throws IOException
    {
        var dsId = (key != null && !ctx.isClientSide()) ?
            idEncoders.getDataStreamIdEncoder().encodeID(key.getInternalID()) : null;
        
        var sysId = (dsInfo.getSystemID() != null && dsInfo.getSystemID() != FeatureId.NULL_FEATURE && !ctx.isClientSide()) ?
            idEncoders.getSystemIdEncoder().encodeID(dsInfo.getSystemID().getInternalID()) : null;
        
        writer.beginObject();
        
        if (dsId != null)
            writer.name("id").value(dsId);
        
        writer.name("name").value(dsInfo.getName());
        
        if (dsInfo.getDescription() != null)
            writer.name("description").value(dsInfo.getDescription());
        
        if (sysId != null)
        {
            writer.name("system@id").value(sysId);
            writer.name("system@link");
            writeLink(writer, dsInfo.getSystemID(), SystemHandler.class);
        }
        
        writer.name("outputName").value(dsInfo.getOutputName());
        
        if (dsInfo.getValidTime() != null)
        {
            writer.name("validTime");
            geojsonBindings.writeTimeExtent(writer, dsInfo.getValidTime());
        }
        
        // procedure
        var procRef = dsInfo.getProcedureID();
        if (procRef != null)
        {
            writer.name("procedure@link");
            writeLink(writer, procRef, ProcedureHandler.class);
        }
        
        // deployment
        var deplRef = dsInfo.getDeploymentID();
        if (deplRef != null)
        {
            writer.name("deployment@link");
            writeLink(writer, deplRef, DeploymentHandler.class);
        }
        
        // ultimate foi
        var foiRef = dsInfo.getFeatureOfInterestID();
        if (foiRef != null)
        {
            writer.name("featureOfInterest@link");
            writeLink(writer, foiRef, FeatureHandler.class);
        }
        
        // sampling feature
        var sfRef = dsInfo.getSamplingFeatureID();
        if (sfRef != null)
        {
            writer.name("samplingFeature@link");
            writeLink(writer, sfRef, FoiHandler.class);
        }
        
        if (dsInfo.getPhenomenonTimeRange() != null)
        {
            writer.name("phenomenonTime");
            geojsonBindings.writeTimeExtent(writer, dsInfo.getPhenomenonTimeRange());
        }

        if (dsInfo.getResultTimeRange() != null)
        {
            writer.name("resultTime");
            geojsonBindings.writeTimeExtent(writer, dsInfo.getResultTimeRange());
        }
        
        if (dsInfo.hasDiscreteResultTimes())
        {
            writer.name("runTimes").beginArray();
            for (var time: dsInfo.getDiscreteResultTimes().keySet())
                writer.value(time.toString());
            writer.endArray();
        }

        if (ctx.isClientSide() || expandSchema)
        {
            writer.name("schema");
            ResourceBindingJson<DataStreamKey, IDataStreamInfo> schemaBinding;
            if (dsInfo.getRecordEncoding() instanceof BinaryEncoding)
                schemaBinding = new DataStreamSchemaBindingSweCommon(ResourceFormat.SWE_BINARY, ctx, idEncoders, writer);
            else
                schemaBinding = new DataStreamSchemaBindingOmJson(ctx, idEncoders, writer);
            schemaBinding.serialize(null, dsInfo, false);
        }
        else
        {
            // observed properties
            writer.name("observedProperties").beginArray();
            for (var prop: SWECommonUtils.getProperties(dsInfo.getRecordStructure()))
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
            
            // result type
            writer.name("resultType").value(getResultType(dsInfo.getRecordStructure()).toString());
            
            // available formats
            writer.name("formats").beginArray();
            for (var f: SWECommonUtils.getAvailableFormats(dsInfo, customFormats))
                writer.value(f);
            writer.endArray();
        }
        
        // links
        if (showLinks)
        {
            var links = new ArrayList<ResourceLink>();
            links.add(assocs.getCanonicalLink(dsId));
            links.add(assocs.getAlternateLink(dsId, ResourceFormat.HTML, "HTML"));
            links.add(assocs.getParentLink(dsInfo, ResourceFormat.JSON));
            links.add(assocs.getObservationsLink(dsId, ResourceFormat.JSON));
            writeLinksAsJson(writer, links);
        }
        
        writer.endObject();
        writer.flush();
    }
    
    
    protected ResultType getResultType(DataComponent recordStruct)
    {
        boolean hasArray = false;
        boolean hasRecord = false;
        boolean hasVector = false;
        
        for (var c: new DataIterator(recordStruct))
        {
            if (c instanceof DataArray)
                hasArray = true;
            else if (c instanceof DataRecord && (c.getParent() != null || c.getComponentCount() > 2))
                hasRecord = true;
            else if (c instanceof Vector)
                hasVector = true;
        }
        
        if (hasArray)
            return ResultType.coverage;
        else if (hasRecord)
            return ResultType.record;
        else if (hasVector)
            return ResultType.vector;
        else
            return ResultType.measure;
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
